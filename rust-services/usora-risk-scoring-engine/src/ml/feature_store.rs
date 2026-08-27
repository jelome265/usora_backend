use crate::config::FeatureStoreConfig;
use crate::ml::ModelError;
use crate::models::{FeatureSource, FeatureValue, FeatureVector};
use crate::utils::{feature_key, Stopwatch};
use async_trait::async_trait;
use chrono::Utc;
use redis::aio::ConnectionManager;
use redis::AsyncCommands;
use sqlx::postgres::PgPoolOptions;
use sqlx::PgPool;
use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::RwLock;

#[async_trait]
pub trait FeatureStore: Send + Sync {
    async fn get_features(
        &self,
        tenant_id: &str,
        applicant_id: &str,
        feature_names: &[String],
    ) -> Result<FeatureVector, ModelError>;
    async fn get_batch_features(
        &self,
        tenant_id: &str,
        applicant_ids: &[String],
        feature_names: &[String],
    ) -> Result<Vec<FeatureVector>, ModelError>;
    async fn set_features(
        &self,
        tenant_id: &str,
        applicant_id: &str,
        features: HashMap<String, FeatureValue>,
        ttl_seconds: u64,
    ) -> Result<(), ModelError>;
    async fn health_check(&self) -> Result<(), ModelError>;
}

pub struct RedisFeatureStore {
    conn: RwLock<ConnectionManager>,
    config: FeatureStoreConfig,
    prefix: String,
}

impl RedisFeatureStore {
    pub async fn new(redis_url: &str, config: FeatureStoreConfig) -> Result<Self, ModelError> {
        let client =
            redis::Client::open(redis_url).map_err(|e| ModelError::FeatureError(e.to_string()))?;
        let conn = client
            .get_connection_manager()
            .await
            .map_err(|e| ModelError::FeatureError(e.to_string()))?;
        Ok(Self {
            conn: RwLock::new(conn),
            config,
            prefix: "usora:features:".into(),
        })
    }

    fn full_key(&self, tenant_id: &str, applicant_id: &str) -> String {
        format!("{}{}", self.prefix, feature_key(tenant_id, applicant_id))
    }
}

#[async_trait]
impl FeatureStore for RedisFeatureStore {
    async fn get_features(
        &self,
        tenant_id: &str,
        applicant_id: &str,
        feature_names: &[String],
    ) -> Result<FeatureVector, ModelError> {
        let key = self.full_key(tenant_id, applicant_id);
        let mut conn = self.conn.write().await;

        let raw: Option<String> = conn
            .get(&key)
            .await
            .map_err(|e| ModelError::FeatureError(e.to_string()))?;

        let all_features: HashMap<String, FeatureValue> = match raw {
            Some(json_str) => serde_json::from_str(&json_str)
                .map_err(|e| ModelError::FeatureError(e.to_string()))?,
            None => {
                return Err(ModelError::FeatureError(format!(
                    "No features found for {}/{}",
                    tenant_id, applicant_id
                )));
            }
        };

        let filtered: HashMap<String, FeatureValue> = if feature_names.is_empty() {
            all_features
        } else {
            all_features
                .into_iter()
                .filter(|(k, _)| feature_names.contains(k))
                .collect()
        };

        Ok(FeatureVector {
            tenant_id: tenant_id.to_string(),
            applicant_id: applicant_id.to_string(),
            features: filtered,
            fetch_timestamp: Utc::now(),
            source: FeatureSource::Realtime,
        })
    }

    async fn get_batch_features(
        &self,
        tenant_id: &str,
        applicant_ids: &[String],
        feature_names: &[String],
    ) -> Result<Vec<FeatureVector>, ModelError> {
        let mut results = Vec::with_capacity(applicant_ids.len());
        let mut conn = self.conn.write().await;

        for applicant_id in applicant_ids {
            let key = self.full_key(tenant_id, applicant_id);
            let raw: Option<String> = conn
                .get(&key)
                .await
                .map_err(|e| ModelError::FeatureError(e.to_string()))?;

            if let Some(json_str) = raw {
                if let Ok(all_features) =
                    serde_json::from_str::<HashMap<String, FeatureValue>>(&json_str)
                {
                    let filtered = if feature_names.is_empty() {
                        all_features
                    } else {
                        all_features
                            .into_iter()
                            .filter(|(k, _)| feature_names.contains(k))
                            .collect()
                    };
                    results.push(FeatureVector {
                        tenant_id: tenant_id.to_string(),
                        applicant_id: applicant_id.clone(),
                        features: filtered,
                        fetch_timestamp: Utc::now(),
                        source: FeatureSource::Realtime,
                    });
                }
            }
        }

        Ok(results)
    }

    async fn set_features(
        &self,
        tenant_id: &str,
        applicant_id: &str,
        features: HashMap<String, FeatureValue>,
        ttl_seconds: u64,
    ) -> Result<(), ModelError> {
        let key = self.full_key(tenant_id, applicant_id);
        let json = serde_json::to_string(&features)
            .map_err(|e| ModelError::FeatureError(e.to_string()))?;
        let mut conn = self.conn.write().await;
        conn.set_ex::<_, _, ()>(&key, json, ttl_seconds as u64)
            .await
            .map_err(|e| ModelError::FeatureError(e.to_string()))?;
        Ok(())
    }

