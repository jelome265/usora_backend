use crate::generated::usora::document::v1::{
    document_service_server::DocumentService,
    ProcessDocumentRequest, ProcessDocumentResponse,
    GetDocumentStatusRequest, GetDocumentStatusResponse,
    ExtractDataRequest, ExtractDataResponse,
    ValidateAuthenticityRequest, ValidateAuthenticityResponse,
    ExtractedField, DocumentData, DocumentValidationResult, AuthenticityScore, DocumentStatus,
};
use crate::models::{self, DocumentImage, ExtractionMethod, ImageFormat};
use crate::Config;
use std::collections::HashMap;
use std::sync::Arc;
use tonic::{async_trait, Request, Response, Status};

pub struct DocumentServiceImpl {
    config: Arc<Config>,
    processor: crate::DocumentProcessor,
}

impl DocumentServiceImpl {
    pub fn new(config: Arc<Config>) -> Self {
        let pipeline = crate::pipeline::PipelineBuilder::default()
            .with_ingestion()
            .with_preprocessing()
            .with_postprocessing()
            .build();
        let processor = crate::DocumentProcessor::new(config.clone(), pipeline);
        Self { config, processor }
    }

    fn field_to_proto(field: &models::ExtractedField) -> ExtractedField {
        ExtractedField {
            name: field.name.clone(),
            value: field.value.clone(),
            confidence: field.confidence,
            method: match field.method {
                models::ExtractionMethod::Ocr => 1,
                models::ExtractionMethod::Mrz => 2,
                models::ExtractionMethod::Barcode => 3,
                models::ExtractionMethod::Nfc => 4,
                models::ExtractionMethod::MlOcr => 1,
            },
            raw_text: field.raw_text.clone().unwrap_or_default(),
            bounding_box: field.bounding_box.as_ref().map(|bb| {
                prost_types::Struct {
                    fields: [
                        ("x".to_string(), prost_types::Value::NumberValue(bb.x as f64)),
                        ("y".to_string(), prost_types::Value::NumberValue(bb.y as f64)),
                        ("width".to_string(), prost_types::Value::NumberValue(bb.width as f64)),
                        ("height".to_string(), prost_types::Value::NumberValue(bb.height as f64)),
                    ].into_iter().collect(),
                }
            }),
        }
    }

    fn validation_to_proto(v: &models::DocumentValidation) -> DocumentValidationResult {
        let mut individual = HashMap::new();
        for (k, v) in &v.authenticity.individual_checks {
            individual.insert(k.clone(), *v);
        }

        DocumentValidationResult {
            is_valid: v.is_valid,
            is_tampered: v.is_tampered,
            authenticity: Some(AuthenticityScore {
                overall_score: v.authenticity.overall_score,
                tamper_detection_score: v.authenticity.tamper_detection_score,
                hologram_verification_score: v.authenticity.hologram_verification_score,
                font_analysis_score: v.authenticity.font_analysis_score,
                uv_check_score: v.authenticity.uv_check_score,
                digital_signature_score: v.authenticity.digital_signature_score,
                individual_checks: individual,
            }),
            flags: v.flags.clone(),
            warnings: v.warnings.clone(),
            validation_summary: v.validation_summary.clone(),
        }
    }
}

#[async_trait]
impl DocumentService for DocumentServiceImpl {
    async fn process_document(
        &self,
        req: Request<ProcessDocumentRequest>,
    ) -> Result<Response<ProcessDocumentResponse>, Status> {
        let start = std::time::Instant::now();
        let inner = req.into_inner();

        let result = self.processor.process_document(&inner.document_image).await
            .map_err(|e| Status::internal(format!("Processing failed: {}", e)))?;

        let proto_fields: Vec<ExtractedField> = result.data.fields.iter()
            .map(|f| Self::field_to_proto(f))
            .collect();

        let methods_used: Vec<i32> = result.methods_used.iter()
            .map(|m| match m {
                ExtractionMethod::Ocr => 1,
                ExtractionMethod::Mrz => 2,
                ExtractionMethod::Barcode => 3,
                ExtractionMethod::Nfc => 4,
                ExtractionMethod::MlOcr => 1,
            })
            .collect();

        let validation = result.validation.as_ref()
            .map(|v| Self::validation_to_proto(v));

        let resp = ProcessDocumentResponse {
            document_id: result.document_id.to_string(),
            status: DocumentStatus::Completed as i32,
            data: Some(DocumentData {
                document_id: result.data.document_id.to_string(),
                fields: proto_fields,
                document_type: result.data.document_type.unwrap_or_default(),
                country_code: result.data.country_code.unwrap_or_default(),
                mrz_line: result.data.mrz_line.unwrap_or_default(),
                encoded_face: result.data.encoded_face.unwrap_or_default(),
                raw_fields: result.data.raw_fields,
                metadata: Some(prost_types::Struct { fields: HashMap::new() }),
            }),
            validation,
            processing_time_ms: result.processing_time_ms,
            methods_used,
        };

        Ok(Response::new(resp))
    }

