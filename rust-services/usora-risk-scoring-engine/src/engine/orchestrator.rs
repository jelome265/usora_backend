use crate::config::ServiceConfig;
use crate::engine::cache::MultiLevelCache;
use crate::engine::{RiskEngine, RiskEngineError};
use crate::ml::feature_store::{CompositeFeatureStore, FeatureStore};
use crate::ml::inference::InferenceService;
use crate::ml::{FeatureMap, ModelEnsemble};
use crate::models::{
    ApplicantScoringRequest, ExplainabilityRequest, ExplainabilityResponse, FeatureValue,
    RiskFactorsRequest, RiskFactorsResponse, ScoringResponse, TransactionScoringRequest,
};
use crate::rules::evaluator::RuleEvaluator;
use crate::rules::registry::RuleRegistry;
use crate::rules::ContextMap;
use crate::scoring::engine::PipelineScoringEngine;
use crate::scoring::{ScoringEngine, ScoringError};
use crate::utils::Stopwatch;
use async_trait::async_trait;
use std::collections::HashMap;
use std::sync::Arc;
use uuid::Uuid;

pub struct ScoringOrchestrator {
    scoring_engine: Arc<PipelineScoringEngine>,
    ml_service: Arc<InferenceService>,
    rule_evaluator: Arc<RuleEvaluator>,
    rule_registry: Arc<RuleRegistry>,
    feature_store: Arc<CompositeFeatureStore>,
    cache: Arc<MultiLevelCache>,
    config: Arc<ServiceConfig>,
}