    async fn health_check(&self) -> Result<(), ModelError> {
        let mut conn = self.conn.write().await;
        redis::cmd("PING")
            .query_async::<_, String>(&mut *conn)
            .await
            .map_err(|e| ModelError::FeatureError(e.to_string()))?;
        Ok(())
    }
}

pub struct PostgresFeatureStore {
    pool: PgPool,
    config: FeatureStoreConfig,
}

impl PostgresFeatureStore {
    pub async fn new(database_url: &str, config: FeatureStoreConfig) -> Result<Self, ModelError> {
        let pool = PgPoolOptions::new()
            .max_connections(10)
            .connect(database_url)
            .await
            .map_err(|e| ModelError::FeatureError(e.to_string()))?;

        sqlx::query(
            r#"
            CREATE TABLE IF NOT EXISTS feature_store (
                tenant_id VARCHAR(64) NOT NULL,
                applicant_id VARCHAR(128) NOT NULL,
                feature_name VARCHAR(256) NOT NULL,
                feature_value JSONB NOT NULL,
                feature_source VARCHAR(32) NOT NULL DEFAULT 'batch',
                created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                PRIMARY KEY (tenant_id, applicant_id, feature_name)
            );
            "#,
        )
        .execute(&pool)
        .await
        .map_err(|e| ModelError::FeatureError(e.to_string()))?;

        Ok(Self { pool, config })
    }
}

#[async_trait]
impl FeatureStore for PostgresFeatureStore {
    async fn get_features(
        &self,
        tenant_id: &str,
        applicant_id: &str,
        feature_names: &[String],
    ) -> Result<FeatureVector, ModelError> {
        let sw = Stopwatch::start();

        let rows: Vec<(String, serde_json::Value)> = if feature_names.is_empty() {
            sqlx::query_as(
                r#"
                SELECT feature_name, feature_value
                FROM feature_store
                WHERE tenant_id = $1 AND applicant_id = $2
                "#,
            )
            .bind(tenant_id)
            .bind(applicant_id)
            .fetch_all(&self.pool)
            .await
            .map_err(|e| ModelError::FeatureError(e.to_string()))?
        } else {
            sqlx::query_as(
                r#"
                SELECT feature_name, feature_value
                FROM feature_store
                WHERE tenant_id = $1 AND applicant_id = $2 AND feature_name = ANY($3)
                "#,
            )
            .bind(tenant_id)
            .bind(applicant_id)
            .bind(feature_names)
            .fetch_all(&self.pool)
            .await
            .map_err(|e| ModelError::FeatureError(e.to_string()))?
        };

        let mut features = HashMap::new();
        for (name, value) in rows {
            let fv: FeatureValue = serde_json::from_value(value)
                .map_err(|e| ModelError::FeatureError(e.to_string()))?;
            features.insert(name, fv);
        }

        tracing::debug!(
            tenant = %tenant_id,
            applicant = %applicant_id,
            feature_count = %features.len(),
            latency_ms = %sw.elapsed_ms(),
            "Fetched features from PostgreSQL"
        );

        Ok(FeatureVector {
            tenant_id: tenant_id.to_string(),
            applicant_id: applicant_id.to_string(),
            features,
            fetch_timestamp: Utc::now(),
            source: FeatureSource::Batch,
        })
    }

    async fn get_batch_features(
        &self,
        tenant_id: &str,
        applicant_ids: &[String],
        feature_names: &[String],
    ) -> Result<Vec<FeatureVector>, ModelError> {
        let mut results = Vec::with_capacity(applicant_ids.len());

        for applicant_id in applicant_ids {
            match self
                .get_features(tenant_id, applicant_id, feature_names)
                .await
            {
                Ok(fv) => results.push(fv),
                Err(e) => {
                    tracing::warn!(
                        tenant = %tenant_id,
                        applicant = %applicant_id,
                        error = %e,
                        "Failed to fetch batch features, skipping"
                    );
                }
            }
        }

        Ok(results)
    }

    async fn set_features(
        &self,
        tenant_id: &str,
        applicant_id: &str,
        features: HashMap<String, FeatureValue>,
        _ttl_seconds: u64,
    ) -> Result<(), ModelError> {
        let mut tx = self
            .pool
            .begin()
            .await
            .map_err(|e| ModelError::FeatureError(e.to_string()))?;

        for (name, value) in &features {
            let json_value =
                serde_json::to_value(value).map_err(|e| ModelError::FeatureError(e.to_string()))?;

            sqlx::query(
                r#"
                INSERT INTO feature_store (tenant_id, applicant_id, feature_name, feature_value, feature_source, updated_at)
                VALUES ($1, $2, $3, $4, 'realtime', NOW())
                ON CONFLICT (tenant_id, applicant_id, feature_name)
                DO UPDATE SET feature_value = $4, feature_source = 'realtime', updated_at = NOW()
                "#,
            )
            .bind(tenant_id)
            .bind(applicant_id)
            .bind(name)
            .bind(&json_value)
            .execute(&mut *tx)
            .await
            .map_err(|e| ModelError::FeatureError(e.to_string()))?;
        }

        tx.commit()
            .await
            .map_err(|e| ModelError::FeatureError(e.to_string()))?;

        Ok(())
    }

