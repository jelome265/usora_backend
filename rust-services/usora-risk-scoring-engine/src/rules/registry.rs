use crate::models::RuleDefinition;
use crate::rules::dsl::{DslCache, DslRule};
use crate::rules::{RuleEngineConfig, RuleError};
use async_trait::async_trait;
use chrono::Utc;
use dashmap::DashMap;
use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::RwLock;
use uuid::Uuid;

pub struct RuleRegistry {
    rules: RwLock<HashMap<String, Vec<RuleDefinition>>>,
    tenant_overrides: RwLock<HashMap<String, HashMap<String, RuleDefinition>>>,
    compiled_cache: Arc<DslCache>,
    dsl_cache: Arc<DslCache>,
    config: RuleEngineConfig,
    version_counter: RwLock<HashMap<String, u64>>,
    simulation_rules: Arc<DashMap<String, Vec<RuleDefinition>>>,
}

impl RuleRegistry {
    pub fn new(config: RuleEngineConfig) -> Self {
        Self {
            rules: RwLock::new(HashMap::new()),
            tenant_overrides: RwLock::new(HashMap::new()),
            compiled_cache: Arc::new(DslCache::new()),
            dsl_cache: Arc::new(DslCache::new()),
            config,
            version_counter: RwLock::new(HashMap::new()),
            simulation_rules: Arc::new(DashMap::new()),
        }
    }

    pub async fn register_rule(&self, rule: RuleDefinition) -> Result<(), RuleError> {
        DslRule::validate(&rule.dsl_script)?;

        let mut rules = self.rules.write().await;
        let tenant_key = rule.tenant_id.clone().unwrap_or_else(|| "global".into());
        let tenant_rules = rules.entry(tenant_key.clone()).or_insert_with(Vec::new);

        if tenant_rules.len() >= self.config.max_rules_per_tenant {
            return Err(RuleError::ValidationFailed(format!(
                "Max rules per tenant exceeded: {}",
                self.config.max_rules_per_tenant
            )));
        }

        tenant_rules.push(rule);
        Ok(())
    }

    pub async fn update_rule(
        &self,
        rule_id: &str,
        updated_rule: RuleDefinition,
    ) -> Result<(), RuleError> {
        DslRule::validate(&updated_rule.dsl_script)?;

        let mut rules = self.rules.write().await;
        let tenant_key = updated_rule
            .tenant_id
            .clone()
            .unwrap_or_else(|| "global".into());

        if let Some(tenant_rules) = rules.get_mut(&tenant_key) {
            if let Some(existing) = tenant_rules.iter_mut().find(|r| r.rule_id == rule_id) {
                *existing = updated_rule;
                self.dsl_cache.invalidate(rule_id).await;
                return Ok(());
            }
        }

        Err(RuleError::NotFound(rule_id.to_string()))
    }

    pub async fn delete_rule(
        &self,
        rule_id: &str,
        tenant_id: Option<&str>,
    ) -> Result<(), RuleError> {
        let mut rules = self.rules.write().await;
        let tenant_key = tenant_id.unwrap_or("global");

        if let Some(tenant_rules) = rules.get_mut(tenant_key) {
            let initial_len = tenant_rules.len();
            tenant_rules.retain(|r| r.rule_id != rule_id);
            if tenant_rules.len() < initial_len {
                self.dsl_cache.invalidate(rule_id).await;
                return Ok(());
            }
        }

        Err(RuleError::NotFound(rule_id.to_string()))
    }

    pub async fn get_rule(&self, rule_id: &str, tenant_id: Option<&str>) -> Option<RuleDefinition> {
        let rules = self.rules.read().await;
        let tenant_key = tenant_id.unwrap_or("global");
        if let Some(tenant_rules) = rules.get(tenant_key) {
            return tenant_rules.iter().find(|r| r.rule_id == rule_id).cloned();
        }
        None
    }

