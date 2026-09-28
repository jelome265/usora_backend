use criterion::{black_box, criterion_group, criterion_main, Criterion};
use image::{DynamicImage, RgbImage};
use std::sync::Arc;

use usora_face_matching_engine::detection::quality_check::QualityChecker;
use usora_face_matching_engine::detection::{DetectedFace, FaceDetector};
use usora_face_matching_engine::matching::one_to_one::CosineMatcher;
use usora_face_matching_engine::utils::{self, BBox};

struct DummyDetector;

#[async_trait::async_trait]
impl FaceDetector for DummyDetector {
    async fn detect_faces(
        &self,
        _image: &DynamicImage,
    ) -> anyhow::Result<Vec<DetectedFace>> {
        Ok(vec![DetectedFace {
            bbox: BBox { x1: 10.0, y1: 10.0, x2: 100.0, y2: 100.0 },
            landmarks: vec![
                [30.0, 40.0], [70.0, 40.0], [50.0, 60.0],
                [35.0, 75.0], [65.0, 75.0],
            ],
            confidence: 0.95,
            quality_score: None,
            face_angle: 0.0,
            rotation_angle: 0.0,
        }])
    }

    async fn detect_single_face(
        &self,
        _image: &DynamicImage,
    ) -> anyhow::Result<Option<DetectedFace>> {
        Ok(Some(DetectedFace {
            bbox: BBox { x1: 10.0, y1: 10.0, x2: 100.0, y2: 100.0 },
            landmarks: vec![
                [30.0, 40.0], [70.0, 40.0], [50.0, 60.0],
                [35.0, 75.0], [65.0, 75.0],
            ],
            confidence: 0.95,
            quality_score: None,
            face_angle: 0.0,
            rotation_angle: 0.0,
        }))
    }

    fn min_face_size(&self) -> u32 { 40 }
    fn confidence_threshold(&self) -> f32 { 0.7 }
}

fn create_test_image(width: u32, height: u32) -> DynamicImage {
    DynamicImage::ImageRgb8(RgbImage::new(width, height))
}

fn bench_quality_check(c: &mut Criterion) {
    let checker = QualityChecker::default();
    let image = create_test_image(640, 480);
    let face = DetectedFace {
        bbox: BBox { x1: 200.0, y1: 150.0, x2: 440.0, y2: 330.0 },
        landmarks: vec![
            [270.0, 210.0], [370.0, 210.0], [320.0, 250.0],
            [280.0, 290.0], [360.0, 290.0],
        ],
        confidence: 0.95,
        quality_score: None,
        face_angle: 0.0,
        rotation_angle: 0.0,
    };

    c.bench_function("quality_check_full", |b| {
        b.iter(|| {
            checker.check_quality(black_box(&image), black_box(&face)).unwrap()
        })
    });
}

fn bench_cosine_similarity(c: &mut Criterion) {
    let dim = 512;
    let v1: Vec<f32> = (0..dim).map(|i| (i as f32) / dim as f32).collect();
    let v2: Vec<f32> = (0..dim).map(|i| 1.0 - (i as f32) / dim as f32).collect();

    c.bench_function("cosine_similarity_512", |b| {
        b.iter(|| {
            utils::cosine_similarity(black_box(&v1), black_box(&v2))
        })
    });
}

fn bench_face_detection_pipeline(c: &mut Criterion) {
    let detector = Arc::new(DummyDetector);
    let image = create_test_image(640, 480);

    let mut group = c.benchmark_group("detection_pipeline");
    group.sample_size(10);

    group.bench_function("detect_faces_dummy", |b| {
        b.iter(|| {
            let rt = tokio::runtime::Runtime::new().unwrap();
            rt.block_on(async {
                detector.detect_faces(black_box(&image)).await.unwrap()
            })
        })
    });

    group.finish();
}

fn bench_preprocess_for_embedding(c: &mut Criterion) {
    let image = create_test_image(112, 112);

    c.bench_function("preprocess_for_embedding", |b| {
        b.iter(|| {
            utils::preprocess_for_embedding(black_box(&image)).unwrap()
        })
    });
}

fn bench_l2_normalization(c: &mut Criterion) {
    let dim = 512;
    let mut v: Vec<f32> = (0..dim).map(|i| (i as f32) / dim as f32).collect();

    c.bench_function("l2_normalize_512", |b| {
        b.iter(|| {
            utils::normalize_l2(black_box(&mut v))
        })
    });
}

fn bench_laplacian_variance(c: &mut Criterion) {
    let image = create_test_image(200, 200);

    c.bench_function("laplacian_variance_200x200", |b| {
        b.iter(|| {
            utils::compute_laplacian_variance(black_box(&image))
        })
    });
}

fn bench_euclidean_distance(c: &mut Criterion) {
    let dim = 512;
    let v1: Vec<f32> = (0..dim).map(|i| (i as f32) / dim as f32).collect();
    let v2: Vec<f32> = (0..dim).map(|i| 1.0 - (i as f32) / dim as f32).collect();

    c.bench_function("euclidean_distance_512", |b| {
        b.iter(|| {
            utils::euclidean_distance(black_box(&v1), black_box(&v2))
        })
    });
}

criterion_group!(
    benches,
    bench_quality_check,
    bench_cosine_similarity,
    bench_face_detection_pipeline,
    bench_preprocess_for_embedding,
    bench_l2_normalization,
    bench_laplacian_variance,
    bench_euclidean_distance,
);

criterion_main!(benches);
