pub mod config;
pub mod extraction;
pub mod ocr;
pub mod pipeline;
pub mod validation;
pub mod models;
pub mod grpc;
pub mod routes;
pub mod utils;

pub mod generated {
    pub mod usora {
        pub mod document {
            pub mod v1 {
                include!("generated/usora.document.v1.rs");
            }
        }
    }
}

pub use config::Config;
pub use models::{
    DocumentImage, DocumentData, ExtractedField, ProcessedDocument, ProcessingMetrics,
    ValidationResult, MrzResult, MrzFormat, AuthenticityScore, DocumentValidation,
    DocumentStatus, ImageFormat, ColorSpace, BoundingBox, ExtractionMethod,
};

pub struct DocumentProcessor {
    config: std::sync::Arc<Config>,
    pipeline: pipeline::ProcessingPipeline,
    extractor: extraction::Extractor,
    validation_engine: validation::ValidationEngine,
}

impl DocumentProcessor {
    pub fn new(config: std::sync::Arc<Config>, pipeline: pipeline::ProcessingPipeline) -> Self {
        let mut extractor = extraction::Extractor::new();
        extractor = extractor
            .with_engine(std::sync::Arc::new(extraction::mrz::MrzEngine::new()))
            .with_engine(std::sync::Arc::new(extraction::barcode::BarcodeEngine::new()))
            .with_engine(std::sync::Arc::new(extraction::nfc::NfcEngine::new()));

        let mut validation_engine = validation::ValidationEngine::new();
        validation_engine = validation_engine
            .with_validator(Box::new(validation::authenticity::AuthenticityCheckEngine))
            .with_validator(Box::new(validation::tamper_detection::TamperDetectionEngine));

        Self {
            config,
            pipeline,
            extractor,
            validation_engine,
        }
    }

    pub async fn process_document(
        &self,
        raw_data: &[u8],
    ) -> anyhow::Result<ProcessedDocument> {
        let start = std::time::Instant::now();
        let document_id = uuid::Uuid::now_v7();

        let img = DocumentImage {
            id: document_id,
            data: raw_data.to_vec(),
            format: ImageFormat::from_mime(utils::detect_file_type(raw_data))
                .unwrap_or(ImageFormat::Png),
            width: 0,
            height: 0,
            color_space: ColorSpace::Rgb,
            dpi: None,
        };

        let ctx = pipeline::PipelineContext::new(img);
        let processed_ctx = self.pipeline.execute(ctx).await?;

        let fields = self.extractor.extract_all(&processed_ctx.image).await?;

        let validation = self.validation_engine.validate_all(&processed_ctx.image.data).await?;

        let processing_time = utils::format_processing_time(start);

        let methods_used: Vec<ExtractionMethod> = fields
            .iter()
            .map(|f| f.method.clone())
            .collect::<std::collections::HashSet<_>>()
            .into_iter()
            .collect();

        let raw_fields: std::collections::HashMap<String, String> = fields
            .iter()
            .map(|f| (f.name.clone(), f.value.clone()))
            .collect();

        let doc = ProcessedDocument {
            document_id,
            tenant_id: String::new(),
            verification_id: String::new(),
            status: DocumentStatus::Completed,
            data: DocumentData {
                document_id,
                fields,
                document_type: None,
                country_code: None,
                mrz_line: None,
                encoded_face: None,
                raw_fields,
                metadata: std::collections::HashMap::new(),
            },
            validation: Some(validation),
            processing_time_ms: processing_time,
            methods_used,
            created_at: chrono::Utc::now(),
            updated_at: chrono::Utc::now(),
        };

        Ok(doc)
    }
}

impl Clone for DocumentProcessor {
    fn clone(&self) -> Self {
        Self {
            config: self.config.clone(),
            pipeline: pipeline::ProcessingPipeline::new(),
            extractor: extraction::Extractor::new(),
            validation_engine: validation::ValidationEngine::new(),
        }
    }
}
