use crate::models::{
    ContributionDirection, EnsembleResult, FeatureContribution, RiskLevel, RiskThresholds,
    RuleContribution, RuleResult, ScoreExplanation,
};
use crate::utils::{clamp, sigmoid, softmax};
use std::collections::HashMap;

pub struct ScoreCalculator {
    ml_weight: f64,
    rule_weight: f64,
    thresholds: RiskThresholds,
    max_explanation_features: usize,
    min_feature_importance: f64,
}

impl Default for ScoreCalculator {
    fn default() -> Self {
        Self {
            ml_weight: 0.7,
            rule_weight: 0.3,
            thresholds: RiskThresholds::default(),
            max_explanation_features: 10,
            min_feature_importance: 0.01,
        }
    }
}

impl ScoreCalculator {
    pub fn new(
        ml_weight: f64,
        rule_weight: f64,
        thresholds: RiskThresholds,
        max_explanation_features: usize,
        min_feature_importance: f64,
    ) -> Self {
        let total = ml_weight + rule_weight;
        Self {
            ml_weight: if total > 0.0 { ml_weight / total } else { 0.7 },
            rule_weight: if total > 0.0 {
                rule_weight / total
            } else {
                0.3
            },
            thresholds,
            max_explanation_features,
            min_feature_importance,
        }
    }

    pub fn calculate(
        &self,
        ml_result: &EnsembleResult,
        rule_results: &[RuleResult],
    ) -> (f64, RiskLevel, f64, Option<RiskLevel>) {
        let ml_score = ml_result
            .probabilities
            .iter()
            .enumerate()
            .fold(0.0, |acc, (i, &p)| {
                let class_score = match i {
                    0 => 0.0,
                    1 => 0.3,
                    2 => 0.7,
                    3 => 0.9,
                    _ => 0.5,
                };
                acc + p * class_score
            });

        let ml_risk_level = RiskLevel::from_score(ml_score, &self.thresholds);

        let rule_delta: f64 = rule_results
            .iter()
            .filter(|r| r.triggered)
            .map(|r| r.score_delta)
            .sum();
        let rule_delta = clamp(rule_delta, -0.5, 0.5);

        let mut composite = ml_score * self.ml_weight + rule_delta * self.rule_weight;
        composite = clamp(composite, 0.0, 1.0);

        let mut risk_level_override = None;
        for result in rule_results {
            if result.triggered {
                if let Some(ref override_level) = result.risk_level_override {
                    let override_score = override_level.as_f64();
                    if override_score > composite {
                        risk_level_override = Some(override_level.clone());
                        composite = composite.max(override_score);
                    }
                }
            }
        }

        let final_risk_level = risk_level_override
            .clone()
            .unwrap_or_else(|| RiskLevel::from_score(composite, &self.thresholds));

        (composite, final_risk_level, ml_score, Some(ml_risk_level))
    }

    pub fn generate_explanation(
        &self,
        ml_result: &EnsembleResult,
        rule_results: &[RuleResult],
        ml_score: f64,
        composite_score: f64,
    ) -> ScoreExplanation {
        let mut feature_contributions: Vec<FeatureContribution> = ml_result
            .feature_importance
            .iter()
            .filter(|&(_, &v)| v >= self.min_feature_importance)
            .map(|(name, &importance)| {
                let direction = if importance > 0.0 {
                    ContributionDirection::IncreasesRisk
                } else if importance < 0.0 {
                    ContributionDirection::DecreasesRisk
                } else {
                    ContributionDirection::Neutral
                };
                FeatureContribution {
                    feature_name: name.clone(),
                    value: crate::models::FeatureValue::Float(importance),
                    importance: importance.abs(),
                    direction,
                    shap_value: importance,
                }
            })
            .collect();

        feature_contributions.sort_by(|a, b| {
            b.importance
                .partial_cmp(&a.importance)
                .unwrap_or(std::cmp::Ordering::Equal)
        });
        feature_contributions.truncate(self.max_explanation_features);

        let rule_contributions: Vec<RuleContribution> = rule_results
            .iter()
            .map(|r| RuleContribution {
                rule_id: r.rule_id.clone(),
                rule_name: r.rule_name.clone(),
                triggered: r.triggered,
                score_delta: r.score_delta,
                description: r.explanation.clone(),
            })
            .collect();

        let top_risk_drivers: Vec<String> = feature_contributions
            .iter()
            .filter(|c| matches!(c.direction, ContributionDirection::IncreasesRisk))
            .take(5)
            .map(|c| c.feature_name.clone())
            .collect();

        let confidence = ml_result.probabilities.iter().cloned().fold(0.0, f64::max);

        ScoreExplanation {
            method: "shap".into(),
            base_score: ml_score,
            feature_contributions,
            rule_contributions,
            top_risk_drivers,
            confidence,
        }
    }

    pub fn set_weights(&mut self, ml_weight: f64, rule_weight: f64) {
        let total = ml_weight + rule_weight;
        if total > 0.0 {
            self.ml_weight = ml_weight / total;
            self.rule_weight = rule_weight / total;
        }
    }

    pub fn weights(&self) -> (f64, f64) {
        (self.ml_weight, self.rule_weight)
    }
}