    async fn health_check(&self) -> Result<(), ModelError> {
        sqlx::query("SELECT 1")
            .execute(&self.pool)
            .await
            .map_err(|e| ModelError::FeatureError(e.to_string()))?;
        Ok(())
    }
}

pub struct CompositeFeatureStore {
    pub redis: Arc<RedisFeatureStore>,
    pub postgres: Arc<PostgresFeatureStore>,
    config: FeatureStoreConfig,
}

impl CompositeFeatureStore {
    pub fn new(
        redis: Arc<RedisFeatureStore>,
        postgres: Arc<PostgresFeatureStore>,
        config: FeatureStoreConfig,
    ) -> Self {
        Self {
            redis,
            postgres,
            config,
        }
    }

    pub async fn get_features_with_fallback(
        &self,
        tenant_id: &str,
        applicant_id: &str,
        feature_names: &[String],
    ) -> Result<FeatureVector, ModelError> {
        match self
            .redis
            .get_features(tenant_id, applicant_id, feature_names)
            .await
        {
            Ok(fv) => {
                tracing::debug!("Features served from Redis cache");
                return Ok(fv);
            }
            Err(e) => {
                tracing::warn!(error = %e, "Redis feature fetch failed, falling back to PostgreSQL");
            }
        }
        match self
            .postgres
            .get_features(tenant_id, applicant_id, feature_names)
            .await
        {
            Ok(fv) => {
                if let Err(e) = self
                    .redis
                    .set_features(
                        tenant_id,
                        applicant_id,
                        fv.features.clone(),
                        self.config.ttl_seconds,
                    )
                    .await
                {
                    tracing::warn!(error = %e, "Failed to warm Redis cache from PostgreSQL");
                }
                Ok(fv)
            }
            Err(e) => Err(ModelError::FeatureError(format!(
                "All feature stores unavailable: {}",
                e
            ))),
        }
    }
}

pub fn normalize_features(
    features: &HashMap<String, FeatureValue>,
    means: &HashMap<String, f64>,
    stds: &HashMap<String, f64>,
) -> HashMap<String, f64> {
    let mut normalized = HashMap::new();
    for (key, value) in features {
        let raw = match value.as_f64() {
            Some(v) => v,
            None => continue,
        };
        let mean = means.get(key).copied().unwrap_or(0.0);
        let std = stds.get(key).copied().unwrap_or(1.0);
        let norm = if std > f64::EPSILON {
            (raw - mean) / std
        } else {
            0.0
        };
        normalized.insert(key.clone(), norm);
    }
    normalized
}

pub fn features_to_dense(
    features: &HashMap<String, FeatureValue>,
    expected_size: usize,
    feature_names: &[String],
) -> Vec<f64> {
    let mut dense = Vec::with_capacity(expected_size);
    for name in feature_names {
        let value = features.get(name).and_then(|v| v.as_f64()).unwrap_or(0.0);
        dense.push(value);
    }
    while dense.len() < expected_size {
        dense.push(0.0);
    }
    dense
}

pub struct NormalizationParams {
    pub means: HashMap<String, f64>,
    pub stds: HashMap<String, f64>,
    pub feature_order: Vec<String>,
}

impl NormalizationParams {
    pub fn from_json(json: &str) -> Result<Self, ModelError> {
        #[derive(serde::Deserialize)]
        struct ParamsJson {
            means: HashMap<String, f64>,
            stds: HashMap<String, f64>,
            feature_order: Vec<String>,
        }
        let p: ParamsJson =
            serde_json::from_str(json).map_err(|e| ModelError::FeatureError(e.to_string()))?;
        Ok(Self {
            means: p.means,
            stds: p.stds,
            feature_order: p.feature_order,
        })
    }

    pub fn normalize(&self, features: &HashMap<String, FeatureValue>) -> Vec<f64> {
        let mut normalized = Vec::with_capacity(self.feature_order.len());
        for name in &self.feature_order {
            let raw = features.get(name).and_then(|v| v.as_f64()).unwrap_or(0.0);
            let mean = self.means.get(name).copied().unwrap_or(0.0);
            let std = self.stds.get(name).copied().unwrap_or(1.0);
            let norm = if std > f64::EPSILON {
                (raw - mean) / std
            } else {
                0.0
            };
            normalized.push(norm);
        }
        normalized
    }

    pub fn normalize_f64(&self, features: &HashMap<String, f64>) -> Vec<f64> {
        let mut normalized = Vec::with_capacity(self.feature_order.len());
        for name in &self.feature_order {
            let raw = features.get(name).copied().unwrap_or(0.0);
            let mean = self.means.get(name).copied().unwrap_or(0.0);
            let std = self.stds.get(name).copied().unwrap_or(1.0);
            let norm = if std > f64::EPSILON {
                (raw - mean) / std
            } else {
                0.0
            };
            normalized.push(norm);
        }
        normalized
    }
}
