use crate::config::ServiceConfig;
use crate::engine::cache::MultiLevelCache;
use crate::ml::feature_store::{CompositeFeatureStore, FeatureStore, NormalizationParams};
use crate::ml::inference::InferenceService;
use crate::ml::{FeatureMap, ModelEnsemble, ModelError};
use crate::models::{
    ApplicantScoringRequest, EnsembleResult, ExplainabilityRequest, ExplainabilityResponse,
    FeatureValue, RiskFactorsRequest, RiskFactorsResponse, RiskLevel, RiskThresholds, RuleResult,
    ScoreExplanation, ScoringResponse,
};
use crate::rules::evaluator::RuleEvaluator;
use crate::rules::registry::RuleRegistry;
use crate::rules::ContextMap;
use crate::scoring::calculator::ScoreCalculator;
use crate::scoring::{ScoringEngine, ScoringError};
use crate::utils::Stopwatch;
use async_trait::async_trait;
use chrono::Utc;
use std::collections::HashMap;
use std::sync::Arc;
use tracing::{info_span, Instrument};
use uuid::Uuid;

pub struct PipelineScoringEngine {
    ml_service: Arc<InferenceService>,
    rule_evaluator: Arc<RuleEvaluator>,
    rule_registry: Arc<RuleRegistry>,
    feature_store: Arc<CompositeFeatureStore>,
    cache: Arc<MultiLevelCache>,
    calculator: ScoreCalculator,
    config: Arc<ServiceConfig>,
    normalization: Arc<NormalizationParams>,
}

impl PipelineScoringEngine {
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        ml_service: Arc<InferenceService>,
        rule_evaluator: Arc<RuleEvaluator>,
        rule_registry: Arc<RuleRegistry>,
        feature_store: Arc<CompositeFeatureStore>,
        cache: Arc<MultiLevelCache>,
        config: Arc<ServiceConfig>,
        normalization: Arc<NormalizationParams>,
    ) -> Self {
        let calculator = ScoreCalculator::new(
            0.7,
            0.3,
            config.thresholds.clone(),
            config.explainability.max_features,
            config.explainability.min_feature_importance,
        );
        Self {
            ml_service,
            rule_evaluator,
            rule_registry,
            feature_store,
            cache,
            calculator,
            config,
            normalization,
        }
    }

    async fn execute_pipeline(
        &self,
        tenant_id: &str,
        applicant_id: &str,
        request_features: &HashMap<String, FeatureValue>,
        include_explanation: bool,
        model_id: &str,
    ) -> Result<ScoringResponse, ScoringError> {
        let sw = Stopwatch::start();

        let feature_names: Vec<String> = self
            .config
            .features
            .real_time_features
            .iter()
            .chain(self.config.features.batch_features.iter())
            .cloned()
            .collect();

        let feature_vector = self
            .feature_store
            .get_features_with_fallback(tenant_id, applicant_id, &feature_names)
            .await
            .map_err(|e| ScoringError::FeatureError(e.to_string()))?;

        let mut combined_features = feature_vector.features;
        for (k, v) in request_features {
            combined_features.insert(k.clone(), v.clone());
        }

        let raw_ml_features: FeatureMap = combined_features
            .iter()
            .map(|(k, v)| (k.clone(), v.as_f64().unwrap_or(0.0)))
            .collect();

        let ml_result = self
            .ml_service
            .predict(model_id, &raw_ml_features)
            .await
            .map_err(|e| ScoringError::MlError(e.to_string()))?;

        let context_map: ContextMap = combined_features.clone();
        let tenant_rules = self
            .rule_registry
            .compile_rules_for_tenant(tenant_id)
            .await
            .map_err(|e| ScoringError::RuleError(e.to_string()))?;

        let rule_results = self
            .rule_evaluator
            .evaluate_rules(&context_map, &tenant_rules)
            .await;

        let (composite_score, risk_level, ml_score, ml_risk_level) =
            self.calculator.calculate(&ml_result, &rule_results);

        let explanation = if include_explanation && self.config.explainability.enabled {
            Some(self.calculator.generate_explanation(
                &ml_result,
                &rule_results,
                ml_score,
                composite_score,
            ))
        } else {
            None
        };

        let processing_time_ms = sw.elapsed_ms();

        let response = ScoringResponse {
            score_id: Uuid::new_v4(),
            applicant_id: applicant_id.to_string(),
            tenant_id: tenant_id.to_string(),
            composite_score,
            risk_level,
            ml_score: Some(ml_score),
            ml_risk_level,
            rule_score: Some(
                rule_results
                    .iter()
                    .filter(|r| r.triggered)
                    .map(|r| r.score_delta)
                    .sum(),
            ),
            rule_risk_level: None,
            explanation,
            risk_factors: vec![],
            model_version: ml_result.model_version.clone(),
            rule_version: "1.0".into(),
            processing_time_ms,
            computed_at: Utc::now(),
            cached: false,
        };

        let model_ver = &ml_result.model_version;
        self.cache
            .set(tenant_id, applicant_id, model_ver, response.clone())
            .await;

        Ok(response)
    }
}

