CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE webhook_configs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    deleted_at TIMESTAMP WITH TIME ZONE,
    tenant_id VARCHAR(64) NOT NULL,
    tenant_org_id VARCHAR(64),
    endpoint_id VARCHAR(128) NOT NULL,
    url VARCHAR(1024) NOT NULL,
    description VARCHAR(512),
    secret VARCHAR(512),
    hmac_secret VARCHAR(512),
    public_key TEXT,
    auth_type VARCHAR(32),
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    retry_count INTEGER NOT NULL DEFAULT 5,
    retry_interval_ms BIGINT NOT NULL DEFAULT 1000,
    rate_limit_per_second INTEGER NOT NULL DEFAULT 100,
    max_payload_size_bytes BIGINT NOT NULL DEFAULT 10485760,
    filter_expression VARCHAR(1024),
    headers TEXT,
    enabled BOOLEAN NOT NULL DEFAULT true,
    webhook_url VARCHAR(1024),
    cloud_event_source VARCHAR(512),
    cloud_event_type_prefix VARCHAR(256),
    UNIQUE(tenant_id, endpoint_id)
);

CREATE TABLE webhook_config_events (
    webhook_config_id UUID NOT NULL REFERENCES webhook_configs(id) ON DELETE CASCADE,
    event_type VARCHAR(256) NOT NULL
);

CREATE INDEX idx_webhook_configs_tenant ON webhook_configs(tenant_id);
CREATE INDEX idx_webhook_configs_status ON webhook_configs(status);
CREATE INDEX idx_webhook_configs_deleted ON webhook_configs(deleted_at) WHERE deleted_at IS NOT NULL;

CREATE TABLE integration_providers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    deleted_at TIMESTAMP WITH TIME ZONE,
    tenant_id VARCHAR(64) NOT NULL,
    tenant_org_id VARCHAR(64),
    provider_type VARCHAR(64) NOT NULL,
    provider_name VARCHAR(128) NOT NULL,
    config_encrypted TEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT true,
    priority INTEGER DEFAULT 0,
    circuit_breaker_state VARCHAR(32) DEFAULT 'CLOSED',
    failure_count INTEGER NOT NULL DEFAULT 0,
    last_failure_at TIMESTAMP WITH TIME ZONE,
    last_success_at TIMESTAMP WITH TIME ZONE,
    rate_limit_rpm INTEGER NOT NULL DEFAULT 100,
    metadata TEXT,
    UNIQUE(tenant_id, provider_type, provider_name)
);

CREATE INDEX idx_integration_providers_tenant ON integration_providers(tenant_id);
CREATE INDEX idx_integration_providers_type ON integration_providers(provider_type);
CREATE INDEX idx_integration_providers_enabled ON integration_providers(enabled);

CREATE TABLE banking_links (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    deleted_at TIMESTAMP WITH TIME ZONE,
    tenant_id VARCHAR(64) NOT NULL,
    tenant_org_id VARCHAR(64),
    user_id VARCHAR(128) NOT NULL,
    provider_name VARCHAR(64) NOT NULL,
    access_token_encrypted TEXT NOT NULL,
    refresh_token_encrypted TEXT,
    token_expires_at TIMESTAMP WITH TIME ZONE,
    account_id VARCHAR(128),
    account_type VARCHAR(64),
    account_number_masked VARCHAR(32),
    routing_number VARCHAR(16),
    institution_name VARCHAR(256),
    institution_id VARCHAR(128),
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    linked_at TIMESTAMP WITH TIME ZONE,
    verified_at TIMESTAMP WITH TIME ZONE,
    last_sync_at TIMESTAMP WITH TIME ZONE,
    kyc_completed BOOLEAN NOT NULL DEFAULT false,
    user_consent_granted BOOLEAN NOT NULL DEFAULT false,
    consent_expires_at TIMESTAMP WITH TIME ZONE,
    error_message VARCHAR(1024),
    metadata TEXT,
    UNIQUE(tenant_id, user_id, provider_name)
);

CREATE INDEX idx_banking_links_tenant ON banking_links(tenant_id);
CREATE INDEX idx_banking_links_user ON banking_links(tenant_id, user_id);
CREATE INDEX idx_banking_links_status ON banking_links(status);
CREATE INDEX idx_banking_links_account ON banking_links(tenant_id, account_id);
CREATE INDEX idx_banking_links_expired ON banking_links(token_expires_at) WHERE token_expires_at IS NOT NULL;

CREATE TABLE government_verifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    deleted_at TIMESTAMP WITH TIME ZONE,
    tenant_id VARCHAR(64) NOT NULL,
    tenant_org_id VARCHAR(64),
    user_id VARCHAR(128) NOT NULL,
    verification_type VARCHAR(32) NOT NULL,
    provider_name VARCHAR(64),
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    request_payload_encrypted TEXT,
    response_payload_encrypted TEXT,
    identity_document_hash VARCHAR(128),
    country_code VARCHAR(4),
    document_number_masked VARCHAR(32),
    verified_at TIMESTAMP WITH TIME ZONE,
    expires_at TIMESTAMP WITH TIME ZONE,
    confidence_score DOUBLE PRECISION,
    verification_id UUID NOT NULL UNIQUE,
    consent_granted BOOLEAN NOT NULL DEFAULT false,
    error_code VARCHAR(64),
    error_message VARCHAR(1024),
    metadata TEXT
);

CREATE INDEX idx_gov_verifications_tenant ON government_verifications(tenant_id);
CREATE INDEX idx_gov_verifications_user ON government_verifications(tenant_id, user_id);
CREATE INDEX idx_gov_verifications_type ON government_verifications(verification_type);
CREATE INDEX idx_gov_verifications_status ON government_verifications(status);
CREATE INDEX idx_gov_verifications_verification_id ON government_verifications(verification_id);
CREATE INDEX idx_gov_verifications_expires ON government_verifications(expires_at) WHERE expires_at IS NOT NULL;

CREATE TABLE credit_reports (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    deleted_at TIMESTAMP WITH TIME ZONE,
    tenant_id VARCHAR(64) NOT NULL,
    tenant_org_id VARCHAR(64),
    user_id VARCHAR(128) NOT NULL,
    bureau_name VARCHAR(64) NOT NULL,
    report_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    request_payload_encrypted TEXT,
    response_payload_encrypted TEXT,
    credit_score INTEGER,
    confidence_score DOUBLE PRECISION,
    fraud_indicators TEXT,
    identity_match BOOLEAN NOT NULL DEFAULT false,
    consumer_consent_granted BOOLEAN NOT NULL DEFAULT false,
    consent_id VARCHAR(128),
    fcra_compliant BOOLEAN NOT NULL DEFAULT true,
    adverse_action_notice_sent BOOLEAN NOT NULL DEFAULT false,
    queried_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMP WITH TIME ZONE,
    error_code VARCHAR(64),
    error_message VARCHAR(1024),
    metadata TEXT
);

CREATE INDEX idx_credit_reports_tenant ON credit_reports(tenant_id);
CREATE INDEX idx_credit_reports_user ON credit_reports(tenant_id, user_id);
CREATE INDEX idx_credit_reports_bureau ON credit_reports(bureau_name);
CREATE INDEX idx_credit_reports_type ON credit_reports(report_type);
CREATE INDEX idx_credit_reports_status ON credit_reports(status);
CREATE INDEX idx_credit_reports_expires ON credit_reports(expires_at) WHERE expires_at IS NOT NULL;
