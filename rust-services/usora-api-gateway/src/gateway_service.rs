use std::sync::Arc;
use chrono::Utc;
use tonic::{async_trait, Request, Response, Status};
use tracing::instrument;
use crate::AppState;
use crate::proto::gateway;

pub struct GatewayServiceImpl {
    state: Arc<AppState>,
    startup_time: chrono::DateTime<Utc>,
}

impl GatewayServiceImpl {
    pub fn new(state: Arc<AppState>) -> Self {
        Self {
            state,
            startup_time: Utc::now(),
        }
    }
}

#[tonic::async_trait]
impl gateway::gateway_service_server::GatewayService for GatewayServiceImpl {
    #[instrument(skip(self, request))]
    async fn resolve_tenant(
        &self,
        request: Request<gateway::TenantContext>,
    ) -> Result<Response<gateway::TenantResolution>, Status> {
        let ctx = request.into_inner();
        let tenant_id = ctx.tenant_id;

        let resolution = self.state.grpc_clients.tenant
            .clone()
            .get_tenant_config(crate::proto::tenant::GetTenantConfigRequest {
                tenant_id: tenant_id.clone(),
            })
            .await
            .map_err(|e| Status::internal(format!("tenant resolution failed: {e}")))
            .map(|resp| {
                let t = resp.into_inner().tenant.unwrap_or_default();
                gateway::TenantResolution {
                    tenant_id: t.tenant_id,
                    name: t.name,
                    schema_name: format!("tenant_{}", t.tenant_id),
                    active: t.status() == crate::proto::tenant::TenantStatus::Active,
                    settings: t.settings,
                    features: t.features,
                    max_rps: 100,
                    burst_size: 150,
                }
            })?;

        Ok(Response::new(resolution))
    }

    #[instrument(skip(self, request))]
    async fn check_rate_limit(
        &self,
        request: Request<gateway::RateLimitRequest>,
    ) -> Result<Response<gateway::RateLimitResponse>, Status> {
        let req = request.into_inner();
        let key = format!("rl:{}:{}:{}", req.tenant_id, req.client_id, req.endpoint);

        if let Some(ref redis) = self.state.redis {
            let result = redis_ratelimit::check(redis, &key, 100, 150)
                .await
                .map_err(|e| Status::internal(format!("rate limit check failed: {e}")))?;
            Ok(Response::new(gateway::RateLimitResponse {
                allowed: result.allowed,
                remaining: result.remaining as i32,
                reset_after_ms: result.reset_after as i32,
                retry_after_ms: result.retry_after as i32,
            }))
        } else {
            Ok(Response::new(gateway::RateLimitResponse {
                allowed: true,
                remaining: 100,
                reset_after_ms: 1000,
                retry_after_ms: 0,
            }))
        }
    }

    #[instrument(skip(self, request))]
    async fn validate_token(
        &self,
        request: Request<gateway::TokenValidationRequest>,
    ) -> Result<Response<gateway::TokenValidationResponse>, Status> {
        let req = request.into_inner();
        let token = req.token;

        if token.is_empty() {
            return Ok(Response::new(gateway::TokenValidationResponse {
                valid: false,
                subject: String::new(),
                tenant_id: String::new(),
                roles: vec![],
                permissions: vec![],
                expires_at: 0,
                token_type: String::new(),
            }));
        }

        match crate::auth::jwt::JwtValidator::validate(&token) {
            Ok(claims) => Ok(Response::new(gateway::TokenValidationResponse {
                valid: true,
                subject: claims.sub,
                tenant_id: claims.tid.unwrap_or_default(),
                roles: claims.roles,
                permissions: claims.permissions,
                expires_at: claims.exp,
                token_type: "Bearer".to_string(),
            })),
            Err(_) => Ok(Response::new(gateway::TokenValidationResponse {
                valid: false,
                subject: String::new(),
                tenant_id: String::new(),
                roles: vec![],
                permissions: vec![],
                expires_at: 0,
                token_type: String::new(),
            })),
        }
    }

    #[instrument(skip(self))]
    async fn check_health(
        &self,
        _request: Request<gateway::HealthCheckRequest>,
    ) -> Result<Response<gateway::HealthCheckResponse>, Status> {
        Ok(Response::new(gateway::HealthCheckResponse {
            status: "SERVING".to_string(),
            version: env!("CARGO_PKG_VERSION").to_string(),
            uptime_seconds: (Utc::now() - self.startup_time).num_seconds(),
        }))
    }
}

mod redis_ratelimit {
    use redis::aio::ConnectionManager;

    pub struct RateLimitResult {
        pub allowed: bool,
        pub remaining: u64,
        pub reset_after: u64,
        pub retry_after: u64,
    }

    pub async fn check(
        conn: &ConnectionManager,
        key: &str,
        max_rps: u64,
        burst: u64,
    ) -> redis::RedisResult<RateLimitResult> {
        let mut conn = conn.clone();
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_millis() as u64;
        let window = 1000u64;
        let window_key = format!("{}:{}", key, now / window);

        let count: u64 = redis::cmd("GET")
            .arg(&window_key)
            .query_async(&mut conn)
            .await
            .unwrap_or(0);

        if count >= burst {
            return Ok(RateLimitResult {
                allowed: false,
                remaining: 0,
                reset_after: window - (now % window),
                retry_after: window - (now % window),
            });
        }

        let _: () = redis::cmd("INCR")
            .arg(&window_key)
            .query_async(&mut conn)
            .await?;

        let _: () = redis::cmd("EXPIRE")
            .arg(&window_key)
            .arg(2u64)
            .query_async(&mut conn)
            .await?;

        Ok(RateLimitResult {
            allowed: true,
            remaining: burst.saturating_sub(count + 1),
            reset_after: window - (now % window),
            retry_after: 0,
        })
    }
}
