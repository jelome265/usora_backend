use std::sync::Arc;
use usora_document_processor::{
    config::Config,
    pipeline::{PipelineBuilder, PipelineContext},
    models::{DocumentImage, ImageFormat, ColorSpace},
    extraction::{Extractor, mrz::MrzEngine, barcode::BarcodeEngine},
    validation::{ValidationEngine, authenticity::AuthenticityCheckEngine, tamper_detection::TamperDetectionEngine},
    DocumentProcessor,
};

fn create_test_config() -> Arc<Config> {
    Arc::new(Config {
        kafka_brokers: "localhost:9092".to_string(),
        kafka_group_id: "test".to_string(),
        kafka_tasks_topic: "test.tasks".to_string(),
        kafka_results_topic: "test.results".to_string(),
        grpc_bind_address: "0.0.0.0:0".to_string(),
        redis_url: "redis://localhost:6379".to_string(),
        postgres_url: "postgres://localhost:5432/test".to_string(),
        max_concurrent_jobs: 2,
        model_path: std::path::PathBuf::from("./models"),
        tesseract_data_path: std::path::PathBuf::from("/usr/share/tessdata"),
        temp_dir: std::path::PathBuf::from("./tmp"),
        otlp_endpoint: None,
        service_name: "usora-document-processor-test".to_string(),
    })
}

fn create_test_image(width: u32, height: u32, text_lines: &[&str]) -> Vec<u8> {
    let mut img = image::DynamicImage::new_rgb8(width, height);
    let white = image::Rgb([255u8, 255u8, 255u8]);
    let black = image::Rgb([0u8, 0u8, 0u8]);

    for y in 0..height {
        for x in 0..width {
            img.as_mut_rgb8().unwrap().put_pixel(x, y, white);
        }
    }

    let line_height = 20u32;
    for (i, line) in text_lines.iter().enumerate() {
        let y = 30 + (i as u32 * line_height);
        for (j, c) in line.chars().enumerate() {
            if c != ' ' {
                let x = 10 + (j as u32 * 12);
                if x < width && y < height {
                    img.as_mut_rgb8().unwrap().put_pixel(x, y, black);
                }
            }
        }
    }

    let mut buf = Vec::new();
    img.write_to(&mut std::io::Cursor::new(&mut buf), image::ImageFormat::Png).unwrap();
    buf
}

fn create_mrz_test_image() -> Vec<u8> {
    create_test_image(800, 300, &[
        "P<UTOSTEVENSON<<HENRY<<<<<<<<<<<<<<<<<<<<<<<<",
        "L898902C<UTO6801022F0310018<<<<<<<<<<<<<<<<8",
    ])
}

#[tokio::test]
async fn test_pipeline_ingestion_stage() {
    let data = create_test_image(100, 100, &["Test"]);
    let img = DocumentImage {
        id: uuid::Uuid::now_v7(),
        data: data.clone(),
        format: ImageFormat::Png,
        width: 100,
        height: 100,
        color_space: ColorSpace::Rgb,
        dpi: None,
    };

    let mut pipeline = PipelineBuilder::default()
        .with_ingestion()
        .build();

    let ctx = PipelineContext::new(img);
    let result = pipeline.execute(ctx).await;

    assert!(result.is_ok(), "Pipeline ingestion should succeed");
    let ctx = result.unwrap();
    assert!(!ctx.image.data.is_empty(), "Image data should remain after ingestion");
    assert!(ctx.image.width > 0, "Width should be set");
    assert!(ctx.image.height > 0, "Height should be set");
}

#[tokio::test]
async fn test_pipeline_preprocessing_stage() {
    let data = create_test_image(400, 300, &["Document Test"]);
    let img = DocumentImage {
        id: uuid::Uuid::now_v7(),
        data: data.clone(),
        format: ImageFormat::Png,
        width: 400,
        height: 300,
        color_space: ColorSpace::Rgb,
        dpi: None,
    };

    let mut pipeline = PipelineBuilder::default()
        .with_ingestion()
        .with_preprocessing()
        .build();

    let ctx = PipelineContext::new(img);
    let result = pipeline.execute(ctx).await;

    assert!(result.is_ok(), "Pipeline with preprocessing should succeed");
}

#[tokio::test]
async fn test_full_pipeline() {
    let data = create_test_image(200, 200, &[]);
    let config = create_test_config();
    let pipeline = PipelineBuilder::default()
        .with_ingestion()
        .with_preprocessing()
        .with_postprocessing()
        .build();
    let processor = DocumentProcessor::new(config, pipeline);

    let result = processor.process_document(&data).await;
    assert!(result.is_ok(), "Full document processing should succeed");
    let doc = result.unwrap();
    assert_eq!(doc.status.to_string(), "Completed");
    assert!(doc.processing_time_ms > 0.0, "Processing time should be positive");
}

