SET SCHEMA 'identity';

-- PRE-EXISTING BUG, found and fixed while writing this service's Helm
-- chart: JwtTokenProvider.generateDefaultKey() generated a brand-new RSA
-- key pair in memory on every application startup, with no persistence
-- anywhere. tenant_key_history (V2) already exists for per-tenant keys,
-- but the DEFAULT key — the one actually used to sign every JWT under
-- normal operation, since nothing in this codebase ever populates a
-- tenant's public_key/private_key_encrypted columns — had no equivalent
-- table at all. With more than one replica (this chart runs 3+), each
-- pod independently generated its own default key, meaning a token
-- issued by one pod would fail verification against another pod's JWKS
-- endpoint. See JwtTokenProvider.java's updated init()/generateDefaultKey()
-- for the fix that uses this table.
CREATE TABLE system_signing_keys (
    id BIGSERIAL PRIMARY KEY,
    key_id VARCHAR(255) NOT NULL UNIQUE,
    public_key TEXT NOT NULL,
    private_key_encrypted TEXT NOT NULL,
    algorithm VARCHAR(50) NOT NULL DEFAULT 'RS256',
    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    revoked_at TIMESTAMP WITH TIME ZONE
);

-- Only one active default key at a time — the application logic loads
-- "the" active key, not a list, so this constraint makes that assumption
-- enforced by the database rather than just by convention.
CREATE UNIQUE INDEX idx_system_signing_keys_single_active
    ON system_signing_keys (active)
    WHERE active = true;
