pub mod authenticity;
pub mod tamper_detection;

use crate::models::DocumentValidation;
use async_trait::async_trait;

#[derive(Debug, Clone)]
pub struct ValidationResult {
    pub field: String,
    pub passed: bool,
    pub confidence: f32,
    pub details: Vec<String>,
}

#[async_trait]
pub trait Validator: Send + Sync {
    fn name(&self) -> &'static str;
    async fn validate(&self, image_data: &[u8]) -> anyhow::Result<Vec<ValidationResult>>;
}

pub struct ValidationEngine {
    validators: Vec<Box<dyn Validator>>,
}

impl ValidationEngine {
    pub fn new() -> Self {
        Self {
            validators: Vec::new(),
        }
    }

    pub fn with_validator(mut self, validator: Box<dyn Validator>) -> Self {
        self.validators.push(validator);
        self
    }

    pub async fn validate_all(&self, image_data: &[u8]) -> anyhow::Result<DocumentValidation> {
        let mut all_results = Vec::new();
        let mut total_confidence = 0.0f32;
        let mut check_count = 0;

        for validator in &self.validators {
            match validator.validate(image_data).await {
                Ok(results) => {
                    for r in &results {
                        all_results.push(r.clone());
                        if r.passed {
                            total_confidence += r.confidence;
                            check_count += 1;
                        }
                    }
                }
                Err(e) => {
                    tracing::warn!("Validator '{}' failed: {}", validator.name(), e);
                }
            }
        }

        let avg_confidence = if check_count > 0 {
            total_confidence / check_count as f32
        } else {
            0.0
        };

        let failed_checks: Vec<&ValidationResult> =
            all_results.iter().filter(|r| !r.passed).collect();

        let is_tampered = failed_checks
            .iter()
            .any(|r| r.field.contains("tamper") || r.field.contains("forgery"));

        let overall_score = if all_results.is_empty() {
            0.0
        } else {
            let passed = all_results.iter().filter(|r| r.passed).count() as f32;
            passed / all_results.len() as f32
        };

        Ok(DocumentValidation {
            is_valid: overall_score >= 0.6,
            is_tampered,
            authenticity: crate::models::AuthenticityScore {
                overall_score,
                tamper_detection_score: if is_tampered { 0.0 } else { avg_confidence },
                hologram_verification_score: 0.0,
                font_analysis_score: 0.0,
                uv_check_score: 0.0,
                digital_signature_score: 0.0,
                individual_checks: std::collections::HashMap::new(),
            },
            flags: failed_checks
                .iter()
                .map(|r| format!("{}: {}", r.field, r.details.join("; ")))
                .collect(),
            warnings: Vec::new(),
            validation_summary: format!(
                "Overall score: {:.2}, tampered: {}, failed checks: {}",
                overall_score,
                is_tampered,
                failed_checks.len()
            ),
        })
    }
}

impl Default for ValidationEngine {
    fn default() -> Self {
        Self::new()
    }
}