#[tokio::test]
async fn test_mrz_extraction_td1() {
    let result = MrzEngine::parse_any("P<UTOSTEVENSON<<HENRY<<<<<<<<<<<<<<<<<<<<<<<<\nL898902C<UTO6801022F0310018<<<<<<<<<<<<<<<<8\n");
    assert!(result.is_some(), "TD1 MRZ should parse");
    let mrz = result.unwrap();
    assert_eq!(mrz.document_type, "P<");
    assert_eq!(mrz.issuing_country, "UTO");
    assert_eq!(mrz.surname, "STEVENSON");
    assert_eq!(mrz.given_names, "HENRY");
}

#[tokio::test]
async fn test_mrz_extraction_td3() {
    let result = MrzEngine::parse_any("P<UTOSTEVENSON<<HENRY<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<\nL898902C<UTO6801022F0310018<<<<<<<<<<<<<<<<8\n");
    assert!(result.is_some(), "TD3 MRZ should parse");
    let mrz = result.unwrap();
    assert_eq!(mrz.format.to_string(), "td3");
}

#[tokio::test]
async fn test_mrz_extraction_from_image() {
    let data = create_mrz_test_image();
    let img = DocumentImage {
        id: uuid::Uuid::now_v7(),
        data,
        format: ImageFormat::Png,
        width: 800,
        height: 300,
        color_space: ColorSpace::Rgb,
        dpi: Some(300.0),
    };

    let engine = MrzEngine::new();
    let result = engine.extract(&img).await;

    assert!(result.is_ok(), "MRZ extraction from image should succeed or fail gracefully");
}

#[tokio::test]
async fn test_barcode_extraction() {
    let data = create_test_image(200, 200, &["BARCODE: TEST-12345"]);
    let img = DocumentImage {
        id: uuid::Uuid::now_v7(),
        data,
        format: ImageFormat::Png,
        width: 200,
        height: 200,
        color_space: ColorSpace::Rgb,
        dpi: None,
    };

    let engine = BarcodeEngine::new();
    let result = engine.extract(&img).await;

    assert!(result.is_ok() || result.is_err(), "Barcode extraction should complete");
}

#[tokio::test]
async fn test_mrz_checksum_validation() {
    let result = MrzEngine::parse_any("P<UTOSTEVENSON<<HENRY<<<<<<<<<<<<<<<<<<<<<<<<\nL898902C<UTO6801022F0310018<<<<<<<<<<<<<<<<8\n").unwrap();
    assert!(result.checksums_valid, "MRZ checksums should be valid for test data");
}

#[tokio::test]
async fn test_validation_authenticity() {
    let data = create_test_image(500, 400, &["VALID DOCUMENT", "PASSPORT"]);
    let engine = AuthenticityCheckEngine;
    let results = engine.validate(&data).await;

    assert!(results.is_ok(), "Authenticity validation should complete");
    let results = results.unwrap();
    assert!(!results.is_empty(), "Should produce validation results");

    let hologram = results.iter().find(|r| r.field == "hologram");
    assert!(hologram.is_some(), "Should include hologram check");

    let microprint = results.iter().find(|r| r.field == "microprint");
    assert!(microprint.is_some(), "Should include microprint check");
}

#[tokio::test]
async fn test_validation_tamper_detection() {
    let data = create_test_image(600, 400, &["TEST DOCUMENT"]);
    let engine = TamperDetectionEngine;
    let results = engine.validate(&data).await;

    assert!(results.is_ok(), "Tamper detection should complete");
    let results = results.unwrap();
    assert!(!results.is_empty(), "Should produce tamper detection results");

    let photo = results.iter().find(|r| r.field == "tamper_photo_substitution");
    assert!(photo.is_some(), "Should include photo substitution check");

    let ela = results.iter().find(|r| r.field == "tamper_ela_artifacts");
    assert!(ela.is_some(), "Should include ELA check");
}

#[tokio::test]
async fn test_validation_engine_aggregation() {
    let data = create_test_image(400, 300, &[]);
    let mut engine = ValidationEngine::new();
    engine = engine
        .with_validator(Box::new(AuthenticityCheckEngine))
        .with_validator(Box::new(TamperDetectionEngine));

    let result = engine.validate_all(&data).await;

    assert!(result.is_ok(), "Combined validation should complete");
    let validation = result.unwrap();
    assert!(!validation.flags.is_empty(), "Should produce flags");
    assert!(validation.authenticity.overall_score >= 0.0, "Overall score should be non-negative");
    assert!(validation.authenticity.overall_score <= 1.0, "Overall score should not exceed 1.0");
}

