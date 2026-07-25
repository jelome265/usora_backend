SET SCHEMA 'identity';

-- Tenant-specific key history for key rotation tracking
CREATE TABLE tenant_key_history (
    id BIGSERIAL PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    key_id VARCHAR(255) NOT NULL,
    public_key TEXT NOT NULL,
    private_key_encrypted TEXT NOT NULL,
    algorithm VARCHAR(50) NOT NULL DEFAULT 'RS256',
    activated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMP WITH TIME ZONE,
    revoked_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- Token blacklist for revoked tokens
CREATE TABLE token_blacklist (
    id BIGSERIAL PRIMARY KEY,
    jti VARCHAR(255) NOT NULL UNIQUE,
    token_type VARCHAR(50) NOT NULL,
    tenant_id UUID REFERENCES tenants(id) ON DELETE CASCADE,
    client_id VARCHAR(255),
    subject VARCHAR(255),
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    reason VARCHAR(100)
);

-- Audit log for identity events
CREATE TABLE identity_audit_log (
    id BIGSERIAL PRIMARY KEY,
    event_type VARCHAR(100) NOT NULL,
    tenant_id UUID REFERENCES tenants(id) ON DELETE SET NULL,
    user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    client_id VARCHAR(255),
    details JSONB DEFAULT '{}',
    ip_address VARCHAR(45),
    user_agent TEXT,
    request_id VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- MFA recovery codes
CREATE TABLE mfa_recovery_codes (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code_hash VARCHAR(255) NOT NULL,
    used BOOLEAN NOT NULL DEFAULT FALSE,
    used_at TIMESTAMP WITH TIME ZONE,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- Session binding (device fingerprint + IP)
CREATE TABLE session_bindings (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    session_id VARCHAR(255) NOT NULL UNIQUE,
    device_fingerprint VARCHAR(255),
    ip_address VARCHAR(45),
    ip_subnet VARCHAR(45),
    user_agent TEXT,
    bound_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_seen_at TIMESTAMP WITH TIME ZONE,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Indexes
CREATE INDEX idx_tenant_key_history_tenant ON tenant_key_history(tenant_id);
CREATE INDEX idx_tenant_key_history_key_id ON tenant_key_history(key_id);
CREATE INDEX idx_token_blacklist_jti ON token_blacklist(jti);
CREATE INDEX idx_token_blacklist_expires ON token_blacklist(expires_at);
CREATE INDEX idx_identity_audit_log_tenant ON identity_audit_log(tenant_id);
CREATE INDEX idx_identity_audit_log_event ON identity_audit_log(event_type);
CREATE INDEX idx_identity_audit_log_created ON identity_audit_log(created_at);
CREATE INDEX idx_mfa_recovery_codes_user ON mfa_recovery_codes(user_id);
CREATE INDEX idx_session_bindings_user ON session_bindings(user_id);
CREATE INDEX idx_session_bindings_session ON session_bindings(session_id);

-- Insert seed data for initial tenant
INSERT INTO tenants (id, tenant_name, domain, enabled, key_algorithm, key_id)
VALUES (
    '00000000-0000-0000-0000-000000000001',
    'default',
    'usora.io',
    TRUE,
    'RS256',
    'default-key-001'
);

-- Insert default OAuth2 clients
INSERT INTO oauth2_clients (id, tenant_id, client_id, client_secret, client_name, require_pkce, require_consent, access_token_ttl_seconds, refresh_token_ttl_seconds, enabled)
VALUES
    ('00000000-0000-0000-0000-000000000010', '00000000-0000-0000-0000-000000000001', 'usora-web', NULL, 'USORA Web Client', TRUE, TRUE, 900, 604800, TRUE),
    ('00000000-0000-0000-0000-000000000011', '00000000-0000-0000-0000-000000000001', 'usora-api', '{bcrypt}$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'USORA API Client', FALSE, FALSE, 1800, 604800, TRUE);

-- Insert grant types for default clients
INSERT INTO client_grant_types (client_id, grant_type)
SELECT id, 'authorization_code' FROM oauth2_clients WHERE client_id = 'usora-web';
INSERT INTO client_grant_types (client_id, grant_type)
SELECT id, 'refresh_token' FROM oauth2_clients WHERE client_id = 'usora-web';
INSERT INTO client_grant_types (client_id, grant_type)
SELECT id, 'client_credentials' FROM oauth2_clients WHERE client_id = 'usora-api';

-- Insert scopes for default clients
INSERT INTO client_scopes (client_id, scope)
SELECT id, 'openid' FROM oauth2_clients WHERE client_id = 'usora-web';
INSERT INTO client_scopes (client_id, scope)
SELECT id, 'profile' FROM oauth2_clients WHERE client_id = 'usora-web';
INSERT INTO client_scopes (client_id, scope)
SELECT id, 'tenant:read' FROM oauth2_clients WHERE client_id = 'usora-web';
INSERT INTO client_scopes (client_id, scope)
SELECT id, 'admin' FROM oauth2_clients WHERE client_id = 'usora-api';
INSERT INTO client_scopes (client_id, scope)
SELECT id, 'users:read' FROM oauth2_clients WHERE client_id = 'usora-api';
INSERT INTO client_scopes (client_id, scope)
SELECT id, 'users:write' FROM oauth2_clients WHERE client_id = 'usora-api';

-- Insert redirect URIs for web client
INSERT INTO client_redirect_uris (client_id, redirect_uri)
SELECT id, 'http://localhost:3000/callback' FROM oauth2_clients WHERE client_id = 'usora-web';
INSERT INTO client_redirect_uris (client_id, redirect_uri)
SELECT id, 'http://localhost:3000/logout' FROM oauth2_clients WHERE client_id = 'usora-web';

-- Insert admin user for initial tenant (password: admin123)
INSERT INTO users (id, tenant_id, username, email, password_hash, display_name, enabled)
VALUES (
    '00000000-0000-0000-0000-000000000020',
    '00000000-0000-0000-0000-000000000001',
    'admin',
    'admin@usora.io',
    '{bcrypt}$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
    'System Admin',
    TRUE
);

INSERT INTO user_roles (user_id, role)
VALUES ('00000000-0000-0000-0000-000000000020', 'admin');
INSERT INTO user_roles (user_id, role)
VALUES ('00000000-0000-0000-0000-000000000020', 'compliance_officer');
