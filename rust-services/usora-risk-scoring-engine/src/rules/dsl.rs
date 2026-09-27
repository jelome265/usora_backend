use crate::models::{FeatureValue, RiskLevel};
use crate::rules::{ContextMap, RuleError, RuleResult};
use rhai::{Dynamic, Engine, Scope, AST};
use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::RwLock;

pub struct DslRule {
    rule_id: String,
    name: String,
    description: String,
    priority: i32,
    enabled: bool,
    tags: Vec<String>,
    ast: AST,
    engine: Engine,
    script: String,
}

impl DslRule {
    pub fn compile(
        rule_id: String,
        name: String,
        description: String,
        priority: i32,
        enabled: bool,
        tags: Vec<String>,
        script: String,
    ) -> Result<Self, RuleError> {
        let engine = Self::create_engine();
        let ast = engine
            .compile(&script)
            .map_err(|e| RuleError::CompilationFailed(e.to_string()))?;
        Ok(Self {
            rule_id,
            name,
            description,
            priority,
            enabled,
            tags,
            ast,
            engine,
            script,
        })
    }

    fn create_engine() -> Engine {
        let mut engine = Engine::new();
        engine.set_max_operations(50_000);
        engine.set_max_strings(100);
        engine.set_max_modules(5);

        engine.register_type::<FeatureValue>();

        engine.register_fn("is_string", |v: Dynamic| -> bool { v.is_string() });
        engine.register_fn("is_int", |v: Dynamic| -> bool { v.is_int() });
        engine.register_fn("is_float", |v: Dynamic| -> bool {
            matches!(v, Dynamic::Float(_))
        });
        engine.register_fn("is_bool", |v: Dynamic| -> bool {
            matches!(v, Dynamic::Bool(_))
        });
        engine.register_fn("to_float", |v: Dynamic| -> f64 {
            if v.is_int() {
                v.as_int().unwrap() as f64
            } else if matches!(v, Dynamic::Float(_)) {
                v.as_float().unwrap()
            } else {
                0.0
            }
        });

        engine.register_fn("risk_level", |score: f64| -> String {
            if score >= 0.9 {
                "critical".into()
            } else if score >= 0.7 {
                "high".into()
            } else if score >= 0.3 {
                "medium".into()
            } else {
                "low".into()
            }
        });

        engine.register_fn("clamp", |val: f64, min: f64, max: f64| -> f64 {
            val.clamp(min, max)
        });

        engine.register_fn("abs", |val: f64| -> f64 { val.abs() });
        engine.register_fn("sqrt", |val: f64| -> f64 { val.sqrt() });
        engine.register_fn("log", |val: f64| -> f64 { val.ln() });
        engine.register_fn("round", |val: f64, decimals: i64| -> f64 {
            let factor = 10_f64.powi(decimals as i32);
            (val * factor).round() / factor
        });

        engine.register_fn("max", |a: f64, b: f64| -> f64 { a.max(b) });
        engine.register_fn("min", |a: f64, b: f64| -> f64 { a.min(b) });

        engine
    }

    pub fn evaluate(&self, context: &ContextMap) -> Result<RuleResult, RuleError> {
        let mut scope = Scope::new();
        for (key, value) in context {
            let dyn_val = feature_value_to_dynamic(value);
            scope.push_dynamic(key, dyn_val);
        }

        let result: Dynamic = self
            .engine
            .eval_ast_with_scope(&mut scope, &self.ast)
            .map_err(|e| RuleError::EvaluationFailed(e.to_string()))?;

        parse_rule_result(result, &self.rule_id, &self.name, self.priority)
    }

    pub fn validate(script: &str) -> Result<(), RuleError> {
        let engine = Self::create_engine();
        engine
            .compile(script)
            .map_err(|e| RuleError::ValidationFailed(e.to_string()))?;
        Ok(())
    }

    pub fn script(&self) -> &str {
        &self.script
    }

    // These accessors were previously missing entirely, despite
    // rules/evaluator.rs calling rule.rule_id(), rule.priority(), and
    // rule.enabled() throughout — meaning this crate could not compile
    // as shipped. Implemented as inherent methods (rather than the
    // `Rule` trait in rules/mod.rs, which declares an *async* evaluate())
    // since every existing call site here invokes DslRule::evaluate
    // synchronously, without `.await`; implementing the trait as well
    // would require reconciling that signature mismatch, which is a
    // separate, larger change out of scope for this fix.
    pub fn rule_id(&self) -> &str {
        &self.rule_id
    }

