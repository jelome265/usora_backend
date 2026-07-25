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
}

impl IdentityVerificationServiceImpl {
    pub fn new(engine: Arc<FaceMatchingEngine>) -> Self {
        IdentityVerificationServiceImpl { engine }
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
            .identify_face(&probe_image, top_k)
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
        warn!(
            request_id = %request.get_ref().request_id,
            "Document verification not implemented in face matching engine"
        );
        Err(Status::unimplemented("Document verification not available in this service"))
    }

    async fn extract_document_data(
        &self,
        request: Request<DocumentExtractionRequest>,
    ) -> Result<Response<DocumentExtractionResponse>, Status> {
        warn!(
            request_id = %request.get_ref().request_id,
            "Document extraction not implemented in face matching engine"
        );
        Err(Status::unimplemented("Document extraction not available in this service"))
    }

    async fn verify_document_authenticity(
        &self,
        request: Request<AuthenticityRequest>,
    ) -> Result<Response<AuthenticityResponse>, Status> {
        warn!(
            request_id = %request.get_ref().request_id,
            "Document authenticity not implemented in face matching engine"
        );
        Err(Status::unimplemented("Document authenticity not available in this service"))
    }
}
