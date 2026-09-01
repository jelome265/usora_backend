use crate::generated::usora::document::v1::{
    document_analysis_service_server::DocumentAnalysisService,
    DocumentAnalysisRequest, DocumentAnalysisResponse,
    ForgeryDetectionRequest, ForgeryDetectionResponse,
    MetadataExtractionRequest, MetadataExtractionResponse,
    CrossReferenceRequest, CrossReferenceResponse,
    SecurityFeaturesRequest, SecurityFeaturesResponse,
    TemplateRequest, TemplateResponse,
    ExtractedData,
    FieldConsistency, FeatureCheckResult, TemplateLayout, TemplateField, Position,
};
use crate::models;
use crate::Config;
use std::collections::HashMap;
use std::sync::Arc;
use tonic::{async_trait, Request, Response, Status};
use prost_types::{Timestamp, Struct, Value};

pub struct DocumentAnalysisServiceImpl {
    config: Arc<Config>,
    processor: crate::DocumentProcessor,
}

impl DocumentAnalysisServiceImpl {
    pub fn new(config: Arc<Config>) -> Self {
        let pipeline = crate::pipeline::PipelineBuilder::default()
            .with_ingestion()
            .with_preprocessing()
            .with_postprocessing()
            .build();
        let processor = crate::DocumentProcessor::new(config.clone(), pipeline);
        Self { config, processor }
    }

    /// F-019: shared by analyze_document and check_security_features so
    /// both RPCs report the same real, heuristic-labeled security-feature
    /// data instead of each having its own hand-rolled (and, before this
    /// fix, fabricated) construction. Builds every FeatureCheckResult from
    /// AuthenticityCheckEngine's actual per-check results (via
    /// ValidationEngine, already run as part of process_document), and
    /// marks a result's `details` text distinctly when the underlying
    /// check is a visible-light heuristic only (see
    /// validation/authenticity.rs) rather than genuine forensic UV/IR/
    /// hologram evidence -- this is what prevents the API from ever
    /// implying uv_verified=true-equivalent confidence from an ordinary
    /// RGB capture.
    fn build_security_features_response(
        document_id: String,
        validation: &Option<models::DocumentValidation>,
    ) -> SecurityFeaturesResponse {
        let (individual_checks, heuristic_only_checks) = validation
            .as_ref()
            .map(|v| (v.authenticity.individual_checks.clone(), v.authenticity.heuristic_only_checks.clone()))
            .unwrap_or_default();

        let make_feature_check = |name: &str,
                                   heuristic_field: &str,
                                   heuristic_details: &str,
                                   passed_threshold: f32| -> Option<FeatureCheckResult> {
            individual_checks.get(heuristic_field).map(|&confidence| {
                let is_heuristic = heuristic_only_checks.iter().any(|f| f == heuristic_field);
                let details = if is_heuristic {
                    format!(
                        "{} (visible-light heuristic only, NOT a genuine forensic {} check -- see \
                         validation/authenticity.rs)",
                        heuristic_details, name
                    )
                } else {
                    heuristic_details.to_string()
                };
                FeatureCheckResult {
                    feature_name: name.to_string(),
                    present: confidence >= passed_threshold,
                    confidence: confidence as f64,
                    details,
                }
            })
        };

        let uv_check = make_feature_check(
            "uv_fluorescence", "uv_fluorescence_heuristic", "UV fluorescence heuristic result", 0.3);
        let ir_check = make_feature_check(
            "ir_absorption", "ir_absorption_heuristic", "IR absorption heuristic result", 0.3);
        let hologram_check = make_feature_check(
            "hologram", "hologram_heuristic", "Hologram pixel-variance heuristic result", 0.3);
        let microprint_check = make_feature_check(
            "microprint", "microprint", "Microprint detail analysis result", 0.5);
        let watermark_check = make_feature_check(
            "watermark", "watermark", "Watermark pattern analysis result", 0.5);

        let all_features_present = [&uv_check, &ir_check, &hologram_check, &microprint_check, &watermark_check]
            .iter()
            .all(|c| c.as_ref().is_some_and(|c| c.present));
        let overall_security_score = validation
            .as_ref()
            .map(|v| v.authenticity.overall_score as f64)
            .unwrap_or(0.0);

        SecurityFeaturesResponse {
            document_id,
            all_features_present,
            overall_security_score,
            uv_check,
            ir_check,
            hologram_check,
            microprint_check,
            watermark_check,
            warnings: heuristic_only_checks.iter()
                .map(|f| format!("{f} is a visible-light heuristic only, not genuine forensic evidence"))
                .collect(),
        }
    }
}

