pub mod dsl;
pub mod evaluator;
pub mod registry;

use crate::models::{FeatureValue, RiskLevel, RuleResult};
use async_trait::async_trait;
use std::collections::HashMap;

pub type ContextMap = HashMap<String, FeatureValue>;

#[async_trait]
pub trait Rule: Send + Sync {
    fn rule_id(&self) -> &str;
    fn name(&self) -> &str;
    fn description(&self) -> &str;
    fn priority(&self) -> i32;
    fn enabled(&self) -> bool;
    fn tags(&self) -> &[String];
    async fn evaluate(&self, context: &ContextMap) -> Result<RuleResult, RuleError>;
}

#[derive(Debug, thiserror::Error)]
pub enum RuleError {
    #[error("Rule evaluation failed: {0}")]
    EvaluationFailed(String),
    #[error("Rule compilation failed: {0}")]
    CompilationFailed(String),
    #[error("Rule validation failed: {0}")]
    ValidationFailed(String),
    #[error("Rule not found: {0}")]
    NotFound(String),
    #[error("DSL error: {0}")]
    DslError(String),
    #[error("Context missing required field: {0}")]
    MissingField(String),
    #[error("Type error: expected {expected}, got {actual}")]
    TypeError { expected: String, actual: String },
    #[error(transparent)]
    Internal(#[from] anyhow::Error),
}

#[derive(Debug, Clone)]
pub struct RuleEngineConfig {
    pub max_execution_time_ms: u64,
    pub max_rules_per_tenant: usize,
    pub enable_short_circuit: bool,
    pub enable_simulation: bool,
    pub default_priority: i32,
}

impl Default for RuleEngineConfig {
    fn default() -> Self {
        Self {
            max_execution_time_ms: 500,
            max_rules_per_tenant: 100,
            enable_short_circuit: true,
            enable_simulation: false,
            default_priority: 0,
        }
    }
}