    async fn get_document_status(
        &self,
        req: Request<GetDocumentStatusRequest>,
    ) -> Result<Response<GetDocumentStatusResponse>, Status> {
        let _inner = req.into_inner();
        Err(Status::unimplemented("GetDocumentStatus requires persistent storage - not yet implemented"))
    }

    async fn extract_data(
        &self,
        req: Request<ExtractDataRequest>,
    ) -> Result<Response<ExtractDataResponse>, Status> {
        let inner = req.into_inner();

        let img = image::load_from_memory(&inner.document_image)
            .map_err(|e| Status::invalid_argument(format!("Invalid image: {}", e)))?;
        let (w, h) = img.dimensions();

        let doc_img = DocumentImage {
            id: uuid::Uuid::now_v7(),
            data: inner.document_image,
            format: ImageFormat::Png,
            width: w,
            height: h,
            color_space: models::ColorSpace::Rgb,
            dpi: None,
        };

        let mut extractor = crate::extraction::Extractor::new();
        extractor = extractor
            .with_engine(std::sync::Arc::new(crate::extraction::mrz::MrzEngine::new()))
            .with_engine(std::sync::Arc::new(crate::extraction::barcode::BarcodeEngine::new()));

        let fields = extractor.extract_all(&doc_img).await
            .map_err(|e| Status::internal(format!("Extraction failed: {}", e)))?;

        let proto_fields: Vec<ExtractedField> = fields.iter()
            .map(|f| Self::field_to_proto(f))
            .collect();

        let methods_used: Vec<i32> = fields.iter()
            .map(|f| match f.method {
                ExtractionMethod::Ocr => 1,
                ExtractionMethod::Mrz => 2,
                ExtractionMethod::Barcode => 3,
                ExtractionMethod::Nfc => 4,
                ExtractionMethod::MlOcr => 1,
            })
            .collect();

        Ok(Response::new(ExtractDataResponse {
            document_id: inner.document_id,
            data: Some(DocumentData {
                document_id: inner.document_id.clone(),
                fields: proto_fields,
                document_type: String::new(),
                country_code: String::new(),
                mrz_line: String::new(),
                encoded_face: Vec::new(),
                raw_fields: HashMap::new(),
                metadata: Some(prost_types::Struct { fields: HashMap::new() }),
            }),
            methods_used,
        }))
    }

    async fn validate_authenticity(
        &self,
        req: Request<ValidateAuthenticityRequest>,
    ) -> Result<Response<ValidateAuthenticityResponse>, Status> {
        let inner = req.into_inner();

        let data = if inner.deep_analysis {
            let tamper = crate::validation::tamper_detection::TamperDetectionEngine;
            let auth = crate::validation::authenticity::AuthenticityCheckEngine;
            let mut engine = crate::validation::ValidationEngine::new();
            engine = engine.with_validator(Box::new(tamper)).with_validator(Box::new(auth));
            engine.validate_all(&inner.document_image).await
                .map_err(|e| Status::internal(format!("Validation failed: {}", e)))?
        } else {
            let auth = crate::validation::authenticity::AuthenticityCheckEngine;
            let mut engine = crate::validation::ValidationEngine::new();
            engine = engine.with_validator(Box::new(auth));
            engine.validate_all(&inner.document_image).await
                .map_err(|e| Status::internal(format!("Validation failed: {}", e)))?
        };

        Ok(Response::new(ValidateAuthenticityResponse {
            document_id: inner.document_id,
            result: Some(Self::validation_to_proto(&data)),
        }))
    }
}

impl std::fmt::Debug for DocumentServiceImpl {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("DocumentServiceImpl")
            .field("config", &self.config)
            .finish()
    }
}
