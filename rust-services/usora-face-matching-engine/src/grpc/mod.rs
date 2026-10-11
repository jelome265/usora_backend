use std::sync::Arc;
use tonic::{Request, Response, Status};
use tracing::{info, info_span, warn};

use crate::models::*;
use crate::utils;
use crate::FaceMatchingEngine;

pub mod proto {
    tonic::include_proto!("biometric");
}

use proto::identity_verification_service_server::IdentityVerificationService;
use proto::{
    AuthenticityRequest, AuthenticityResponse, BiometricMatchRequest, BiometricMatchResponse,
    DocumentExtractionRequest, DocumentExtractionResponse, DocumentVerificationRequest,
    DocumentVerificationResponse, FaceVerificationRequest, FaceVerificationResponse,
    LivenessVerificationRequest, LivenessVerificationResponse, MatchResult,
};

pub struct IdentityVerificationServiceImpl {
    engine: Arc<FaceMatchingEngine>,
    document_client: Option<Arc<dyn DocumentProcessingClient>>,
}

#[async_trait::async_trait]
pub trait DocumentProcessingClient: Send + Sync {
    async fn verify_document(&self, req: DocumentVerificationRequest) -> Result<DocumentVerificationResponse, String>;
    async fn extract_document_data(&self, req: DocumentExtractionRequest) -> Result<DocumentExtractionResponse, String>;
    async fn verify_authenticity(&self, req: AuthenticityRequest) -> Result<AuthenticityResponse, String>;
}

pub struct GrpcDocumentClient {
    client: proto::document_service_client::DocumentServiceClient<tonic::transport::Channel>,
}

#[async_trait::async_trait]
impl DocumentProcessingClient for GrpcDocumentClient {
    async fn verify_document(&self, req: DocumentVerificationRequest) -> Result<DocumentVerificationResponse, String> {
        Err("Document verification requires document processor service".to_string())
    }
    async fn extract_document_data(&self, req: DocumentExtractionRequest) -> Result<DocumentExtractionResponse, String> {
        Err("Document data extraction requires document processor service".to_string())
    }
    async fn verify_authenticity(&self, req: AuthenticityRequest) -> Result<AuthenticityResponse, String> {
        Err("Document authenticity verification requires document processor service".to_string())
    }
}

impl IdentityVerificationServiceImpl {
    pub fn new(engine: Arc<FaceMatchingEngine>) -> Self {
        IdentityVerificationServiceImpl {
            engine,
            document_client: None,
        }
    }

    pub fn with_document_client(mut self, client: Arc<dyn DocumentProcessingClient>) -> Self {
        self.document_client = Some(client);
        self
    }
}

#[tonic::async_trait]
impl IdentityVerificationService for IdentityVerificationServiceImpl {
    async fn verify_face(
        &self,
        request: Request<FaceVerificationRequest>,
    ) -> Result<Response<FaceVerificationResponse>, Status> {
        let _span = info_span!("grpc_verify_face").entered();
        let req = request.into_inner();
        let start = std::time::Instant::now();

        let source_image = utils::load_image_from_bytes(&req.source_image)
            .map_err(|e| Status::invalid_argument(format!("Invalid source image: {}", e)))?;
        let target_image = utils::load_image_from_bytes(&req.target_image)
            .map_err(|e| Status::invalid_argument(format!("Invalid target image: {}", e)))?;

        let threshold = if req.match_threshold > 0.0 {
            req.match_threshold
        } else {
            self.engine.default_threshold()
        };

        let result = self.engine
            .verify_faces(&source_image, &target_image, threshold)
            .await
            .map_err(|e| Status::internal(format!("Verification failed: {}", e)))?;

        let processing_time = start.elapsed().as_millis() as i64;

        let response = FaceVerificationResponse {
            request_id: req.request_id.clone(),
            verified: result.passed_threshold,
            similarity_score: result.similarity_score,
            match_confidence: result.match_confidence,
            source_detection: None,
            target_detection: None,
            source_quality: None,
            target_quality: None,
            liveness: None,
            processing_time_ms: processing_time,
            errors: vec![],
        };

        info!(
            request_id = %req.request_id,
            verified = %response.verified,
            similarity = %response.similarity_score,
            time_ms = %processing_time,
            "Face verification completed"
        );

        Ok(Response::new(response))
    }