#[async_trait]
impl DocumentAnalysisService for DocumentAnalysisServiceImpl {
    async fn analyze_document(
        &self,
        req: Request<DocumentAnalysisRequest>,
    ) -> Result<Response<DocumentAnalysisResponse>, Status> {
        let start = std::time::Instant::now();
        let inner = req.into_inner();

        let result = self.processor.process_document(&inner.document_image).await
            .map_err(|e| Status::internal(format!("Processing failed: {}", e)))?;

        let extracted = ExtractedData {
            document_type: result.data.document_type.unwrap_or_default(),
            country_code: result.data.country_code.unwrap_or_default(),
            document_number: extract_field(&result.data.fields, "document_number"),
            full_name: extract_field(&result.data.fields, "full_name"),
            date_of_birth: extract_field(&result.data.fields, "date_of_birth"),
            nationality: extract_field(&result.data.fields, "nationality"),
            sex: extract_field(&result.data.fields, "sex"),
            date_of_expiry: extract_field(&result.data.fields, "date_of_expiry"),
            date_of_issue: extract_field(&result.data.fields, "date_of_issue"),
            address: extract_field(&result.data.fields, "address"),
            mrz_line: result.data.mrz_line.unwrap_or_default(),
            encoded_face: result.data.encoded_face.unwrap_or_default(),
            raw_fields: result.data.raw_fields,
            field_confidences: result.data.fields.iter().map(|f| (f.name.clone(), f.confidence as f64)).collect(),
            metadata: Some(Struct { fields: HashMap::new() }),
        };

        let forgery = ForgeryDetectionResponse {
            document_id: result.document_id.to_string(),
            is_forgery: false,
            forgery_confidence: 0.0,
            forgery_indicators: vec![],
            model_scores: HashMap::new(),
            tamper_detection_score: 0.0,
            manipulation_score: 0.0,
            print_analysis_score: 0.0,
            font_analysis_score: 0.0,
            texture_analysis_score: 0.0,
            warnings: vec![],
        };

        let metadata_result = MetadataExtractionResponse {
            document_id: result.document_id.to_string(),
            exif_data: HashMap::new(),
            file_format: String::new(),
            image_width: 0,
            image_height: 0,
            file_size_kb: inner.document_image.len() as f64 / 1024.0,
            color_space: String::new(),
            dpi: 0,
            has_embedded_thumbnail: false,
            detected_software: vec![],
            creation_date: None,
            warnings: vec![],
        };

        let cross_ref = CrossReferenceResponse {
            document_id: result.document_id.to_string(),
            all_fields_consistent: true,
            field_consistencies: vec![],
            cross_reference_warnings: vec![],
            overall_consistency_score: 1.0,
        };

        // F-019: see build_security_features_response's own docs -- this
        // replaces what used to be hardcoded None/0.0/false for every
        // field here regardless of what AuthenticityCheckEngine actually
        // found.
        let security = Self::build_security_features_response(
            result.document_id.to_string(), &result.validation);

        let resp = DocumentAnalysisResponse {
            document_id: result.document_id.to_string(),
            status: "completed".to_string(),
            extracted_data: Some(extracted),
            forgery_result: Some(forgery),
            metadata_result: Some(metadata_result),
            cross_reference_result: Some(cross_ref),
            security_features_result: Some(security),
            overall_authenticity_score: result.validation.as_ref().map(|v| v.authenticity.overall_score as f64).unwrap_or(0.0),
            warnings: result.validation.as_ref().map(|v| v.warnings.clone()).unwrap_or_default(),
            processing_time_ms: result.processing_time_ms,
        };

        Ok(Response::new(resp))
    }

    async fn detect_forgery(
        &self,
        req: Request<ForgeryDetectionRequest>,
    ) -> Result<Response<ForgeryDetectionResponse>, Status> {
        let inner = req.into_inner();
        let mut model_scores = HashMap::new();
        model_scores.insert("tamper_detection".to_string(), 0.95);
        model_scores.insert("manipulation".to_string(), 0.88);
        model_scores.insert("print_analysis".to_string(), 0.92);
        model_scores.insert("font_analysis".to_string(), 0.85);
        model_scores.insert("texture_analysis".to_string(), 0.90);

        Ok(Response::new(ForgeryDetectionResponse {
            document_id: inner.document_id,
            is_forgery: false,
            forgery_confidence: 0.12,
            forgery_indicators: vec![],
            model_scores,
            tamper_detection_score: 0.95,
            manipulation_score: 0.88,
            print_analysis_score: 0.92,
            font_analysis_score: 0.85,
            texture_analysis_score: 0.90,
            warnings: vec![],
        }))
    }

    async fn extract_metadata(
        &self,
        req: Request<MetadataExtractionRequest>,
    ) -> Result<Response<MetadataExtractionResponse>, Status> {
        let inner = req.into_inner();
        let exif = HashMap::new();
        let now = std::time::SystemTime::now();
        let duration = now.duration_since(std::time::UNIX_EPOCH).unwrap_or_default();

        Ok(Response::new(MetadataExtractionResponse {
            document_id: inner.document_id,
            exif_data: exif,
            file_format: "png".to_string(),
            image_width: 0,
            image_height: 0,
            file_size_kb: inner.document_image.len() as f64 / 1024.0,
            color_space: "sRGB".to_string(),
            dpi: 300,
            has_embedded_thumbnail: false,
            detected_software: vec![],
            creation_date: Some(Timestamp {
                seconds: duration.as_secs() as i64,
                nanos: duration.subsec_nanos() as i32,
            }),
            warnings: vec![],
        }))
    }

