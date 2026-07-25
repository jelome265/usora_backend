use sha2::{Digest, Sha256};
use std::time::Instant;

pub fn compute_checksum(data: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(data);
    hex::encode(hasher.finalize())
}

pub fn now_millis() -> i64 {
    chrono::Utc::now().timestamp_millis()
}

pub fn now_micros() -> i64 {
    chrono::Utc::now().timestamp_nanos() / 1000
}

pub struct Stopwatch {
    start: Instant,
}

impl Stopwatch {
    pub fn start() -> Self {
        Self {
            start: Instant::now(),
        }
    }

    pub fn elapsed_ms(&self) -> f64 {
        self.start.elapsed().as_secs_f64() * 1000.0
    }

    pub fn elapsed_micros(&self) -> f64 {
        self.start.elapsed().as_secs_f64() * 1_000_000.0
    }

    pub fn reset(&mut self) {
        self.start = Instant::now();
    }
}

pub fn normalize_scores(scores: &mut [f64]) {
    if scores.is_empty() {
        return;
    }
    let max = scores.iter().cloned().fold(f64::NEG_INFINITY, f64::max);
    let min = scores.iter().cloned().fold(f64::INFINITY, f64::min);
    let range = max - min;
    if range > f64::EPSILON {
        for score in scores.iter_mut() {
            *score = (*score - min) / range;
        }
    } else {
        for score in scores.iter_mut() {
            *score = 0.5;
        }
    }
}

pub fn sigmoid(x: f64) -> f64 {
    1.0 / (1.0 + (-x).exp())
}

pub fn softmax(logits: &[f64]) -> Vec<f64> {
    let max = logits.iter().cloned().fold(f64::NEG_INFINITY, f64::max);
    let exp: Vec<f64> = logits.iter().map(|&v| (v - max).exp()).collect();
    let sum: f64 = exp.iter().sum();
    if sum > f64::EPSILON {
        exp.iter().map(|&v| v / sum).collect()
    } else {
        vec![1.0 / logits.len() as f64; logits.len()]
    }
}

pub fn clamp(value: f64, min: f64, max: f64) -> f64 {
    value.clamp(min, max)
}

pub fn tenant_prefix(tenant_id: &str) -> String {
    format!("tenant:{}:", tenant_id)
}

pub fn feature_key(tenant_id: &str, applicant_id: &str) -> String {
    format!("{}features:{}", tenant_prefix(tenant_id), applicant_id)
}

pub fn score_cache_key(tenant_id: &str, applicant_id: &str, model_version: &str) -> String {
    format!(
        "{}score:{}:{}",
        tenant_prefix(tenant_id),
        applicant_id,
        model_version
    )
}
