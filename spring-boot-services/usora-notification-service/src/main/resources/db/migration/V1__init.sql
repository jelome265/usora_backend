CREATE TABLE IF NOT EXISTS notifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id VARCHAR(36) NOT NULL,
    channel VARCHAR(20) NOT NULL CHECK (channel IN ('EMAIL', 'SMS', 'WEBHOOK', 'PUSH_IN_APP')),
    to_address VARCHAR(500) NOT NULL,
    template_id VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'DELIVERED', 'FAILED', 'ACKNOWLEDGED')),
    priority VARCHAR(10) NOT NULL DEFAULT 'NORMAL' CHECK (priority IN ('LOW', 'NORMAL', 'HIGH', 'URGENT')),
    subject VARCHAR(500),
    variables JSONB,
    attachments JSONB,
    sent_at TIMESTAMP,
    delivered_at TIMESTAMP,
    failed_at TIMESTAMP,
    error_message TEXT,
    retry_count INTEGER NOT NULL DEFAULT 0,
    acknowledged_at TIMESTAMP,
    provider_message_id VARCHAR(255),
    metadata JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_notifications_tenant_id ON notifications(tenant_id);
CREATE INDEX idx_notifications_status ON notifications(status);
CREATE INDEX idx_notifications_channel ON notifications(channel);
CREATE INDEX idx_notifications_created_at ON notifications(created_at);
CREATE INDEX idx_notifications_tenant_status ON notifications(tenant_id, status);
CREATE INDEX idx_notifications_tenant_created ON notifications(tenant_id, created_at DESC);

CREATE TABLE IF NOT EXISTS tenant_configs (
    tenant_id VARCHAR(36) PRIMARY KEY,
    tenant_name VARCHAR(255) NOT NULL,
    sendgrid_api_key VARCHAR(500),
    sendgrid_from_email VARCHAR(255),
    twilio_account_sid VARCHAR(500),
    twilio_auth_token VARCHAR(500),
    twilio_from_number VARCHAR(20),
    webhook_url_template VARCHAR(1000),
    webhook_secret VARCHAR(500),
    push_fcm_config JSONB,
    push_apns_config JSONB,
    retry_max_attempts INTEGER NOT NULL DEFAULT 3,
    retry_initial_delay_ms BIGINT NOT NULL DEFAULT 1000,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_tenant_configs_active ON tenant_configs(active);
