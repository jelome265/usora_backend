-- V2: Multi-tenant schema enhancements
-- Implements tenant isolation with per-tenant partitioning support

-- Tenant settings table for per-tenant configuration
CREATE TABLE tenant_settings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id VARCHAR(64) NOT NULL UNIQUE,
    tenant_org_id VARCHAR(64),
    settings_json TEXT NOT NULL DEFAULT '{}',
    features_json TEXT NOT NULL DEFAULT '{}',
    webhook_defaults_json TEXT NOT NULL DEFAULT '{}',
    banking_config_json TEXT NOT NULL DEFAULT '{}',
    government_config_json TEXT NOT NULL DEFAULT '{}',
    credit_config_json TEXT NOT NULL DEFAULT '{}',
    rate_limits_json TEXT NOT NULL DEFAULT '{}',
    encryption_key_id VARCHAR(128),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_tenant_settings_tenant ON tenant_settings(tenant_id);

-- Tenant audit log
CREATE TABLE tenant_audit_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(128),
    action VARCHAR(64) NOT NULL,
    resource_type VARCHAR(64),
    resource_id VARCHAR(128),
    details JSONB,
    ip_address VARCHAR(45),
    user_agent VARCHAR(512),
    correlation_id VARCHAR(128),
    status VARCHAR(32) NOT NULL DEFAULT 'SUCCESS',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_tenant_audit_tenant ON tenant_audit_log(tenant_id);
CREATE INDEX idx_tenant_audit_created ON tenant_audit_log(created_at);
CREATE INDEX idx_tenant_audit_action ON tenant_audit_log(tenant_id, action);

-- Webhook delivery attempts for retry tracking
CREATE TABLE webhook_delivery_attempts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    webhook_config_id UUID NOT NULL REFERENCES webhook_configs(id) ON DELETE CASCADE,
    tenant_id VARCHAR(64) NOT NULL,
    event_id VARCHAR(128) NOT NULL,
    event_type VARCHAR(256),
    correlation_id VARCHAR(128),
    payload_hash VARCHAR(128),
    attempt_number INTEGER NOT NULL DEFAULT 1,
    max_retries INTEGER NOT NULL DEFAULT 5,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    request_url VARCHAR(1024),
    request_headers TEXT,
    request_body TEXT,
    response_status_code INTEGER,
    response_body TEXT,
    error_message VARCHAR(1024),
    scheduled_at TIMESTAMP WITH TIME ZONE,
    last_attempt_at TIMESTAMP WITH TIME ZONE,
    next_retry_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_webhook_delivery_config ON webhook_delivery_attempts(webhook_config_id);
CREATE INDEX idx_webhook_delivery_tenant ON webhook_delivery_attempts(tenant_id);
CREATE INDEX idx_webhook_delivery_status ON webhook_delivery_attempts(status);
CREATE INDEX idx_webhook_delivery_next_retry ON webhook_delivery_attempts(next_retry_at) WHERE next_retry_at IS NOT NULL;
CREATE INDEX idx_webhook_delivery_event ON webhook_delivery_attempts(event_id);

-- Notification preferences for integration events
CREATE TABLE integration_notification_prefs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    webhook_id UUID REFERENCES webhook_configs(id) ON DELETE CASCADE,
    channel VARCHAR(32) NOT NULL DEFAULT 'WEBHOOK',
    events TEXT NOT NULL DEFAULT '[]',
    enabled BOOLEAN NOT NULL DEFAULT true,
    endpoint VARCHAR(1024),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE(tenant_id, user_id, webhook_id, channel)
);

-- Function to update updated_at timestamp
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Triggers for automatic updated_at
CREATE TRIGGER update_webhook_configs_updated_at
    BEFORE UPDATE ON webhook_configs
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_integration_providers_updated_at
    BEFORE UPDATE ON integration_providers
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_banking_links_updated_at
    BEFORE UPDATE ON banking_links
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_government_verifications_updated_at
    BEFORE UPDATE ON government_verifications
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_credit_reports_updated_at
    BEFORE UPDATE ON credit_reports
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_tenant_settings_updated_at
    BEFORE UPDATE ON tenant_settings
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- Row-level security policy for tenant isolation
ALTER TABLE webhook_configs ENABLE ROW LEVEL SECURITY;
ALTER TABLE integration_providers ENABLE ROW LEVEL SECURITY;
ALTER TABLE banking_links ENABLE ROW LEVEL SECURITY;
ALTER TABLE government_verifications ENABLE ROW LEVEL SECURITY;
ALTER TABLE credit_reports ENABLE ROW LEVEL SECURITY;
ALTER TABLE webhook_delivery_attempts ENABLE ROW LEVEL SECURITY;
ALTER TABLE integration_notification_prefs ENABLE ROW LEVEL SECURITY;

-- Seed data for default tenant if needed
-- INSERT INTO tenant_settings (tenant_id, settings_json, features_json) VALUES ('default', '{}', '{}');
