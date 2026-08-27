use crate::models::RuleResult;
use crate::rules::dsl::DslRule;
use crate::rules::{ContextMap, RuleEngineConfig};
use crate::utils::Stopwatch;
use dashmap::DashMap;
use petgraph::graph::{DiGraph, NodeIndex};
use petgraph::Direction;
use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::RwLock;

type RuleNode = Arc<DslRule>;

struct AlphaMemory {
    conditions: Vec<Condition>,
    rules: Vec<NodeIndex>,
}

#[derive(Clone)]
struct BetaMemory {
    tokens: Vec<NodeIndex>,
}

#[derive(Clone)]
enum Condition {
    FieldEquals(String, String),
    FieldGreaterThan(String, f64),
    FieldLessThan(String, f64),
    FieldIn(String, Vec<String>),
    FieldNotIn(String, Vec<String>),
    FieldExists(String),
    FieldMatches(String, String),
}

pub struct ReteNetwork {
    graph: RwLock<DiGraph<NetworkNode, EdgeType>>,
    alpha_memories: RwLock<HashMap<String, AlphaMemory>>,
    beta_memories: RwLock<Vec<BetaMemory>>,
    terminal_nodes: RwLock<Vec<NodeIndex>>,
}

#[derive(Clone)]
enum NetworkNode {
    AlphaNode(Condition),
    BetaNode,
    Terminal(Arc<DslRule>),
    JoinNode,
}

#[derive(Clone)]
enum EdgeType {
    Alpha,
    Beta,
    Join,
}

impl ReteNetwork {
    pub fn new() -> Self {
        Self {
            graph: RwLock::new(DiGraph::new()),
            alpha_memories: RwLock::new(HashMap::new()),
            beta_memories: RwLock::new(Vec::new()),
            terminal_nodes: RwLock::new(Vec::new()),
        }
    }

    pub async fn add_rule(&self, rule: Arc<DslRule>) {
        let mut graph = self.graph.write().await;
        let terminal_idx = graph.add_node(NetworkNode::Terminal(rule.clone()));
        self.terminal_nodes.write().await.push(terminal_idx);

        let condition = Condition::FieldExists("applicant_id".to_string());
        let alpha_idx = graph.add_node(NetworkNode::AlphaNode(condition.clone()));
        graph.add_edge(alpha_idx, terminal_idx, EdgeType::Alpha);

        let mut alpha_memories = self.alpha_memories.write().await;
        let key = condition_key(&condition);
        alpha_memories
            .entry(key)
            .or_insert_with(|| AlphaMemory {
                conditions: vec![condition],
                rules: vec![terminal_idx],
            })
            .rules
            .push(terminal_idx);
    }

    pub async fn evaluate(&self, context: &ContextMap) -> Vec<Arc<DslRule>> {
        let graph = self.graph.read().await;
        let terminals = self.terminal_nodes.read().await;
        let mut matched_rules = Vec::new();

        for &terminal_idx in terminals.iter() {
            if let Some(NetworkNode::Terminal(rule)) = graph.node_weight(terminal_idx) {
                match rule.evaluate(context) {
                    Ok(result) if result.triggered => {
                        matched_rules.push(rule.clone());
                    }
                    _ => {}
                }
            }
        }

        matched_rules
    }

    pub async fn clear(&self) {
        self.graph.write().await.clear();
        self.alpha_memories.write().await.clear();
        self.beta_memories.write().await.clear();
        self.terminal_nodes.write().await.clear();
    }
}

fn condition_key(cond: &Condition) -> String {
    match cond {
        Condition::FieldEquals(f, v) => format!("eq:{}={}", f, v),
        Condition::FieldGreaterThan(f, v) => format!("gt:{}>{}", f, v),
        Condition::FieldLessThan(f, v) => format!("lt:{}<{}", f, v),
        Condition::FieldIn(f, _) => format!("in:{}", f),
        Condition::FieldNotIn(f, _) => format!("nin:{}", f),
        Condition::FieldExists(f) => format!("exists:{}", f),
        Condition::FieldMatches(f, p) => format!("matches:{}:{}", f, p),
    }
}

pub struct RuleEvaluator {
    rete: Arc<ReteNetwork>,
    config: RuleEngineConfig,
    execution_stats: Arc<DashMap<String, ExecutionStats>>,
}

#[derive(Debug, Clone)]
struct ExecutionStats {
    eval_count: u64,
    total_time_ms: f64,
    last_duration_ms: f64,
    trigger_count: u64,
}

impl RuleEvaluator {
    pub fn new(config: RuleEngineConfig) -> Self {
        Self {
            rete: Arc::new(ReteNetwork::new()),
            config,
            execution_stats: Arc::new(DashMap::new()),
        }
    }

