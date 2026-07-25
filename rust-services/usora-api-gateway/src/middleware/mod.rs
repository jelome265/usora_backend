pub mod auth;
pub mod cors;
pub mod rate_limit;
pub mod tenant;

use axum::Router;

#[derive(Default)]
pub struct MiddlewarePipelineBuilder {
    pub enable_auth: bool,
    pub enable_cors: bool,
    pub enable_rate_limit: bool,
    pub enable_tenant: bool,
}

impl MiddlewarePipelineBuilder {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn with_auth(mut self) -> Self {
        self.enable_auth = true;
        self
    }

    pub fn with_cors(mut self) -> Self {
        self.enable_cors = true;
        self
    }

    pub fn with_rate_limit(mut self) -> Self {
        self.enable_rate_limit = true;
        self
    }

    pub fn with_tenant(mut self) -> Self {
        self.enable_tenant = true;
        self
    }

    pub fn build(self, router: Router) -> Router {
        let mut app = router;

        if self.enable_tenant {
            app = app.layer(tenant::TenantLayer::new());
        }
        if self.enable_rate_limit {
            app = app.layer(rate_limit::RateLimitLayer::new(100, 200, 1000));
        }
        if self.enable_auth {
            app = app.layer(auth::AuthLayer::new());
        }
        if self.enable_cors {
            app = app.layer(cors::CorsLayer::new());
        }

        app
    }
}
