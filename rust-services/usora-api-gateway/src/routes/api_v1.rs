use std::sync::Arc;
use axum::Router;
use axum::routing::{get, post, put, delete};

use crate::AppState;
use crate::handlers;

pub fn routes() -> Router<Arc<AppState>> {
    Router::new()
        .route("/identity/verify", post(handlers::identity_handler::verify_identity))
        .route("/identity/verification/{id}", get(handlers::identity_handler::get_verification_status))
        .route("/identity/verifications", get(handlers::identity_handler::list_verifications))
        .route("/identity/start-kyc", post(handlers::identity_handler::start_kyc))
        .route("/kyc/document", post(handlers::kyc_handler::submit_document))
        .route("/kyc/biometric", post(handlers::kyc_handler::submit_biometric))
        .route("/tenant/{id}", get(handlers::tenant_handler::get_tenant))
        .route("/tenant/{id}", put(handlers::tenant_handler::update_tenant))
        .route("/tenant/{id}", delete(handlers::tenant_handler::delete_tenant))
        .route("/tenant", post(handlers::tenant_handler::create_tenant))
}