    pub async fn add_rule(&self, rule: Arc<DslRule>) {
        self.rete.add_rule(rule).await;
    }

    pub async fn evaluate_rules(
        &self,
        context: &ContextMap,
        tenant_rules: &[Arc<DslRule>],
    ) -> Vec<RuleResult> {
        let mut results: Vec<RuleResult> = Vec::new();

        let mut sorted_rules: Vec<&Arc<DslRule>> = tenant_rules.iter().collect();
        sorted_rules.sort_by(|a, b| b.priority().cmp(&a.priority()));

        for rule in &sorted_rules {
            if !rule.enabled() {
                continue;
            }
            if self.config.enable_short_circuit && has_critical_result(&results) {
                break;
            }

            let sw = Stopwatch::start();
            match rule.evaluate(context) {
                Ok(mut result) => {
                    result.execution_time_ms = sw.elapsed_ms();
                    self.record_execution(rule.rule_id(), sw.elapsed_ms(), result.triggered);
                    results.push(result);
                }
                Err(e) => {
                    tracing::warn!(
                        rule_id = %rule.rule_id(),
                        error = %e,
                        "Rule evaluation failed — escalating rather than silently dropping"
                    );
                    // SECURITY/COMPLIANCE: do not silently drop a rule that
                    // failed to evaluate — that's indistinguishable from a
                    // rule that ran and found no risk. A rule error (often
                    // caused by missing/malformed feature data, which is
                    // itself frequently correlated with a higher-risk
                    // applicant) is an *unknown*, not a clean result, and
                    // must not be able to quietly lower the composite score
                    // relative to what it would have been had the rule run.
                    // Recorded as a High-risk-level-override with
                    // triggered=true so it participates in `calculate()`'s
                    // rule_level escalation and short-circuit logic, and is
                    // clearly tagged in metadata for audit/debugging.
                    let mut metadata = HashMap::new();
                    metadata.insert("evaluation_failed".to_string(), "true".to_string());
                    metadata.insert("error".to_string(), e.to_string());
                    results.push(crate::models::RuleResult {
                        triggered: true,
                        rule_id: rule.rule_id().to_string(),
                        rule_name: format!("{} (EVALUATION FAILED)", rule.rule_id()),
                        priority: rule.priority(),
                        score_delta: 0.0,
                        risk_level_override: Some(crate::models::RiskLevel::High),
                        explanation: format!(
                            "Rule could not be evaluated and is being treated as \
                             unresolved, requiring manual review: {e}"
                        ),
                        metadata,
                        execution_time_ms: sw.elapsed_ms(),
                    });
                }
            }
        }

        results
    }

    pub async fn evaluate_single_rule(
        &self,
        rule: &DslRule,
        context: &ContextMap,
    ) -> Result<RuleResult, crate::rules::RuleError> {
        let sw = Stopwatch::start();
        let result = rule.evaluate(context)?;
        self.record_execution(rule.rule_id(), sw.elapsed_ms(), result.triggered);
        Ok(result)
    }

    fn record_execution(&self, rule_id: &str, duration_ms: f64, triggered: bool) {
        self.execution_stats
            .entry(rule_id.to_string())
            .and_modify(|stats| {
                stats.eval_count += 1;
                stats.total_time_ms += duration_ms;
                stats.last_duration_ms = duration_ms;
                if triggered {
                    stats.trigger_count += 1;
                }
            })
            .or_insert(ExecutionStats {
                eval_count: 1,
                total_time_ms: duration_ms,
                last_duration_ms: duration_ms,
                trigger_count: if triggered { 1 } else { 0 },
            });
    }

    pub fn get_stats(&self, rule_id: &str) -> Option<(u64, f64, u64)> {
        self.execution_stats.get(rule_id).map(|s| {
            (
                s.eval_count,
                if s.eval_count > 0 {
                    s.total_time_ms / s.eval_count as f64
                } else {
                    0.0
                },
                s.trigger_count,
            )
        })
    }

    pub fn rete_network(&self) -> &Arc<ReteNetwork> {
        &self.rete
    }
}

fn has_critical_result(results: &[RuleResult]) -> bool {
    results
        .iter()
        .any(|r| r.risk_level_override == Some(crate::models::RiskLevel::Critical))
}

pub fn aggregate_rule_results(results: &[RuleResult]) -> (f64, Vec<String>) {
    let mut total_delta = 0.0;
    let mut explanations = Vec::new();

    for result in results {
        if result.triggered {
            total_delta += result.score_delta;
            explanations.push(result.explanation.clone());
        }
    }

    (total_delta.clamp(-1.0, 1.0), explanations)
}
