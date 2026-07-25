CREATE SCHEMA IF NOT EXISTS audit;

SET search_path TO audit;

CREATE TABLE IF NOT EXISTS audit_log (
    id UUID NOT NULL,
    tenant_id VARCHAR(50) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    actor_id VARCHAR(100) NOT NULL,
    action VARCHAR(100) NOT NULL,
    resource_type VARCHAR(100) NOT NULL,
    resource_id VARCHAR(100) NOT NULL,
    before_state JSONB,
    after_state JSONB,
    metadata JSONB,
    outcome VARCHAR(20) NOT NULL,
    severity VARCHAR(20),
    category VARCHAR(50),
    ip_address VARCHAR(45),
    user_agent VARCHAR(500),
    previous_hash VARCHAR(64),
    current_hash VARCHAR(64) NOT NULL,
    signature VARCHAR(128) NOT NULL,
    event_timestamp TIMESTAMP WITH TIME ZONE NOT NULL,
    forensic_flag BOOLEAN NOT NULL DEFAULT FALSE,
    anchored BOOLEAN NOT NULL DEFAULT FALSE,
    archived BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by VARCHAR(100),
    CONSTRAINT pk_audit_log PRIMARY KEY (id, tenant_id)
) PARTITION BY LIST (tenant_id);

CREATE TABLE IF NOT EXISTS tamper_alerts (
    id UUID NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(50) NOT NULL,
    alert_type VARCHAR(50) NOT NULL,
    severity VARCHAR(20) NOT NULL,
    description TEXT,
    affected_hash VARCHAR(64),
    expected_hash VARCHAR(64),
    detected_at TIMESTAMP WITH TIME ZONE NOT NULL,
    resolved BOOLEAN NOT NULL DEFAULT FALSE,
    resolved_at TIMESTAMP WITH TIME ZONE,
    resolved_by VARCHAR(100),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by VARCHAR(100)
);

CREATE TABLE IF NOT EXISTS tenant_config (
    tenant_id VARCHAR(50) NOT NULL PRIMARY KEY,
    hmac_key VARCHAR(512) NOT NULL,
    key_rotation_at TIMESTAMP WITH TIME ZONE,
    blockchain_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    blockchain_channel_id VARCHAR(100) DEFAULT 'auditchannel',
    blockchain_anchor_interval_min INTEGER NOT NULL DEFAULT 60,
    hot_retention_days INTEGER NOT NULL DEFAULT 90,
    cold_retention_years INTEGER NOT NULL DEFAULT 7,
    siem_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by VARCHAR(100)
);

CREATE TABLE IF NOT EXISTS merkle_roots (
    id UUID NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(50) NOT NULL,
    merkle_root VARCHAR(64) NOT NULL,
    interval_start TIMESTAMP WITH TIME ZONE NOT NULL,
    interval_end TIMESTAMP WITH TIME ZONE NOT NULL,
    event_count INTEGER NOT NULL DEFAULT 0,
    signature VARCHAR(128) NOT NULL,
    blockchain_tx_id VARCHAR(128),
    blockchain_anchored BOOLEAN NOT NULL DEFAULT FALSE,
    anchored_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_log_tenant_timestamp ON audit_log (tenant_id, event_timestamp DESC);
CREATE INDEX idx_audit_log_actor ON audit_log (tenant_id, actor_id);
CREATE INDEX idx_audit_log_resource ON audit_log (tenant_id, resource_type, resource_id);
CREATE INDEX idx_audit_log_action ON audit_log (tenant_id, action);
CREATE INDEX idx_audit_log_category ON audit_log (tenant_id, category);
CREATE INDEX idx_audit_log_outcome ON audit_log (tenant_id, outcome);
CREATE INDEX idx_audit_log_hash ON audit_log (tenant_id, current_hash);
CREATE INDEX idx_audit_log_forensic ON audit_log (tenant_id, forensic_flag) WHERE forensic_flag = TRUE;
CREATE INDEX idx_audit_log_anchored ON audit_log (tenant_id, anchored) WHERE anchored = FALSE;
CREATE INDEX idx_audit_log_archived ON audit_log (tenant_id, archived) WHERE archived = FALSE;
CREATE INDEX idx_tamper_alerts_tenant ON tamper_alerts (tenant_id, detected_at DESC);
CREATE INDEX idx_tamper_alerts_resolved ON tamper_alerts (resolved) WHERE resolved = FALSE;
CREATE INDEX idx_merkle_roots_tenant ON merkle_roots (tenant_id, interval_start DESC);

INSERT INTO tenant_config (tenant_id, hmac_key) VALUES ('default', 'default-hmac-key-change-in-production')
ON CONFLICT (tenant_id) DO NOTHING;
