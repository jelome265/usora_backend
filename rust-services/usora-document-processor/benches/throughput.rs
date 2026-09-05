use criterion::{black_box, criterion_group, criterion_main, BatchSize, Criterion};
use std::sync::Arc;
use usora_document_processor::{
    config::Config,
    models::{ColorSpace, DocumentImage, ImageFormat},
    pipeline::{PipelineBuilder, PipelineContext, ProcessingPipeline},
    DocumentProcessor,
};

fn mock_document_data(size_kb: u32) -> Vec<u8> {
    let width = 200u32;
    let height = (size_kb * 1024) / (width * 3);
    let height = height.max(1);

    let mut img = image::DynamicImage::new_rgb8(width, height);
    for y in 0..height {
        for x in 0..width {
            let r = ((x as f32 / width as f32) * 255.0) as u8;
            let g = ((y as f32 / height as f32) * 255.0) as u8;
            let b = 128u8;
            img.as_mut_rgb8()
                .unwrap()
                .put_pixel(x, y, image::Rgb([r, g, b]));
        }
    }

    let mut buf = Vec::new();
    img.write_to(
        &mut std::io::Cursor::new(&mut buf),
        image::ImageFormat::Jpeg,
    )
    .unwrap();
    buf
}

fn create_config() -> Arc<Config> {
    Arc::new(Config {
        kafka_brokers: "localhost:9092".to_string(),
        kafka_group_id: "bench".to_string(),
        kafka_tasks_topic: "bench".to_string(),
        kafka_results_topic: "bench".to_string(),
        grpc_bind_address: "0.0.0.0:0".to_string(),
        redis_url: "redis://localhost:6379".to_string(),
        postgres_url: "postgres://localhost:5432/bench".to_string(),
        max_concurrent_jobs: 4,
        model_path: std::path::PathBuf::from("./models"),
        tesseract_data_path: std::path::PathBuf::from("/usr/share/tessdata"),
        temp_dir: std::path::PathBuf::from("./tmp"),
        otlp_endpoint: None,
        service_name: "bench".to_string(),
    })
}

fn bench_pipeline_execution(c: &mut Criterion) {
    let config = create_config();
    let pipeline = PipelineBuilder::default()
        .with_ingestion()
        .with_preprocessing()
        .with_postprocessing()
        .build();

    let mut group = c.benchmark_group("pipeline");
    group.sample_size(30);

    group.bench_function("pipeline_100kb_jpeg", |b| {
        let data = mock_document_data(100);
        b.iter_batched(
            || {
                let img = DocumentImage {
                    id: uuid::Uuid::now_v7(),
                    data: data.clone(),
                    format: ImageFormat::Jpeg,
                    width: 200,
                    height: 174,
                    color_space: ColorSpace::Rgb,
                    dpi: None,
                };
                PipelineContext::new(img)
            },
            |ctx| {
                let rt = tokio::runtime::Runtime::new().unwrap();
                rt.block_on(async {
                    let result = pipeline.execute(ctx).await.unwrap();
                    black_box(result);
                });
            },
            BatchSize::SmallInput,
        );
    });

    group.bench_function("pipeline_500kb_jpeg", |b| {
        let data = mock_document_data(500);
        b.iter_batched(
            || {
                let img = DocumentImage {
                    id: uuid::Uuid::now_v7(),
                    data: data.clone(),
                    format: ImageFormat::Jpeg,
                    width: 200,
                    height: 873,
                    color_space: ColorSpace::Rgb,
                    dpi: None,
                };
                PipelineContext::new(img)
            },
            |ctx| {
                let rt = tokio::runtime::Runtime::new().unwrap();
                rt.block_on(async {
                    let result = pipeline.execute(ctx).await.unwrap();
                    black_box(result);
                });
            },
            BatchSize::SmallInput,
        );
    });

    group.finish();
}

fn bench_full_document_processing(c: &mut Criterion) {
    let config = create_config();
    let pipeline = PipelineBuilder::default()
        .with_ingestion()
        .with_preprocessing()
        .with_postprocessing()
        .build();
    let processor = DocumentProcessor::new(config, pipeline);

    let mut group = c.benchmark_group("full_processing");
    group.sample_size(10);

    group.bench_function("full_100kb_jpeg", |b| {
        let data = mock_document_data(100);
        b.to_async(tokio::runtime::Runtime::new().unwrap())
            .iter(|| async {
                let result = processor.process_document(&data).await.unwrap();
                black_box(result);
            });
    });

    group.finish();
}

fn bench_extraction_engines(c: &mut Criterion) {
    let config = create_config();
    let data = mock_document_data(100);
    let img = DocumentImage {
        id: uuid::Uuid::now_v7(),
        data: data.clone(),
        format: ImageFormat::Jpeg,
        width: 200,
        height: 174,
        color_space: ColorSpace::Rgb,
        dpi: None,
    };

    let mut group = c.benchmark_group("extraction");
    group.sample_size(10);

    let mrz_engine = usora_document_processor::extraction::mrz::MrzEngine::new();
    group.bench_function("mrz_extraction", |b| {
        b.to_async(tokio::runtime::Runtime::new().unwrap())
            .iter(|| async {
                let result = mrz_engine.extract(&img).await.unwrap_or_default();
                black_box(result);
            });
    });

    group.finish();
}

criterion_group!(
    benches,
    bench_pipeline_execution,
    bench_full_document_processing,
    bench_extraction_engines,
);

criterion_main!(benches);