#[async_trait]
impl ScoringEngine for PipelineScoringEngine {
    async fn calculate_score(
        &self,
        request: &ApplicantScoringRequest,
    ) -> Result<ScoringResponse, ScoringError> {
        let span = info_span!(
            "calculate_score",
            applicant_id = %request.applicant_id,
            tenant_id = %request.tenant_id,
        );

        async {
            if let Some(cached) = self
                .cache
                .get(
                    &request.tenant_id,
                    &request.applicant_id,
                    &self.config.models.applicant_risk.version,
                )
                .await
            {
                let mut cached = cached;
                cached.cached = true;
                return Ok(cached);
            }

            self.execute_pipeline(
                &request.tenant_id,
                &request.applicant_id,
                &request.features,
                request.include_explanation,
                &self.config.models.applicant_risk.model_id,
            )
            .await
        }
        .instrument(span)
        .await
    }

    async fn calculate_batch(
        &self,
        requests: &[ApplicantScoringRequest],
    ) -> Result<Vec<ScoringResponse>, ScoringError> {
        let mut results = Vec::with_capacity(requests.len());
        for req in requests {
            match self.calculate_score(req).await {
                Ok(response) => results.push(response),
                Err(e) => {
                    tracing::error!(
                        applicant_id = %req.applicant_id,
                        error = %e,
                        "Batch scoring failed for applicant — reporting as Critical/unscored, \
                         not a fabricated neutral result"
                    );
                    // SECURITY/COMPLIANCE: do NOT report a scoring failure as
                    // composite_score=0.5 / RiskLevel::Medium — that is
                    // indistinguishable from a genuine "proceed with normal
                    // review" outcome to any caller that only reads
                    // risk_level (rather than separately checking
                    // model_version == "error"). Fail closed to Critical so
                    // an ML/feature-store/rule-evaluation outage can never
                    // silently look like an acceptable applicant; combined
                    // with model_version/rule_version == "error" this should
                    // route to mandatory manual review, not auto-approval.
                    results.push(ScoringResponse {
                        score_id: Uuid::new_v4(),
                        applicant_id: req.applicant_id.clone(),
                        tenant_id: req.tenant_id.clone(),
                        composite_score: 1.0,
                        risk_level: RiskLevel::Critical,
                        ml_score: None,
                        ml_risk_level: None,
                        rule_score: None,
                        rule_risk_level: None,
                        explanation: None,
                        risk_factors: vec![],
                        model_version: "error".into(),
                        rule_version: "error".into(),
                        processing_time_ms: 0.0,
                        computed_at: Utc::now(),
                        cached: false,
                    });
                }
            }
        }
        Ok(results)
    }

    async fn explain_score(
        &self,
        request: &ExplainabilityRequest,
    ) -> Result<ScoreExplanation, ScoringError> {
        let cached = self
            .cache
            .get(
                &request.tenant_id,
                &request.applicant_id,
                &self.config.models.applicant_risk.version,
            )
            .await;

        if let Some(response) = cached {
            if let Some(explanation) = response.explanation {
                return Ok(explanation);
            }
        }

        let raw_features: FeatureMap = HashMap::new();
        let ml_result = self
            .ml_service
            .predict(&self.config.models.applicant_risk.model_id, &raw_features)
            .await
            .map_err(|e| ScoringError::MlError(e.to_string()))?;

        let feature_importance = self
            .ml_service
            .explain(
                &self.config.models.applicant_risk.model_id,
                &raw_features,
                &ml_result,
            )
            .await
            .map_err(|e| ScoringError::MlError(e.to_string()))?;

        let max_features = request.max_features.unwrap_or(10);
        let min_importance = request.min_importance.unwrap_or(0.01);

        let mut contributions: Vec<_> = feature_importance
            .into_iter()
            .filter(|&(_, v)| v >= min_importance)
            .collect();
        contributions.sort_by(|a, b| b.1.partial_cmp(&a.1).unwrap_or(std::cmp::Ordering::Equal));
        contributions.truncate(max_features);

        let top_drivers: Vec<String> = contributions
            .iter()
            .take(5)
            .map(|(k, _)| k.clone())
            .collect();

        Ok(ScoreExplanation {
            method: "shap".into(),
            base_score: ml_result.probabilities.iter().cloned().fold(0.0, f64::max),
            feature_contributions: contributions
                .into_iter()
                .map(|(name, importance)| crate::models::FeatureContribution {
                    feature_name: name.clone(),
                    value: FeatureValue::Float(importance),
                    importance: importance.abs(),
                    direction: crate::models::ContributionDirection::IncreasesRisk,
                    shap_value: importance,
                })
                .collect(),
            rule_contributions: vec![],
            top_risk_drivers: top_drivers,
            confidence: ml_result.probabilities.iter().cloned().fold(0.0, f64::max),
        })
    }

    async fn health(&self) -> Result<(), ScoringError> {
        self.feature_store
            .redis
            .health_check()
            .await
            .map_err(|e| ScoringError::FeatureError(e.to_string()))?;
        self.feature_store
            .postgres
            .health_check()
            .await
            .map_err(|e| ScoringError::FeatureError(e.to_string()))?;
        self.cache
            .health_check()
            .await
            .map_err(|e| ScoringError::Internal(anyhow::anyhow!(e)))?;
        Ok(())
    }
}