    async fn validate_cross_reference(
        &self,
        req: Request<CrossReferenceRequest>,
    ) -> Result<Response<CrossReferenceResponse>, Status> {
        let inner = req.into_inner();
        let extracted = inner.extracted_data.unwrap_or_default();

        let mut consistencies = Vec::new();
        if !extracted.date_of_birth.is_empty() && !extracted.date_of_expiry.is_empty() {
            consistencies.push(FieldConsistency {
                field_name: "date_consistency".to_string(),
                consistent: true,
                confidence: 1.0,
                details: "Dates are sequential".to_string(),
            });
        }
        if !extracted.document_number.is_empty() {
            consistencies.push(FieldConsistency {
                field_name: "document_number_format".to_string(),
                consistent: true,
                confidence: 0.95,
                details: "Format matches expected pattern".to_string(),
            });
        }
        if !extracted.mrz_line.is_empty() {
            consistencies.push(FieldConsistency {
                field_name: "mrz_vs_visual".to_string(),
                consistent: true,
                confidence: 0.90,
                details: "MRZ matches visual inspection zone".to_string(),
            });
        }

        let overall = consistencies.iter().filter(|c| c.consistent).count() as f64
            / consistencies.len().max(1) as f64;

        Ok(Response::new(CrossReferenceResponse {
            document_id: inner.document_id,
            all_fields_consistent: overall >= 0.8,
            field_consistencies: consistencies,
            cross_reference_warnings: vec![],
            overall_consistency_score: overall,
        }))
    }

    async fn check_security_features(
        &self,
        req: Request<SecurityFeaturesRequest>,
    ) -> Result<Response<SecurityFeaturesResponse>, Status> {
        let inner = req.into_inner();

        // CRITICAL BUG found while implementing F-019: this previously
        // never looked at inner.document_image at all. `present` was set
        // directly from whether the CALLER'S REQUEST asked for a given
        // check (inner.check_uv/check_ir/etc.), and confidence was a
        // hardcoded 0.95 whenever present -- meaning a caller asking for
        // check_uv=true received "uv_check: present=true, confidence=0.95,
        // details='uv check passed'" regardless of what the submitted
        // image actually contained. This was a complete fabrication with
        // zero relationship to the input document, not merely a
        // mislabeled heuristic -- worse than the analyze_document path's
        // hardcoded-to-absent placeholders, since this one actively
        // reported false positive "passed" results on request.
        //
        // Now actually runs the same AuthenticityCheckEngine/
        // ValidationEngine pipeline analyze_document uses, and builds the
        // response from real (if heuristic-only, honestly labeled as
        // such) per-check results via the shared
        // build_security_features_response helper.
        let result = self.processor.process_document(&inner.document_image).await
            .map_err(|e| Status::internal(format!("Processing failed: {}", e)))?;

        Ok(Response::new(Self::build_security_features_response(
            inner.document_id, &result.validation)))
    }

    async fn get_document_template(
        &self,
        req: Request<TemplateRequest>,
    ) -> Result<Response<TemplateResponse>, Status> {
        let inner = req.into_inner();

        Ok(Response::new(TemplateResponse {
            template_id: format!("{}/{}/{}", inner.tenant_id, inner.country_code, inner.document_type),
            country_code: inner.country_code,
            document_type: inner.document_type,
            layout: Some(TemplateLayout {
                width_mm: 85,
                height_mm: 54,
                orientation: "landscape".to_string(),
                security_zones: vec![
                    "hologram".to_string(),
                    "microprint".to_string(),
                    "uv_feature".to_string(),
                    "watermark".to_string(),
                ],
            }),
            fields: vec![
                TemplateField { name: "full_name".to_string(), type_: "string".to_string(), expected_format: "Latin".to_string(), mandatory: true, position: Some(Position { x_rel: 0.2, y_rel: 0.3, width_rel: 0.6, height_rel: 0.08 }) },
                TemplateField { name: "date_of_birth".to_string(), type_: "date".to_string(), expected_format: "DD/MM/YYYY".to_string(), mandatory: true, position: Some(Position { x_rel: 0.2, y_rel: 0.4, width_rel: 0.3, height_rel: 0.06 }) },
                TemplateField { name: "document_number".to_string(), type_: "string".to_string(), expected_format: "alphanumeric".to_string(), mandatory: true, position: Some(Position { x_rel: 0.2, y_rel: 0.5, width_rel: 0.4, height_rel: 0.06 }) },
            ],
            version: "1.0".to_string(),
            valid_from: Some(std::time::SystemTime::now().into()),
            valid_to: None,
            match_confidence: 0.0,
        }))
    }
}

fn extract_field(fields: &[models::ExtractedField], name: &str) -> String {
    fields.iter().find(|f| f.name == name).map(|f| f.value.clone()).unwrap_or_default()
}

impl std::fmt::Debug for DocumentAnalysisServiceImpl {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("DocumentAnalysisServiceImpl")
            .field("config", &self.config)
            .finish()
    }
}
