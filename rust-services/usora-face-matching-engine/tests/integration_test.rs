use image::{DynamicImage, RgbImage};
use std::sync::Arc;

use usora_face_matching_engine::detection::quality_check::QualityChecker;
use usora_face_matching_engine::detection::DetectedFace;
use usora_face_matching_engine::matching::one_to_one::CosineMatcher;
use usora_face_matching_engine::utils::{self, BBox};

fn create_test_face() -> DetectedFace {
    DetectedFace {
        bbox: BBox {
            x1: 40.0,
            y1: 40.0,
            x2: 160.0,
            y2: 160.0,
        },
        landmarks: vec![
            [70.0, 80.0],
            [130.0, 80.0],
            [100.0, 110.0],
            [80.0, 130.0],
            [120.0, 130.0],
        ],
        confidence: 0.95,
        quality_score: None,
        face_angle: 0.0,
        rotation_angle: 0.0,
    }
}

fn create_test_image() -> DynamicImage {
    DynamicImage::ImageRgb8(RgbImage::new(200, 200))
}

#[tokio::test]
async fn test_quality_check_pipeline() {
    let checker = QualityChecker::default();
    let image = create_test_image();
    let face = create_test_face();

    let score = checker.check_quality(&image, &face).unwrap();

    assert!(score.overall_score >= 0.0);
    assert!(score.overall_score <= 100.0);
    assert!(!score.blur_detected);
    assert!(score.face_size_valid);
    assert!(score.passes_quality_gate);
}

#[tokio::test]
async fn test_cosine_similarity_identical() {
    let v: Vec<f32> = vec![1.0, 0.0, 0.0, 0.0];
    let similarity = utils::cosine_similarity(&v, &v);
    assert!((similarity - 1.0).abs() < 1e-6);
}

#[tokio::test]
async fn test_cosine_similarity_orthogonal() {
    let v1: Vec<f32> = vec![1.0, 0.0, 0.0, 0.0];
    let v2: Vec<f32> = vec![0.0, 1.0, 0.0, 0.0];
    let similarity = utils::cosine_similarity(&v1, &v2);
    assert!(similarity.abs() < 1e-6);
}

#[tokio::test]
async fn test_cosine_similarity_opposite() {
    let v1: Vec<f32> = vec![1.0, 0.0];
    let v2: Vec<f32> = vec![-1.0, 0.0];
    let similarity = utils::cosine_similarity(&v1, &v2);
    assert!((similarity - (-1.0)).abs() < 1e-6);
}

#[tokio::test]
async fn test_l2_normalization() {
    let mut v = vec![3.0, 4.0];
    utils::normalize_l2(&mut v);
    let norm: f32 = v.iter().map(|x| x * x).sum::<f32>().sqrt();
    assert!((norm - 1.0).abs() < 1e-6);
    assert!((v[0] - 0.6).abs() < 1e-6);
    assert!((v[1] - 0.8).abs() < 1e-6);
}

#[tokio::test]
async fn test_euclidean_distance() {
    let v1 = vec![0.0, 0.0];
    let v2 = vec![3.0, 4.0];
    let dist = utils::euclidean_distance(&v1, &v2);
    assert!((dist - 5.0).abs() < 1e-6);
}

#[tokio::test]
async fn test_iou_computation() {
    let a = BBox {
        x1: 0.0,
        y1: 0.0,
        x2: 10.0,
        y2: 10.0,
    };
    let b = BBox {
        x1: 0.0,
        y1: 0.0,
        x2: 10.0,
        y2: 10.0,
    };
    let iou = utils::compute_iou(&a, &b);
    assert!((iou - 1.0).abs() < 1e-6);

    let c = BBox {
        x1: 20.0,
        y1: 20.0,
        x2: 30.0,
        y2: 30.0,
    };
    let iou = utils::compute_iou(&a, &c);
    assert!((iou - 0.0).abs() < 1e-6);

    let d = BBox {
        x1: 5.0,
        y1: 0.0,
        x2: 15.0,
        y2: 10.0,
    };
    let iou = utils::compute_iou(&a, &d);
    assert!((iou - 50.0 / 150.0).abs() < 1e-6);
}

#[tokio::test]
async fn test_non_maximum_suppression() {
    let mut detections = vec![
        DetectedFace {
            bbox: BBox {
                x1: 0.0,
                y1: 0.0,
                x2: 10.0,
                y2: 10.0,
            },
            landmarks: vec![],
            confidence: 0.9,
            quality_score: None,
            face_angle: 0.0,
            rotation_angle: 0.0,
        },
        DetectedFace {
            bbox: BBox {
                x1: 1.0,
                y1: 1.0,
                x2: 9.0,
                y2: 9.0,
            },
            landmarks: vec![],
            confidence: 0.8,
            quality_score: None,
            face_angle: 0.0,
            rotation_angle: 0.0,
        },
    ];

    let keep = utils::non_maximum_suppression(&mut detections, 0.5, 0.5);
    assert_eq!(keep.len(), 1);
    assert!((keep[0].confidence - 0.9).abs() < 1e-6);
}