    async fn verify_liveness(
        &self,
        request: Request<LivenessVerificationRequest>,
    ) -> Result<Response<LivenessVerificationResponse>, Status> {
        let _span = info_span!("grpc_verify_liveness").entered();
        let req = request.into_inner();
        let start = std::time::Instant::now();

        let image = utils::load_image_from_bytes(&req.image)
            .map_err(|e| Status::invalid_argument(format!("Invalid image: {}", e)))?;

        let result = self.engine
            .check_liveness(&image, &req.challenge_type, req.challenge_data.as_deref())
            .await
            .map_err(|e| Status::internal(format!("Liveness check failed: {}", e)))?;

        let processing_time = start.elapsed().as_millis() as i64;

        let response = LivenessVerificationResponse {
            request_id: req.request_id.clone(),
            is_live: result.is_live,
            confidence: result.confidence,
            spoof_type: format!("{:?}", result.spoof_type),
            details: None,
            processing_time_ms: processing_time,
            errors: vec![],
        };

        info!(
            request_id = %req.request_id,
            is_live = %response.is_live,
            confidence = %response.confidence,
            time_ms = %processing_time,
            "Liveness verification completed"
        );

        Ok(Response::new(response))
    }

    async fn match_biometrics(
        &self,
        request: Request<BiometricMatchRequest>,
    ) -> Result<Response<BiometricMatchResponse>, Status> {
        let _span = info_span!("grpc_match_biometrics").entered();
        let req = request.into_inner();
        let start = std::time::Instant::now();

        let probe_image = utils::load_image_from_bytes(&req.probe_image)
            .map_err(|e| Status::invalid_argument(format!("Invalid probe image: {}", e)))?;

        let top_k = if req.top_k > 0 { req.top_k as usize } else { 10 };

        let result = self.engine
            .identify_face(&probe_image, top_k, &req.tenant_id)
            .await
            .map_err(|e| Status::internal(format!("Biometric match failed: {}", e)))?;

        let processing_time = start.elapsed().as_millis() as i64;

        let matches: Vec<MatchResult> = result.iter().enumerate().map(|(i, r)| {
            MatchResult {
                user_id: r.user_id.map(|u| u.to_string()).unwrap_or_default(),
                similarity_score: r.similarity_score,
                match_confidence: r.match_confidence,
                probe_detection: None,
                probe_quality: None,
                rank: (i + 1) as i32,
            }
        }).collect();

        let response = BiometricMatchResponse {
            request_id: req.request_id.clone(),
            match_found: matches.iter().any(|m| m.similarity_score >= self.engine.default_threshold()),
            matches,
            total_gallery_size: 0,
            processing_time_ms: processing_time,
            errors: vec![],
        };

        info!(
            request_id = %req.request_id,
            matches = %response.matches.len(),
            time_ms = %processing_time,
            "Biometric matching completed"
        );

        Ok(Response::new(response))
    }

    async fn verify_document(
        &self,
        request: Request<DocumentVerificationRequest>,
    ) -> Result<Response<DocumentVerificationResponse>, Status> {
        let req = request.into_inner();
        info!(
            request_id = %req.request_id,
            "Forwarding document verification to document processor"
        );

        if let Some(ref client) = self.document_client {
            match client.verify_document(req).await {
                Ok(resp) => Ok(Response::new(resp)),
                Err(e) => Err(Status::internal(format!("Document verification failed: {e}"))),
            }
        } else {
            warn!("No document processor client configured");
            Err(Status::unimplemented("Document verification not available - no document processor configured"))
        }
    }

    async fn extract_document_data(
        &self,
        request: Request<DocumentExtractionRequest>,
    ) -> Result<Response<DocumentExtractionResponse>, Status> {
        let req = request.into_inner();
        info!(
            request_id = %req.request_id,
            "Forwarding document extraction to document processor"
        );

        if let Some(ref client) = self.document_client {
            match client.extract_document_data(req).await {
                Ok(resp) => Ok(Response::new(resp)),
                Err(e) => Err(Status::internal(format!("Document extraction failed: {e}"))),
            }
        } else {
            warn!("No document processor client configured");
            Err(Status::unimplemented("Document extraction not available - no document processor configured"))
        }
    }

    async fn verify_document_authenticity(
        &self,
        request: Request<AuthenticityRequest>,
    ) -> Result<Response<AuthenticityResponse>, Status> {
        let req = request.into_inner();
        info!(
            request_id = %req.request_id,
            "Forwarding document authenticity check to document processor"
        );

        if let Some(ref client) = self.document_client {
            match client.verify_authenticity(req).await {
                Ok(resp) => Ok(Response::new(resp)),
                Err(e) => Err(Status::internal(format!("Document authenticity check failed: {e}"))),
            }
        } else {
            warn!("No document processor client configured");
            Err(Status::unimplemented("Document authenticity check not available - no document processor configured"))
        }
    }
}
