// NOTE: this module previously contained an unused `MiddlewarePipelineBuilder`
// and a hand-rolled, wildcard-open `cors` submodule that were never wired
// into the running app (routes/mod.rs builds the real middleware stack
// directly). Both were removed as dead code — see finding H2 in
// docs/USORA-BACKEND-ENTERPRISE-AUDIT-2026-08-16.md. routes/mod.rs::create_router
// is the single source of truth for how this crate's middleware stack is
// assembled and ordered; do not reintroduce a second, parallel builder here.
pub mod auth;
pub mod rate_limit;
pub mod tenant;