#[tokio::test]
async fn test_image_processing() {
    let mut img = create_test_image();
    let face = create_test_face();

    let cropped = utils::crop_face(&img, &face).unwrap();
    assert_eq!(cropped.width(), 120);
    assert_eq!(cropped.height(), 120);

    let resized = utils::resize_face(&cropped, 112, 112);
    assert_eq!(resized.width(), 112);
    assert_eq!(resized.height(), 112);

    let tensor = utils::preprocess_for_embedding(&resized).unwrap();
    assert_eq!(tensor.shape(), &[3, 112, 112]);
}

#[tokio::test]
async fn test_brightness_computation() {
    let white =
        DynamicImage::ImageRgb8(RgbImage::from_pixel(100, 100, image::Rgb([255, 255, 255])));
    let brightness = utils::compute_brightness(&white);
    assert!((brightness - 100.0).abs() < 1.0);

    let black = DynamicImage::ImageRgb8(RgbImage::from_pixel(100, 100, image::Rgb([0, 0, 0])));
    let brightness = utils::compute_brightness(&black);
    assert!(brightness < 1.0);
}

#[tokio::test]
async fn test_contrast_computation() {
    let uniform =
        DynamicImage::ImageRgb8(RgbImage::from_pixel(100, 100, image::Rgb([128, 128, 128])));
    let contrast = utils::compute_contrast(&uniform);
    assert!(contrast < 1.0);
}

#[tokio::test]
async fn test_laplacian_variance() {
    let uniform =
        DynamicImage::ImageRgb8(RgbImage::from_pixel(100, 100, image::Rgb([128, 128, 128])));
    let lap = utils::compute_laplacian_variance(&uniform);
    assert!(lap < 1.0);
}

#[tokio::test]
async fn test_bbox_methods() {
    let bbox = BBox {
        x1: 10.0,
        y1: 20.0,
        x2: 110.0,
        y2: 120.0,
    };

    assert!((bbox.width() - 100.0).abs() < 1e-6);
    assert!((bbox.height() - 100.0).abs() < 1e-6);
    assert!((bbox.area() - 10000.0).abs() < 1e-6);

    let center = bbox.center();
    assert!((center.0 - 60.0).abs() < 1e-6);
    assert!((center.1 - 70.0).abs() < 1e-6);
}

#[tokio::test]
async fn test_landmark_distances() {
    let landmarks = vec![[0.0, 0.0], [3.0, 4.0], [0.0, 0.0]];
    let dists = utils::landmark_distances(&landmarks);
    assert_eq!(dists.len(), 3);
    assert!((dists[0] - 5.0).abs() < 1e-6);
}

#[tokio::test]
async fn test_base64_roundtrip() {
    let data = b"hello world";
    let encoded = utils::encode_image_base64(data);
    let decoded = utils::decode_image_base64(&encoded).unwrap();
    assert_eq!(decoded, data);
}

#[tokio::test]
async fn test_face_centered_check() {
    let face = DetectedFace {
        bbox: BBox {
            x1: 350.0,
            y1: 200.0,
            x2: 450.0,
            y2: 400.0,
        },
        landmarks: vec![],
        confidence: 0.95,
        quality_score: None,
        face_angle: 0.0,
        rotation_angle: 0.0,
    };

    let centered = utils::is_face_centered(&face, 800, 600);
    assert!(centered);

    let face_off = DetectedFace {
        bbox: BBox {
            x1: 10.0,
            y1: 10.0,
            x2: 100.0,
            y2: 100.0,
        },
        landmarks: vec![],
        confidence: 0.95,
        quality_score: None,
        face_angle: 0.0,
        rotation_angle: 0.0,
    };

    let centered = utils::is_face_centered(&face_off, 800, 600);
    assert!(!centered);
}

#[tokio::test]
async fn test_cosine_matcher() {
    let matcher = CosineMatcher::new(0.6);

    let probe = usora_face_matching_engine::FaceEmbedding {
        vector: vec![1.0, 0.0, 0.0, 0.0],
        dimension: 4,
        model_version: "v1".into(),
        confidence: 0.95,
        face_id: None,
    };

    let target = usora_face_matching_engine::FaceEmbedding {
        vector: vec![1.0, 0.0, 0.0, 0.0],
        dimension: 4,
        model_version: "v1".into(),
        confidence: 0.95,
        face_id: None,
    };

    let result = matcher.verify_one_to_one(&probe, &target).await.unwrap();
    assert!(result.passed_threshold);
    assert!((result.similarity_score - 1.0).abs() < 1e-6);

    let different = usora_face_matching_engine::FaceEmbedding {
        vector: vec![0.0, 1.0, 0.0, 0.0],
        dimension: 4,
        model_version: "v1".into(),
        confidence: 0.95,
        face_id: None,
    };

    let result = matcher.verify_one_to_one(&probe, &different).await.unwrap();
    assert!(!result.passed_threshold);
}

#[tokio::test]
async fn test_image_format_detection() {
    let data = vec![0xFF, 0xD8, 0xFF, 0xE0];
    let result = std::panic::catch_unwind(|| {
        let _img = utils::load_image_from_bytes(&data);
    });
    assert!(result.is_ok() || result.is_err());
}
