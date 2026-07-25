CREATE SCHEMA IF NOT EXISTS identity;

SET SCHEMA 'identity';

-- Tenants table stores per-tenant OAuth2 configuration and key material
CREATE TABLE tenants (
    id UUID PRIMARY KEY,
    tenant_name VARCHAR(255) NOT NULL UNIQUE,
    domain VARCHAR(255),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    public_key TEXT,
    private_key_encrypted TEXT,
    key_algorithm VARCHAR(50) DEFAULT 'RS256',
    key_id VARCHAR(255),
    key_rotation_at TIMESTAMP WITH TIME ZONE,
    opa_policy_url VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by VARCHAR(255),
    updated_by VARCHAR(255)
);

-- OAuth2 client registrations per tenant
CREATE TABLE oauth2_clients (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    client_id VARCHAR(255) NOT NULL UNIQUE,
    client_secret VARCHAR(255),
    client_name VARCHAR(255),
    require_pkce BOOLEAN NOT NULL DEFAULT FALSE,
    require_consent BOOLEAN NOT NULL DEFAULT FALSE,
    access_token_ttl_seconds BIGINT NOT NULL DEFAULT 900,
    refresh_token_ttl_seconds BIGINT NOT NULL DEFAULT 604800,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by VARCHAR(255),
    updated_by VARCHAR(255)
);

-- Client redirect URIs
CREATE TABLE client_redirect_uris (
    id BIGSERIAL PRIMARY KEY,
    client_id UUID NOT NULL REFERENCES oauth2_clients(id) ON DELETE CASCADE,
    redirect_uri VARCHAR(500) NOT NULL
);

-- Client allowed grant types
CREATE TABLE client_grant_types (
    id BIGSERIAL PRIMARY KEY,
    client_id UUID NOT NULL REFERENCES oauth2_clients(id) ON DELETE CASCADE,
    grant_type VARCHAR(100) NOT NULL
);

-- Client scopes
CREATE TABLE client_scopes (
    id BIGSERIAL PRIMARY KEY,
    client_id UUID NOT NULL REFERENCES oauth2_clients(id) ON DELETE CASCADE,
    scope VARCHAR(255) NOT NULL
);

-- User accounts (tenant-scoped)
CREATE TABLE users (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    username VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255),
    display_name VARCHAR(255),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    mfa_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    mfa_type VARCHAR(50),
    last_login_at TIMESTAMP WITH TIME ZONE,
    attributes JSONB DEFAULT '{}',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    UNIQUE(username, tenant_id),
    UNIQUE(email, tenant_id)
);

-- User role assignments
CREATE TABLE user_roles (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role VARCHAR(100) NOT NULL,
    UNIQUE(user_id, role)
);

-- Authorization consents
CREATE TABLE authorization_consents (
    id BIGSERIAL PRIMARY KEY,
    client_id VARCHAR(255) NOT NULL,
    principal_name VARCHAR(255) NOT NULL,
    authorities TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE(client_id, principal_name)
);

-- Authorization sessions (for auth code flow)
CREATE TABLE authorization_sessions (
    id UUID PRIMARY KEY,
    client_id VARCHAR(255) NOT NULL,
    principal_name VARCHAR(255),
    authorization_scopes TEXT,
    state VARCHAR(255),
    code VARCHAR(255),
    code_challenge VARCHAR(255),
    code_challenge_method VARCHAR(10),
    authenticated BOOLEAN NOT NULL DEFAULT FALSE,
    attributes TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Indexes
CREATE INDEX idx_tenants_tenant_name ON tenants(tenant_name);
CREATE INDEX idx_tenants_domain ON tenants(domain);
CREATE INDEX idx_tenants_enabled ON tenants(enabled);
CREATE INDEX idx_oauth2_clients_client_id ON oauth2_clients(client_id);
CREATE INDEX idx_oauth2_clients_tenant ON oauth2_clients(tenant_id);
CREATE INDEX idx_users_tenant_id ON users(tenant_id);
CREATE INDEX idx_users_username ON users(username);
CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_client_redirect_uris_client ON client_redirect_uris(client_id);
CREATE INDEX idx_client_grant_types_client ON client_grant_types(client_id);
CREATE INDEX idx_client_scopes_client ON client_scopes(client_id);
CREATE INDEX idx_user_roles_user ON user_roles(user_id);
CREATE INDEX idx_authorization_sessions_client ON authorization_sessions(client_id);
CREATE INDEX idx_authorization_sessions_code ON authorization_sessions(code);
