use criterion::{black_box, criterion_group, criterion_main, Criterion};
use std::collections::HashMap;
use std::sync::Arc;
use tokio::runtime::Runtime;
use usora_risk_scoring_engine::config::ServiceConfig;
use usora_risk_scoring_engine::engine::cache::MultiLevelCache;
use usora_risk_scoring_engine::engine::orchestrator::ScoringOrchestrator;
use usora_risk_scoring_engine::ml::feature_store::{
    CompositeFeatureStore, NormalizationParams, PostgresFeatureStore, RedisFeatureStore,
};
use usora_risk_scoring_engine::ml::inference::InferenceService;
use usora_risk_scoring_engine::ml::model::ModelRegistry;
use usora_risk_scoring_engine::models::{
    ApplicantScoringRequest, FeatureValue, RiskLevel, RuleDefinition,
};
use usora_risk_scoring_engine::rules::evaluator::RuleEvaluator;
use usora_risk_scoring_engine::rules::registry::RuleRegistry;
use usora_risk_scoring_engine::rules::RuleEngineConfig;
use usora_risk_scoring_engine::scoring::calculator::ScoreCalculator;
use usora_risk_scoring_engine::scoring::engine::PipelineScoringEngine;

fn build_bench_config() -> ServiceConfig {
    ServiceConfig::default()
}

fn build_test_features() -> HashMap<String, FeatureValue> {
    let mut features = HashMap::new();
    features.insert(
        "device_fingerprint".into(),
        FeatureValue::String("bench-device-001".into()),
    );
    features.insert(
        "ip_reputation".into(),
        FeatureValue::Float(0.85),
    );
    features.insert(
        "behavioral_velocity".into(),
        FeatureValue::Integer(42),
    );
    features.insert(
        "historical_fraud_rate".into(),
        FeatureValue::Float(0.02),
    );
    features.insert(
        "geographic_risk".into(),
        FeatureValue::Float(0.3),
    );
    features.insert(
        "watchlist_hits".into(),
        FeatureValue::Integer(0),
    );
    features
}

fn bench_scoring_pipeline(c: &mut Criterion) {
    let rt = Runtime::new().unwrap();

    let config = Arc::new(build_bench_config());

    let cache = Arc::new(MultiLevelCache::new(
        &config.performance,
        config.redis.key_prefix.clone(),
    ));

    let rule_config = RuleEngineConfig::default();
    let rule_evaluator = Arc::new(RuleEvaluator::new(rule_config.clone()));
    let rule_registry = Arc::new(RuleRegistry::new(rule_config));

    let normalization_params = Arc::new(NormalizationParams {
        means: HashMap::new(),
        stds: HashMap::new(),
        feature_order: (0..config.models.applicant_risk.input_features)
            .map(|i| format!("feature_{}", i))
            .collect(),
    });

    let model_registry = Arc::new(ModelRegistry::new());
    let ml_service = Arc::new(InferenceService::new(
        model_registry,
        normalization_params.clone(),
        config.models.clone(),
    ));

    let calculator = ScoreCalculator::new(
        0.7,
        0.3,
        config.thresholds.clone(),
        config.explainability.max_features,
        config.explainability.min_feature_importance,
    );

    c.bench_function("score_calculation", |b| {
        b.iter(|| {
            black_box(calculator.calculate(
                &crate::models::EnsembleResult {
                    scores: vec![0.1, 0.2, 0.3, 0.4],
                    probabilities: vec![0.1, 0.2, 0.3, 0.4],
                    predicted_class: 2,
                    model_contributions: HashMap::new(),
                    feature_importance: {
                        let mut m = HashMap::new();
                        m.insert("feature_0".into(), 0.5);
                        m.insert("feature_1".into(), 0.3);
                        m.insert("feature_2".into(), 0.2);
                        m
                    },
                    shap_values: None,
                    latency_ms: 10.0,
                    model_id: "bench".into(),
                    model_version: "1.0".into(),
                },
                &[],
            ));
        })
    });

    c.bench_function("normalization", |b| {
        b.iter(|| {
            let features = build_test_features();
            black_box(normalization_params.normalize(
                &features,
            ));
        })
    });

    c.bench_function("explanation_generation", |b| {
        b.iter(|| {
            let ml_result = crate::models::EnsembleResult {
                scores: vec![0.1, 0.2, 0.3, 0.4],
                probabilities: vec![0.1, 0.2, 0.3, 0.4],
                predicted_class: 2,
                model_contributions: HashMap::new(),
                feature_importance: {
                    let mut m = HashMap::new();
                    m.insert("feature_0".into(), 0.5);
                    m.insert("feature_1".into(), 0.3);
                    m.insert("feature_2".into(), 0.2);
                    m
                },
                shap_values: None,
                latency_ms: 10.0,
                model_id: "bench".into(),
                model_version: "1.0".into(),
            };
            black_box(calculator.generate_explanation(
                &ml_result,
                &[],
                0.5,
                0.55,
            ));
        })
    });

    c.bench_function("cache_read_write", |b| {
        let features = build_test_features();
        b.to_async(&rt).iter(|| async {
            let request = ApplicantScoringRequest {
                applicant_id: "bench-applicant".into(),
                tenant_id: "bench-tenant".into(),
                transaction_id: None,
                features: features.clone(),
                include_explanation: false,
                request_id: uuid::Uuid::new_v4(),
            };

            let response = crate::models::ScoringResponse {
                score_id: uuid::Uuid::new_v4(),
                applicant_id: request.applicant_id.clone(),
                tenant_id: request.tenant_id.clone(),
                composite_score: 0.65,
                risk_level: RiskLevel::Medium,
                ml_score: Some(0.6),
                ml_risk_level: Some(RiskLevel::Medium),
                rule_score: Some(0.05),
                rule_risk_level: None,
                explanation: None,
                risk_factors: vec![],
                model_version: "bench".into(),
                rule_version: "1.0".into(),
                processing_time_ms: 15.0,
                computed_at: chrono::Utc::now(),
                cached: false,
            };

            cache
                .set(
                    &response.tenant_id,
                    &response.applicant_id,
                    &response.model_version,
                    response.clone(),
                )
                .await;
            let _cached = cache
                .get(
                    &response.tenant_id,
                    &response.applicant_id,
                    &response.model_version,
                )
                .await;
            black_box(_cached)
        })
    });
}

criterion_group!(benches, bench_scoring_pipeline);
criterion_main!(benches);