impl ScoringOrchestrator {
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        scoring_engine: Arc<PipelineScoringEngine>,
        ml_service: Arc<InferenceService>,
        rule_evaluator: Arc<RuleEvaluator>,
        rule_registry: Arc<RuleRegistry>,
        feature_store: Arc<CompositeFeatureStore>,
        cache: Arc<MultiLevelCache>,
        config: Arc<ServiceConfig>,
    ) -> Self {
        Self {
            scoring_engine,
            ml_service,
            rule_evaluator,
            rule_registry,
            feature_store,
            cache,
            config,
        }
    }

    pub async fn score_applicant_with_trace(
        &self,
        request: &ApplicantScoringRequest,
    ) -> Result<ScoringResponse, RiskEngineError> {
        let sw = Stopwatch::start();
        let span = tracing::info_span!(
            "score_applicant",
            applicant_id = %request.applicant_id,
            tenant_id = %request.tenant_id,
            request_id = %request.request_id
        );
        let _guard = span.enter();

        tracing::debug!("Starting applicant scoring pipeline");

        let result = self
            .scoring_engine
            .calculate_score(request)
            .await
            .map_err(|e| RiskEngineError::ScoringError(e.to_string()))?;

        tracing::info!(
            applicant_id = %request.applicant_id,
            score = %result.composite_score,
            risk_level = ?result.risk_level,
            processing_time_ms = %result.processing_time_ms,
            "Applicant scored successfully"
        );

        Ok(result)
    }

    pub async fn score_transaction(
        &self,
        request: &TransactionScoringRequest,
    ) -> Result<ScoringResponse, RiskEngineError> {
        let sw = Stopwatch::start();
        let span = tracing::info_span!(
            "score_transaction",
            transaction_id = %request.transaction_id,
            applicant_id = %request.applicant_id,
            tenant_id = %request.tenant_id
        );
        let _guard = span.enter();

        let scoring_request = ApplicantScoringRequest {
            applicant_id: request.applicant_id.clone(),
            tenant_id: request.tenant_id.clone(),
            transaction_id: Some(request.transaction_id.clone()),
            features: request.features.clone(),
            include_explanation: true,
            request_id: Uuid::new_v4(),
        };

        let result = self
            .scoring_engine
            .calculate_score(&scoring_request)
            .await
            .map_err(|e| RiskEngineError::ScoringError(e.to_string()))?;

        Ok(result)
    }

    pub async fn explain_risk_score(
        &self,
        request: &ExplainabilityRequest,
    ) -> Result<ExplainabilityResponse, RiskEngineError> {
        let explanation = self
            .scoring_engine
            .explain_score(request)
            .await
            .map_err(|e| RiskEngineError::ScoringError(e.to_string()))?;

        Ok(ExplainabilityResponse {
            score_id: request.score_id,
            explanation,
            model_version: self.config.models.applicant_risk.version.clone(),
            rule_version: "1.0".into(),
        })
    }

    pub async fn get_risk_factors(
        &self,
        _request: &RiskFactorsRequest,
    ) -> Result<RiskFactorsResponse, RiskEngineError> {
        let cached = self
            .cache
            .get(
                &_request.tenant_id,
                &_request.applicant_id,
                &self.config.models.applicant_risk.version,
            )
            .await;

        match cached {
            Some(response) => Ok(RiskFactorsResponse {
                score_id: response.score_id,
                risk_factors: response.risk_factors,
                total_risk_score: response.composite_score,
                risk_level: response.risk_level,
            }),
            None => Err(RiskEngineError::NotFound(format!(
                "Score {} not found",
                _request.score_id
            ))),
        }
    }

    pub async fn batch_score(
        &self,
        requests: &[ApplicantScoringRequest],
    ) -> Result<Vec<ScoringResponse>, RiskEngineError> {
        self.scoring_engine
            .calculate_batch(requests)
            .await
            .map_err(|e| RiskEngineError::ScoringError(e.to_string()))
    }

    pub async fn update_model(
        &self,
        model_id: &str,
        model_path: &str,
        version: &str,
    ) -> Result<(), RiskEngineError> {
        let config = if model_id == self.config.models.applicant_risk.model_id {
            &self.config.models.applicant_risk
        } else if model_id == self.config.models.transaction_risk.model_id {
            &self.config.models.transaction_risk
        } else {
            return Err(RiskEngineError::NotFound(format!(
                "Model {} not found",
                model_id
            )));
        };

        let _ = config;
        Ok(())
    }

    pub async fn handle_fallback(
        &self,
        error: &RiskEngineError,
        request: &ApplicantScoringRequest,
    ) -> Result<ScoringResponse, RiskEngineError> {
        tracing::warn!(
            error = %error,
            applicant_id = %request.applicant_id,
            "Falling back to rule-only scoring"
        );

        let context_map: ContextMap = request
            .features
            .iter()
            .map(|(k, v)| (k.clone(), v.clone()))
            .collect();

        let tenant_rules = self
            .rule_registry
            .compile_rules_for_tenant(&request.tenant_id)
            .await
            .map_err(|e| RiskEngineError::RuleError(e.to_string()))?;

        let rule_results = self
            .rule_evaluator
            .evaluate_rules(&context_map, &tenant_rules)
            .await;

        let rule_delta: f64 = rule_results
            .iter()
            .filter(|r| r.triggered)
            .map(|r| r.score_delta)
            .sum();
        let fallback_score = (0.5 + rule_delta).clamp(0.0, 1.0);

        Ok(ScoringResponse {
            score_id: Uuid::new_v4(),
            applicant_id: request.applicant_id.clone(),
            tenant_id: request.tenant_id.clone(),
            composite_score: fallback_score,
            risk_level: crate::models::RiskLevel::from_score(
                fallback_score,
                &self.config.thresholds,
            ),
            ml_score: None,
            ml_risk_level: None,
            rule_score: Some(rule_delta),
            rule_risk_level: None,
            explanation: None,
            risk_factors: vec![],
            model_version: "fallback".into(),
            rule_version: "1.0".into(),
            processing_time_ms: 0.0,
            computed_at: chrono::Utc::now(),
            cached: false,
        })
    }
}

#[async_trait]
impl RiskEngine for ScoringOrchestrator {
    async fn score_applicant(
        &self,
        request: &ApplicantScoringRequest,
    ) -> Result<ScoringResponse, RiskEngineError> {
        self.score_applicant_with_trace(request).await
    }

    async fn score_transaction(
        &self,
        request: &TransactionScoringRequest,
    ) -> Result<ScoringResponse, RiskEngineError> {
        self.score_transaction(request).await
    }

    async fn batch_score(
        &self,
        requests: &[ApplicantScoringRequest],
    ) -> Result<Vec<ScoringResponse>, RiskEngineError> {
        self.batch_score(requests).await
    }

    async fn explain_score(
        &self,
        _score_id: &uuid::Uuid,
        request: &ExplainabilityRequest,
    ) -> Result<crate::models::ScoreExplanation, RiskEngineError> {
        let response = self.explain_risk_score(request).await?;
        Ok(response.explanation)
    }

    async fn health_check(&self) -> Result<(), RiskEngineError> {
        self.feature_store
            .redis
            .health_check()
            .await
            .map_err(|e| RiskEngineError::FeatureError(e.to_string()))?;
        self.feature_store
            .postgres
            .health_check()
            .await
            .map_err(|e| RiskEngineError::FeatureError(e.to_string()))?;
        self.cache
            .health_check()
            .await
            .map_err(|e| RiskEngineError::CacheError(e.to_string()))?;
        Ok(())
    }
}