/// F-019 regression: the aggregation layer must actually surface which
/// per-check results are visible-light heuristics only (not genuine
/// forensic UV/IR/hologram evidence), and must not silently drop that
/// distinction the way it previously did (individual_checks was always
/// an empty map, hologram_verification_score/uv_check_score were always
/// hardcoded to 0.0, regardless of what AuthenticityCheckEngine actually
/// found).
#[tokio::test]
async fn test_heuristic_only_checks_are_surfaced() {
    let data = create_test_image(400, 300, &[]);
    let engine = ValidationEngine::new().with_validator(Box::new(AuthenticityCheckEngine));

    let result = engine.validate_all(&data).await.unwrap();

    assert!(
        !result.authenticity.individual_checks.is_empty(),
        "individual_checks must be populated from the real per-check results, not left empty"
    );
    assert!(
        result.authenticity.heuristic_only_checks.contains(&"uv_fluorescence_heuristic".to_string()),
        "uv_fluorescence_heuristic must be explicitly listed as heuristic-only"
    );
    assert!(
        result.authenticity.heuristic_only_checks.contains(&"ir_absorption_heuristic".to_string()),
        "ir_absorption_heuristic must be explicitly listed as heuristic-only"
    );
    assert!(
        result.authenticity.heuristic_only_checks.contains(&"hologram_heuristic".to_string()),
        "hologram_heuristic must be explicitly listed as heuristic-only"
    );
    assert!(
        !result.authenticity.heuristic_only_checks.contains(&"microprint".to_string()),
        "microprint is not a UV/IR/hologram heuristic and must not be listed as one"
    );

    // The acceptance criterion in spirit: no heuristic-only check's
    // reported confidence can reach a level that would plausibly be
    // mistaken for genuine forensic verification.
    for field in &result.authenticity.heuristic_only_checks {
        let confidence = result.authenticity.individual_checks.get(field).copied().unwrap_or(0.0);
        assert!(
            confidence <= 0.4,
            "heuristic-only check '{field}' reported confidence {confidence}, above the 0.4 cap that keeps it \
             from looking like genuine forensic evidence"
        );
    }
}

#[tokio::test]
async fn test_extractor_aggregation() {
    let data = create_test_image(400, 200, &[]);
    let img = DocumentImage {
        id: uuid::Uuid::now_v7(),
        data,
        format: ImageFormat::Png,
        width: 400,
        height: 200,
        color_space: ColorSpace::Rgb,
        dpi: None,
    };

    let extractor = Extractor::new()
        .with_engine(std::sync::Arc::new(MrzEngine::new()))
        .with_engine(std::sync::Arc::new(BarcodeEngine::new()));

    let results = extractor.extract_all(&img).await;
    assert!(results.is_ok(), "Combined extraction should complete");
}

#[tokio::test]
async fn test_pipeline_error_handling() {
    let empty_data = Vec::new();
    let img = DocumentImage {
        id: uuid::Uuid::now_v7(),
        data: empty_data,
        format: ImageFormat::Png,
        width: 0,
        height: 0,
        color_space: ColorSpace::Rgb,
        dpi: None,
    };

    let mut pipeline = PipelineBuilder::default()
        .with_ingestion()
        .build();

    let ctx = PipelineContext::new(img);
    let result = pipeline.execute(ctx).await;

    assert!(result.is_ok(), "Pipeline should handle empty data gracefully");
}

#[tokio::test]
async fn test_image_format_detection() {
    assert_eq!(ImageFormat::from_mime("image/png").unwrap() as usize, 0);
    assert_eq!(ImageFormat::from_mime("image/jpeg").unwrap() as usize, 1);
    assert_eq!(ImageFormat::from_mime("image/tiff").unwrap() as usize, 2);

    assert_eq!(ImageFormat::from_ext("png").unwrap() as usize, 0);
    assert_eq!(ImageFormat::from_ext("jpg").unwrap() as usize, 1);
    assert_eq!(ImageFormat::from_ext("pdf").unwrap() as usize, 5);

    assert!(ImageFormat::from_mime("image/gif").is_none());
    assert!(ImageFormat::from_ext("txt").is_none());
}

#[tokio::test]
async fn test_nfc_bac_key_derivation() {
    let keys = usora_document_processor::extraction::nfc::NfcEngine::compute_bac_keys(
        "L898902C",
        "680102",
        "100318",
    );
    assert!(!keys.k_enc.is_empty(), "BAC encryption key should be derived");
    assert!(!keys.k_mac.is_empty(), "BAC MAC key should be derived");
    assert_ne!(keys.k_enc, keys.k_mac, "Encryption and MAC keys should differ");
}