    pub fn name(&self) -> &str {
        &self.name
    }

    pub fn description(&self) -> &str {
        &self.description
    }

    pub fn priority(&self) -> i32 {
        self.priority
    }

    pub fn enabled(&self) -> bool {
        self.enabled
    }

    pub fn tags(&self) -> &[String] {
        &self.tags
    }
}

fn feature_value_to_dynamic(value: &FeatureValue) -> Dynamic {
    match value {
        FeatureValue::String(s) => Dynamic::from(s.clone()),
        FeatureValue::Integer(i) => Dynamic::from(*i),
        FeatureValue::Float(f) => Dynamic::from(*f),
        FeatureValue::Boolean(b) => Dynamic::from(*b),
        FeatureValue::Array(arr) => {
            let items: Vec<Dynamic> = arr.iter().map(feature_value_to_dynamic).collect();
            Dynamic::from(items)
        }
        FeatureValue::Object(map) => {
            let mut dyn_map = rhai::Map::new();
            for (k, v) in map {
                dyn_map.insert(k.clone().into(), feature_value_to_dynamic(v));
            }
            Dynamic::from(dyn_map)
        }
        FeatureValue::Null => Dynamic::UNIT,
    }
}

fn parse_rule_result(
    result: Dynamic,
    rule_id: &str,
    name: &str,
    priority: i32,
) -> Result<RuleResult, RuleError> {
    let map = match result.as_map() {
        Some(m) => m.clone(),
        None => {
            let triggered = !result.is_unit();
            return Ok(RuleResult {
                triggered,
                rule_id: rule_id.to_string(),
                rule_name: name.to_string(),
                priority,
                score_delta: if triggered { 0.1 } else { 0.0 },
                risk_level_override: None,
                explanation: if triggered {
                    format!("Rule '{}' triggered", name)
                } else {
                    "Rule not triggered".into()
                },
                metadata: HashMap::new(),
                execution_time_ms: 0.0,
            });
        }
    };

    let triggered = map
        .get("triggered")
        .and_then(|d| d.as_bool())
        .unwrap_or(false);
    let score_delta = map
        .get("score_delta")
        .and_then(|d| d.as_float())
        .unwrap_or(0.0);
    let explanation = map
        .get("explanation")
        .and_then(|d| d.as_str().map(|s| s.to_string()))
        .unwrap_or_default();

    let risk_level_override = map.get("risk_level").and_then(|d| {
        d.as_str().map(|s| match s {
            "low" => RiskLevel::Low,
            "medium" => RiskLevel::Medium,
            "high" => RiskLevel::High,
            "critical" => RiskLevel::Critical,
            _ => return None,
        })
    });

    let mut metadata = HashMap::new();
    if let Some(md) = map.get("metadata").and_then(|d| d.as_map()) {
        for (k, v) in md {
            metadata.insert(k.to_string(), v.to_string());
        }
    }

    Ok(RuleResult {
        triggered,
        rule_id: rule_id.to_string(),
        rule_name: name.to_string(),
        priority,
        score_delta,
        risk_level_override,
        explanation,
        metadata,
        execution_time_ms: 0.0,
    })
}

pub struct DslCache {
    cache: RwLock<HashMap<String, Arc<DslRule>>>,
}

impl DslCache {
    pub fn new() -> Self {
        Self {
            cache: RwLock::new(HashMap::new()),
        }
    }

    pub async fn get_or_compile(
        &self,
        rule_id: &str,
        name: &str,
        description: &str,
        priority: i32,
        enabled: bool,
        tags: Vec<String>,
        script: &str,
    ) -> Result<Arc<DslRule>, RuleError> {
        {
            let cache = self.cache.read().await;
            if let Some(rule) = cache.get(rule_id) {
                return Ok(rule.clone());
            }
        }

        let rule = Arc::new(DslRule::compile(
            rule_id.to_string(),
            name.to_string(),
            description.to_string(),
            priority,
            enabled,
            tags,
            script.to_string(),
        )?);

        self.cache
            .write()
            .await
            .insert(rule_id.to_string(), rule.clone());
        Ok(rule)
    }

    pub async fn invalidate(&self, rule_id: &str) {
        self.cache.write().await.remove(rule_id);
    }

    pub async fn clear(&self) {
        self.cache.write().await.clear();
    }
}