    pub async fn get_rules_for_tenant(&self, tenant_id: &str) -> Vec<RuleDefinition> {
        let rules = self.rules.read().await;
        let mut result = rules.get("global").cloned().unwrap_or_default();

        if let Some(tenant_specific) = rules.get(tenant_id) {
            result.extend(tenant_specific.iter().cloned());
        }

        let overrides = self.tenant_overrides.read().await;
        if let Some(overrides_map) = overrides.get(tenant_id) {
            for (rule_id, override_def) in overrides_map {
                if let Some(pos) = result.iter_mut().find(|r| r.rule_id == *rule_id) {
                    *pos = override_def.clone();
                }
            }
        }

        result.sort_by(|a, b| b.priority.cmp(&a.priority));
        result
    }

    pub async fn compile_rules_for_tenant(
        &self,
        tenant_id: &str,
    ) -> Result<Vec<Arc<DslRule>>, RuleError> {
        let definitions = self.get_rules_for_tenant(tenant_id).await;
        let mut compiled = Vec::with_capacity(definitions.len());

        for def in &definitions {
            if !def.enabled {
                continue;
            }
            let rule = self
                .dsl_cache
                .get_or_compile(
                    &def.rule_id,
                    &def.name,
                    &def.description,
                    def.priority,
                    def.enabled,
                    def.tags.clone(),
                    &def.dsl_script,
                )
                .await?;
            compiled.push(rule);
        }

        Ok(compiled)
    }

    pub async fn set_tenant_override(
        &self,
        tenant_id: &str,
        rule_id: &str,
        override_def: RuleDefinition,
    ) -> Result<(), RuleError> {
        DslRule::validate(&override_def.dsl_script)?;
        let mut overrides = self.tenant_overrides.write().await;
        overrides
            .entry(tenant_id.to_string())
            .or_insert_with(HashMap::new)
            .insert(rule_id.to_string(), override_def);
        self.dsl_cache.invalidate(rule_id).await;
        Ok(())
    }

    pub async fn remove_tenant_override(
        &self,
        tenant_id: &str,
        rule_id: &str,
    ) -> Result<(), RuleError> {
        let mut overrides = self.tenant_overrides.write().await;
        if let Some(tenant_overrides) = overrides.get_mut(tenant_id) {
            tenant_overrides.remove(rule_id);
            self.dsl_cache.invalidate(rule_id).await;
            return Ok(());
        }
        Err(RuleError::NotFound(rule_id.to_string()))
    }

    pub async fn create_simulation_rule(
        &self,
        tenant_id: &str,
        rule_def: RuleDefinition,
    ) -> Result<String, RuleError> {
        DslRule::validate(&rule_def.dsl_script)?;
        let sim_id = format!("sim_{}", Uuid::new_v4());
        let mut sim_rule = rule_def;
        sim_rule.rule_id = sim_id.clone();
        sim_rule.simulation_mode = true;

        self.simulation_rules
            .entry(tenant_id.to_string())
            .or_insert_with(Vec::new)
            .push(sim_rule);

        Ok(sim_id)
    }

    pub async fn evaluate_simulation(
        &self,
        tenant_id: &str,
        context: &crate::rules::ContextMap,
    ) -> Vec<RuleDefinition> {
        self.simulation_rules
            .get(tenant_id)
            .map(|rules| rules.clone())
            .unwrap_or_default()
    }

    pub async fn clear_simulation_rules(&self, tenant_id: &str) {
        self.simulation_rules.remove(tenant_id);
    }

    pub async fn list_rules(&self, tenant_id: Option<&str>) -> Vec<RuleDefinition> {
        let rules = self.rules.read().await;
        match tenant_id {
            Some(tid) => {
                let mut result = rules.get("global").cloned().unwrap_or_default();
                if let Some(tenant_rules) = rules.get(tid) {
                    result.extend(tenant_rules.iter().cloned());
                }
                result
            }
            None => rules.values().flat_map(|v| v.iter().cloned()).collect(),
        }
    }

    pub async fn next_version(&self, rule_id: &str) -> String {
        let mut counter = self.version_counter.write().await;
        let count = counter.entry(rule_id.to_string()).or_insert(0);
        *count += 1;
        format!("v{}", count)
    }

    pub async fn validate_script(script: &str) -> Result<(), RuleError> {
        DslRule::validate(script)
    }
}