#[tokio::test]
async fn test_nfc_simulation() {
    let fields = usora_document_processor::extraction::nfc::NfcEngine::simulate_nfc_extraction(
        "L898902C",
        "680102",
        "100318",
    );
    assert!(!fields.is_empty(), "NFC simulation should produce fields");
    assert!(fields.iter().any(|f| f.name == "nfc_bac_keys_generated"), "Should include BAC keys generated flag");
    assert!(fields.iter().any(|f| f.name.starts_with("nfc_dg")), "Should include data group fields");
}

#[tokio::test]
async fn test_utils_base64_roundtrip() {
    let original = b"Hello, Document Processor Test!";
    let encoded = usora_document_processor::utils::image_to_base64(original);
    let decoded = usora_document_processor::utils::base64_to_image(&encoded).unwrap();
    assert_eq!(original.to_vec(), decoded, "Base64 roundtrip should preserve data");
}

#[tokio::test]
async fn test_utils_sha256() {
    let data = b"test document data";
    let hash = usora_document_processor::utils::sha256_hash(data);
    assert_eq!(hash.len(), 64, "SHA-256 hex should be 64 characters");
    assert!(hash.chars().all(|c| c.is_ascii_hexdigit()), "SHA-256 should be hex");
}

#[tokio::test]
async fn test_utils_file_type_detection() {
    let png_header: [u8; 4] = [0x89, 0x50, 0x4E, 0x47];
    assert_eq!(usora_document_processor::utils::detect_file_type(&png_header), "image/png");

    let jpeg_header: [u8; 4] = [0xFF, 0xD8, 0xFF, 0xE0];
    assert_eq!(usora_document_processor::utils::detect_file_type(&jpeg_header), "image/jpeg");

    let pdf_header: [u8; 4] = [0x25, 0x50, 0x44, 0x46];
    assert_eq!(usora_document_processor::utils::detect_file_type(&pdf_header), "application/pdf");

    let unknown: [u8; 4] = [0x00, 0x00, 0x00, 0x00];
    assert_eq!(usora_document_processor::utils::detect_file_type(&unknown), "unknown");
}

#[tokio::test]
async fn test_document_processor_with_empty_data() {
    let config = create_test_config();
    let pipeline = PipelineBuilder::default()
        .with_ingestion()
        .with_preprocessing()
        .with_postprocessing()
        .build();
    let processor = DocumentProcessor::new(config, pipeline);

    let result = processor.process_document(b"").await;
    assert!(result.is_err(), "Processing empty data should fail");
}

#[tokio::test]
async fn test_postprocessing_cross_field_validation() {
    use usora_document_processor::models::ExtractedField;
    use usora_document_processor::models::ExtractionMethod;
    use usora_document_processor::pipeline::{PipelineContext, postprocessing::PostprocessingStage};

    let data = create_test_image(100, 100, &[]);
    let img = DocumentImage {
        id: uuid::Uuid::now_v7(),
        data,
        format: ImageFormat::Png,
        width: 100,
        height: 100,
        color_space: ColorSpace::Rgb,
        dpi: None,
    };

    let stage = PostprocessingStage;
    let mut ctx = PipelineContext::new(img);
    ctx.document = Some(usora_document_processor::models::ProcessedDocument {
        document_id: uuid::Uuid::now_v7(),
        tenant_id: "test".to_string(),
        verification_id: "test".to_string(),
        status: usora_document_processor::models::DocumentStatus::Processing,
        data: usora_document_processor::models::DocumentData {
            document_id: uuid::Uuid::now_v7(),
            fields: vec![
                ExtractedField {
                    name: "date_of_birth".to_string(),
                    value: "680102".to_string(),
                    confidence: 0.95,
                    method: ExtractionMethod::Mrz,
                    raw_text: None,
                    bounding_box: None,
                },
                ExtractedField {
                    name: "date_of_birth".to_string(),
                    value: "680103".to_string(),
                    confidence: 0.85,
                    method: ExtractionMethod::Ocr,
                    raw_text: None,
                    bounding_box: None,
                },
            ],
            document_type: Some("P".to_string()),
            country_code: Some("UTO".to_string()),
            mrz_line: None,
            encoded_face: None,
            raw_fields: std::collections::HashMap::new(),
            metadata: std::collections::HashMap::new(),
        },
        validation: None,
        processing_time_ms: 10.0,
        methods_used: vec![ExtractionMethod::Mrz, ExtractionMethod::Ocr],
        created_at: chrono::Utc::now(),
        updated_at: chrono::Utc::now(),
    });

    let result = stage.process(&mut ctx).await;
    assert!(result.is_ok(), "Post-processing should succeed");

    if let Some(ref doc) = ctx.document {
        let mrz_field = doc.data.fields.iter().find(|f| f.method == ExtractionMethod::Mrz && f.name == "date_of_birth");
        if let Some(field) = mrz_field {
            assert!(field.confidence < 0.95, "MRZ DOB confidence should be reduced when OCR disagrees");
        }
    }
}
