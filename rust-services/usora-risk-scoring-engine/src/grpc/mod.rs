use crate::engine::orchestrator::ScoringOrchestrator;
use crate::engine::RiskEngineError;
use crate::models::{
    ApplicantScoringRequest as InternalRequest, ExplainabilityRequest, FeatureValue,
    ModelUpdateRequest, RiskFactorsRequest, TransactionScoringRequest,
};
use std::collections::HashMap;
use std::sync::Arc;
use tonic::{async_trait, Request, Response, Status};
use uuid::Uuid;

pub mod risk_scoring {
    include!("risk_scoring.rs");
}
use risk_scoring::*;

pub struct RiskScoringServiceImpl {
    orchestrator: Arc<ScoringOrchestrator>,
}

impl RiskScoringServiceImpl {
    pub fn new(orchestrator: Arc<ScoringOrchestrator>) -> Self {
        Self { orchestrator }
    }

    fn map_to_status(e: RiskEngineError) -> Status {
        match e {
            RiskEngineError::NotFound(msg) => Status::not_found(msg),
            RiskEngineError::Timeout => Status::deadline_exceeded("Request timed out"),
            RiskEngineError::FeatureError(msg) => Status::failed_precondition(msg),
            RiskEngineError::MlError(msg) => Status::internal(format!("ML error: {}", msg)),
            RiskEngineError::RuleError(msg) => Status::internal(format!("Rule error: {}", msg)),
            RiskEngineError::ScoringError(msg) => {
                Status::internal(format!("Scoring error: {}", msg))
            }
            RiskEngineError::CacheError(msg) => Status::internal(format!("Cache error: {}", msg)),
            RiskEngineError::ConfigError(msg) => Status::failed_precondition(msg),
            RiskEngineError::TenantIsolationError(msg) => Status::permission_denied(msg),
            RiskEngineError::Internal(e) => Status::internal(e.to_string()),
        }
    }

    fn convert_feature_map(
        proto_features: &HashMap<String, risk_scoring::FeatureValue>,
    ) -> HashMap<String, FeatureValue> {
        let mut features = HashMap::new();
        for (k, v) in proto_features {
            let fv = match v.value.as_ref() {
                Some(risk_scoring::feature_value::Value::StringVal(s)) => {
                    FeatureValue::String(s.clone())
                }
                Some(risk_scoring::feature_value::Value::IntVal(i)) => FeatureValue::Integer(*i),
                Some(risk_scoring::feature_value::Value::FloatVal(f)) => FeatureValue::Float(*f),
                Some(risk_scoring::feature_value::Value::BoolVal(b)) => FeatureValue::Boolean(*b),
                None | Some(_) => FeatureValue::Null,
            };
            features.insert(k.clone(), fv);
        }
        features
    }

    fn to_proto_response(
        response: &crate::models::ScoringResponse,
    ) -> risk_scoring::ScoringResponse {
        risk_scoring::ScoringResponse {
            score_id: response.score_id.to_string(),
            applicant_id: response.applicant_id.clone(),
            tenant_id: response.tenant_id.clone(),
            composite_score: response.composite_score,
            risk_level: format!("{:?}", response.risk_level).to_lowercase(),
            ml_score: response.ml_score.unwrap_or(0.0),
            rule_score: response.rule_score.unwrap_or(0.0),
            model_version: response.model_version.clone(),
            rule_version: response.rule_version.clone(),
            processing_time_ms: response.processing_time_ms,
            computed_at: response.computed_at.to_rfc3339(),
            explanation: response
                .explanation
                .as_ref()
                .map(|e| risk_scoring::ScoreExplanation {
                    method: e.method.clone(),
                    base_score: e.base_score,
                    feature_contributions: e
                        .feature_contributions
                        .iter()
                        .map(|fc| risk_scoring::FeatureContribution {
                            feature_name: fc.feature_name.clone(),
                            importance: fc.importance,
                            shap_value: fc.shap_value,
                        })
                        .collect(),
                    top_risk_drivers: e.top_risk_drivers.clone(),
                    confidence: e.confidence,
                }),
        }
    }
}

#[tonic::async_trait]
impl risk_scoring::risk_scoring_service_server::RiskScoringService for RiskScoringServiceImpl {
    async fn score_applicant(
        &self,
        request: Request<risk_scoring::ApplicantScoringRequest>,
    ) -> Result<Response<risk_scoring::ApplicantScoringResponse>, Status> {
        let req = request.into_inner();
        let features = Self::convert_feature_map(&req.features);

        let internal_req = InternalRequest {
            applicant_id: req.applicant_id,
            tenant_id: req.tenant_id,
            transaction_id: None,
            features,
            include_explanation: req.include_explanation,
            request_id: Uuid::new_v4(),
        };

        let response = self
            .orchestrator
            .score_applicant(&internal_req)
            .await
            .map_err(Self::map_to_status)?;

        Ok(Response::new(risk_scoring::ApplicantScoringResponse {
            response: Some(Self::to_proto_response(&response)),
        }))
    }

