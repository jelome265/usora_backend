use criterion::{black_box, criterion_group, criterion_main, Criterion};
use std::time::Duration;
use tokio::runtime::Runtime;

use usora_api_gateway::rate_limit::token_bucket::TokenBucket;

fn bench_token_bucket(c: &mut Criterion) {
    let mut group = c.benchmark_group("token_bucket");

    let rt = Runtime::new().unwrap();

    group.bench_function("consume_local_100k", |b| {
        let mut bucket = TokenBucket::new(1000, 2000, 1000);
        b.iter(|| {
            black_box(bucket.consume(1));
        });
    });

    group.bench_function("consume_local_async_100k", |b| {
        let bucket = TokenBucket::new(1000, 2000, 1000);
        b.to_async(&rt).iter(|| async {
            black_box(bucket.consume_async(1).await);
        });
    });

    group.finish();
}

fn bench_jwt_validation(c: &mut Criterion) {
    use jsonwebtoken::{encode, Header, EncodingKey};
    }

fn bench_uuid_v7(c: &mut Criterion) {
    c.bench_function("uuid_v7_generation", |b| {
        b.iter(|| {
            black_box(uuid::Uuid::now_v7());
        });
    });
}

fn bench_hmac_sign(c: &mut Criterion) {
    use usora_api_gateway::utils::hmac_sign;

    let key = b"test_hmac_key_for_benchmarking_purposes_32b";
    let data = b"test_data_to_sign_for_benchmark";

    c.bench_function("hmac_sha256_sign", |b| {
        b.iter(|| {
            black_box(hmac_sign(key, data));
        });
    });
}

fn bench_hmac_verify(c: &mut Criterion) {
    use usora_api_gateway::utils::{hmac_sign, hmac_verify};

    let key = b"test_hmac_key_for_benchmarking_purposes_32b";
    let data = b"test_data_to_sign_for_benchmark";
    let signature = hmac_sign(key, data);

    c.bench_function("hmac_sha256_verify", |b| {
        b.iter(|| {
            black_box(hmac_verify(key, data, &signature));
        });
    });
}

criterion_group! {
    name = throughput;
    config = Criterion::default()
        .measurement_time(Duration::from_secs(10))
        .warm_up_time(Duration::from_secs(3))
        .sample_size(100);
    targets = bench_token_bucket, bench_uuid_v7, bench_hmac_sign, bench_hmac_verify
}

criterion_main!(throughput);
