use crate::config::PerformanceConfig;
use crate::engine::RiskEngineError;
use crate::models::ScoringResponse;
use crate::utils::score_cache_key;
use chrono::{DateTime, Utc};
use dashmap::DashMap;
use lru::LruCache;
use parking_lot::Mutex;
use redis::aio::ConnectionManager;
use redis::AsyncCommands;
use std::num::NonZeroUsize;
use std::sync::Arc;
use tokio::sync::RwLock;

struct L1Entry {
    response: ScoringResponse,
    inserted_at: DateTime<Utc>,
}

pub struct MultiLevelCache {
    l1: Arc<DashMap<String, L1Entry>>,
    l2: Arc<RwLock<Option<ConnectionManager>>>,
    lru: Arc<Mutex<LruCache<String, ()>>>,
    l1_capacity: usize,
    l2_ttl_seconds: u64,
    redis_prefix: String,
    hit_count: Arc<dashmap::DashMap<&'static str, u64>>,
}

impl MultiLevelCache {
    pub fn new(config: &PerformanceConfig, redis_prefix: String) -> Self {
        let lru_capacity = NonZeroUsize::new(config.l1_cache_capacity.max(1))
            .unwrap_or(NonZeroUsize::new(1000).unwrap());
        Self {
            l1: Arc::new(DashMap::new()),
            l2: Arc::new(RwLock::new(None)),
            lru: Arc::new(Mutex::new(LruCache::new(lru_capacity))),
            l1_capacity: config.l1_cache_capacity,
            l2_ttl_seconds: config.l2_cache_ttl_seconds,
            redis_prefix,
            hit_count: Arc::new(DashMap::new()),
        }
    }

    pub async fn init_redis(&self, redis_url: &str) -> Result<(), RiskEngineError> {
        let client = redis::Client::open(redis_url)
            .map_err(|e| RiskEngineError::CacheError(e.to_string()))?;
        let conn = client
            .get_connection_manager()
            .await
            .map_err(|e| RiskEngineError::CacheError(e.to_string()))?;
        *self.l2.write().await = Some(conn);
        Ok(())
    }

    pub async fn get(
        &self,
        tenant_id: &str,
        applicant_id: &str,
        model_version: &str,
    ) -> Option<ScoringResponse> {
        let cache_key = score_cache_key(tenant_id, applicant_id, model_version);

        if let Some(entry) = self.l1.get(&cache_key) {
            self.record_hit("l1");
            return Some(entry.response.clone());
        }

        let l2 = self.l2.read().await;
        if let Some(ref conn) = *l2 {
            let redis_key = format!("{}cache:{}", self.redis_prefix, cache_key);
            let result: Result<Option<String>, _> = conn.get(&redis_key).await;
            if let Ok(Some(json)) = result {
                if let Ok(response) = serde_json::from_str::<ScoringResponse>(&json) {
                    self.record_hit("l2");
                    self.add_to_l1(&cache_key, response.clone());
                    return Some(response);
                }
            }
        }

        None
    }

    pub async fn set(
        &self,
        tenant_id: &str,
        applicant_id: &str,
        model_version: &str,
        response: ScoringResponse,
    ) {
        let cache_key = score_cache_key(tenant_id, applicant_id, model_version);
        self.add_to_l1(&cache_key, response.clone());

        let l2 = self.l2.read().await;
        if let Some(ref mut conn) = *l2 {
            let redis_key = format!("{}cache:{}", self.redis_prefix, cache_key);
            if let Ok(json) = serde_json::to_string(&response) {
                let _: Result<(), _> = conn
                    .set_ex(&redis_key, json, self.l2_ttl_seconds as usize)
                    .await;
            }
        }
    }

    pub async fn invalidate(&self, tenant_id: &str, applicant_id: &str, model_version: &str) {
        let cache_key = score_cache_key(tenant_id, applicant_id, model_version);
        self.l1.remove(&cache_key);
        self.lru.lock().pop(&cache_key);

        let l2 = self.l2.read().await;
        if let Some(ref mut conn) = *l2 {
            let redis_key = format!("{}cache:{}", self.redis_prefix, cache_key);
            let _: Result<(), _> = conn.del(&redis_key).await;
        }
    }

    pub async fn invalidate_tenant(&self, tenant_id: &str) {
        let prefix = format!("{}cache:tenant:{}:", self.redis_prefix, tenant_id);
        self.l1.retain(|k, _| !k.starts_with(&prefix));
        self.lru.lock().clear();
    }

    pub async fn clear(&self) {
        self.l1.clear();
        self.lru.lock().clear();
    }

    fn add_to_l1(&self, key: &str, response: ScoringResponse) {
        if self.l1.len() >= self.l1_capacity {
            let mut lru = self.lru.lock();
            if let Some(evicted) = lru.push(key.to_string(), ()) {
                self.l1.remove(&evicted);
            }
        }
        self.l1.insert(
            key.to_string(),
            L1Entry {
                response,
                inserted_at: Utc::now(),
            },
        );
        self.lru.lock().push(key.to_string(), ());
    }

    fn record_hit(&self, level: &'static str) {
        *self.hit_count.entry(level).or_insert(0) += 1;
    }

    pub fn hit_rates(&self) -> (u64, u64) {
        (
            *self.hit_count.get("l1").unwrap_or(&0),
            *self.hit_count.get("l2").unwrap_or(&0),
        )
    }

    pub async fn health_check(&self) -> Result<(), RiskEngineError> {
        let l2 = self.l2.read().await;
        if let Some(ref conn) = *l2 {
            let mut conn = conn.clone();
            redis::cmd("PING")
                .query_async::<_, String>(&mut conn)
                .await
                .map_err(|e| RiskEngineError::CacheError(e.to_string()))?;
        }
        Ok(())
    }
}
