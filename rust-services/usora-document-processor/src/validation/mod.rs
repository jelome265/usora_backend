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

        // F-019: this previously computed overall_score as a plain
        // fraction of checks that passed (passed_count / total_count),
        // giving a heuristic-only check (hologram/UV/IR pixel-variance
        // heuristics, capped by AuthenticityCheckEngine at confidence
        // <= 0.4 specifically because they are not genuine forensic
        // signals) the exact same full weight as a real, uncapped check
        // like microprint or font analysis. That silently discarded the
        // whole point of capping heuristic confidence -- the cap never
        // reached this aggregate at all. Weighting by each result's own
        // confidence instead means a low-confidence heuristic "pass"
        // contributes proportionally little to overall_score, and a
        // confident real-check pass contributes proportionally more --
        // matching what "distinguish heuristic evidence from verified
        // forensic evidence" requires numerically, not just in field
        // names and human-readable text.
        let overall_score = if all_results.is_empty() {
            0.0
        } else {
            let confidence_sum: f32 = all_results
                .iter()
                .map(|r| if r.passed { r.confidence } else { 0.0 })
                .sum();
            confidence_sum / all_results.len() as f32
        };

        // F-019: previously individual_checks was always an empty
        // HashMap and hologram_verification_score/uv_check_score were
        // always hardcoded to 0.0, discarding every per-check result
        // AuthenticityCheckEngine actually produced (including its
        // careful heuristic-vs-real labeling and confidence capping) the
        // moment it reached this aggregation layer. Now actually
        // populated from all_results, and heuristic_only_checks lists
        // which of those entries are visible-light heuristics only (by
        // the "_heuristic" field-name suffix AuthenticityCheckEngine
        // already establishes), so a downstream consumer has an explicit,
        // structured way to tell them apart rather than needing to know
        // this service's internal naming convention.
        let individual_checks: std::collections::HashMap<String, f32> = all_results
            .iter()
            .map(|r| (r.field.clone(), r.confidence))
            .collect();
        let heuristic_only_checks: Vec<String> = all_results
            .iter()
            .filter(|r| r.field.ends_with("_heuristic"))
            .map(|r| r.field.clone())
            .collect();

        let hologram_verification_score = individual_checks
            .get("hologram_heuristic")
            .copied()
            .unwrap_or(0.0);
        let uv_check_score = individual_checks
            .get("uv_fluorescence_heuristic")
            .copied()
            .unwrap_or(0.0);
        let font_analysis_score = individual_checks
            .get("font_analysis")
            .copied()
            .unwrap_or(0.0);

        Ok(DocumentValidation {
            is_valid: overall_score >= 0.6,
            is_tampered,
            authenticity: crate::models::AuthenticityScore {
                overall_score,
                tamper_detection_score: if is_tampered { 0.0 } else { avg_confidence },
                hologram_verification_score,
                font_analysis_score,
                uv_check_score,
                digital_signature_score: 0.0,
                individual_checks,
                heuristic_only_checks,
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