    async fn score_transaction(
        &self,
        request: Request<risk_scoring::TransactionScoringRequest>,
    ) -> Result<Response<risk_scoring::TransactionScoringResponse>, Status> {
        let req = request.into_inner();
        let features = Self::convert_feature_map(&req.features);

        let internal_req = TransactionScoringRequest {
            transaction_id: req.transaction_id,
            applicant_id: req.applicant_id,
            tenant_id: req.tenant_id,
            amount: req.amount,
            currency: req.currency,
            transaction_type: req.transaction_type,
            features,
            timestamp: chrono::DateTime::from_timestamp(req.timestamp_seconds, 0)
                .unwrap_or_else(chrono::Utc::now),
        };

        let response = self
            .orchestrator
            .score_transaction(&internal_req)
            .await
            .map_err(Self::map_to_status)?;

        Ok(Response::new(risk_scoring::TransactionScoringResponse {
            response: Some(Self::to_proto_response(&response)),
        }))
    }

    async fn get_risk_factors(
        &self,
        request: Request<risk_scoring::RiskFactorsRequest>,
    ) -> Result<Response<risk_scoring::RiskFactorsResponse>, Status> {
        let req = request.into_inner();
        let internal_req = RiskFactorsRequest {
            score_id: Uuid::parse_str(&req.score_id)
                .map_err(|e| Status::invalid_argument(e.to_string()))?,
            applicant_id: req.applicant_id,
            tenant_id: req.tenant_id,
        };

        let response = self
            .orchestrator
            .get_risk_factors(&internal_req)
            .await
            .map_err(Self::map_to_status)?;

        Ok(Response::new(risk_scoring::RiskFactorsResponse {
            score_id: response.score_id.to_string(),
            risk_factors: response
                .risk_factors
                .into_iter()
                .map(|rf| risk_scoring::RiskFactor {
                    factor_id: rf.factor_id,
                    factor_type: format!("{:?}", rf.factor_type),
                    severity: rf.severity,
                    description: rf.description,
                    source: rf.source,
                })
                .collect(),
            total_risk_score: response.total_risk_score,
            risk_level: format!("{:?}", response.risk_level).to_lowercase(),
        }))
    }

    async fn update_risk_model(
        &self,
        request: Request<risk_scoring::ModelUpdateRequest>,
    ) -> Result<Response<risk_scoring::ModelUpdateResponse>, Status> {
        let req = request.into_inner();
        let internal_req = ModelUpdateRequest {
            model_id: req.model_id,
            tenant_id: if req.tenant_id.is_empty() {
                None
            } else {
                Some(req.tenant_id)
            },
            new_version: req.new_version,
            model_path: req.model_path,
            checksum: req.checksum,
            force: req.force,
        };

        self.orchestrator
            .update_model(
                &internal_req.model_id,
                &internal_req.model_path,
                &internal_req.new_version,
            )
            .await
            .map_err(Self::map_to_status)?;

        Ok(Response::new(risk_scoring::ModelUpdateResponse {
            model_id: internal_req.model_id,
            previous_version: "unknown".into(),
            current_version: internal_req.new_version,
            status: "success".into(),
            reloaded_at: chrono::Utc::now().to_rfc3339(),
        }))
    }

    async fn explain_risk_score(
        &self,
        request: Request<risk_scoring::ExplainabilityRequest>,
    ) -> Result<Response<risk_scoring::ExplainabilityResponse>, Status> {
        let req = request.into_inner();
        let score_id =
            Uuid::parse_str(&req.score_id).map_err(|e| Status::invalid_argument(e.to_string()))?;

        let internal_req = ExplainabilityRequest {
            score_id,
            applicant_id: req.applicant_id,
            tenant_id: req.tenant_id,
            max_features: Some(req.max_features as usize),
            min_importance: Some(req.min_importance),
        };

        let response = self
            .orchestrator
            .explain_risk_score(&internal_req)
            .await
            .map_err(Self::map_to_status)?;

        Ok(Response::new(risk_scoring::ExplainabilityResponse {
            score_id: response.score_id.to_string(),
            explanation: Some(risk_scoring::ScoreExplanation {
                method: response.explanation.method,
                base_score: response.explanation.base_score,
                feature_contributions: response
                    .explanation
                    .feature_contributions
                    .iter()
                    .map(|fc| risk_scoring::FeatureContribution {
                        feature_name: fc.feature_name.clone(),
                        importance: fc.importance,
                        shap_value: fc.shap_value,
                    })
                    .collect(),
                top_risk_drivers: response.explanation.top_risk_drivers,
                confidence: response.explanation.confidence,
            }),
            model_version: response.model_version,
            rule_version: response.rule_version,
        }))
    }

    async fn batch_score_applicants(
        &self,
        request: Request<risk_scoring::BatchScoringRequest>,
    ) -> Result<Response<risk_scoring::BatchScoringResponse>, Status> {
        let req = request.into_inner();

        let internal_requests: Vec<InternalRequest> = req
            .requests
            .into_iter()
            .map(|r| {
                let features = Self::convert_feature_map(&r.features);
                InternalRequest {
                    applicant_id: r.applicant_id,
                    tenant_id: req.tenant_id.clone(),
                    transaction_id: None,
                    features,
                    include_explanation: false,
                    request_id: Uuid::new_v4(),
                }
            })
            .collect();

        let responses = self
            .orchestrator
            .batch_score(&internal_requests)
            .await
            .map_err(Self::map_to_status)?;

        Ok(Response::new(risk_scoring::BatchScoringResponse {
            responses: responses.iter().map(Self::to_proto_response).collect(),
        }))
    }
}
