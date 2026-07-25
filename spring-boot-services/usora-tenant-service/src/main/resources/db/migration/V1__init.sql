CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE IF NOT EXISTS tenants (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(255) NOT NULL,
    domain VARCHAR(255) NOT NULL UNIQUE,
    plan VARCHAR(50) NOT NULL,
    region VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PROVISIONING',
    features JSONB,
    admin_email VARCHAR(255) NOT NULL,
    max_users INTEGER NOT NULL DEFAULT 100,
    storage_quota_bytes BIGINT NOT NULL DEFAULT 107374182400,
    config JSONB,
    stripe_customer_id VARCHAR(255),
    provisioning_status VARCHAR(50),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_tenants_domain ON tenants(domain);
CREATE INDEX idx_tenants_status ON tenants(status);
CREATE INDEX idx_tenants_plan ON tenants(plan);
CREATE INDEX idx_tenants_stripe_customer ON tenants(stripe_customer_id);
CREATE INDEX idx_tenants_created_at ON tenants(created_at DESC);

CREATE INDEX idx_tenants_config_gin ON tenants USING GIN (config jsonb_path_ops);
CREATE INDEX idx_tenants_features_gin ON tenants USING GIN (features jsonb_path_ops);
