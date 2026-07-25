CREATE SCHEMA IF NOT EXISTS compliance;

SET search_path TO compliance;

-- Compliance Rules (versioned and signed)
CREATE TABLE compliance_rules (
    id VARCHAR(36) PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by VARCHAR(100),
    updated_by VARCHAR(100),
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    tenant_id VARCHAR(50) NOT NULL,
    jurisdiction VARCHAR(50),
    rule_id VARCHAR(100) NOT NULL,
    rule_version INTEGER NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    category VARCHAR(100),
    severity VARCHAR(20) NOT NULL,
    drl_content TEXT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    signature_hash VARCHAR(64),
    signed_by VARCHAR(100),
    officer_approved_by VARCHAR(100),
    legal_approved_by VARCHAR(100),
    effective_from TIMESTAMP WITH TIME ZONE,
    expires_at TIMESTAMP WITH TIME ZONE,
    merkle_root VARCHAR(64),
    previous_version_id VARCHAR(36),
    CONSTRAINT fk_previous_version FOREIGN KEY (previous_version_id) REFERENCES compliance_rules(id),
    CONSTRAINT chk_severity CHECK (severity IN ('critical', 'high', 'medium', 'low', 'info'))
);

CREATE INDEX idx_rules_tenant_jurisdiction ON compliance_rules(tenant_id, jurisdiction);
CREATE INDEX idx_rules_active ON compliance_rules(active);
CREATE INDEX idx_rules_rule_id_version ON compliance_rules(rule_id, rule_version);
CREATE INDEX idx_rules_effective ON compliance_rules(effective_from, expires_at);

-- Jurisdiction Configuration
CREATE TABLE jurisdiction_configs (
    id VARCHAR(36) PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by VARCHAR(100),
    updated_by VARCHAR(100),
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    tenant_id VARCHAR(50) NOT NULL,
    jurisdiction VARCHAR(50),
    jurisdiction_code VARCHAR(50) NOT NULL,
    display_name VARCHAR(255),
    regulations TEXT,
    requirements TEXT,
    sanctions_lists TEXT,
    aml_threshold DOUBLE PRECISION DEFAULT 0.85,
    require_adverse_media BOOLEAN NOT NULL DEFAULT FALSE,
    require_pep_check BOOLEAN NOT NULL DEFAULT TRUE,
    max_report_retention_days INTEGER DEFAULT 2555,
    active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE INDEX idx_juris_tenant ON jurisdiction_configs(tenant_id);
CREATE INDEX idx_juris_code ON jurisdiction_configs(jurisdiction_code);

-- Evidence Records
CREATE TABLE evidence_records (
    id VARCHAR(36) PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by VARCHAR(100),
    updated_by VARCHAR(100),
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    tenant_id VARCHAR(50) NOT NULL,
    jurisdiction VARCHAR(50),
    case_id VARCHAR(100) NOT NULL,
    evidence_type VARCHAR(50) NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    content BYTEA,
    mime_type VARCHAR(100),
    storage_path VARCHAR(500),
    verification_hash VARCHAR(64),
    blockchain_tx_id VARCHAR(100),
    notarization_status VARCHAR(30) DEFAULT 'pending',
    metadata_json TEXT,
    tags TEXT,
    submitted_by VARCHAR(100),
    submitted_at TIMESTAMP WITH TIME ZONE,
    archived BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT chk_notarization CHECK (notarization_status IN ('pending', 'notarized', 'failed', 'skipped'))
);

CREATE INDEX idx_evidence_case ON evidence_records(case_id);
CREATE INDEX idx_evidence_tenant ON evidence_records(tenant_id);
CREATE INDEX idx_evidence_hash ON evidence_records(content_hash);

-- Audit Trail (append-only with hash chain)
CREATE TABLE audit_trail (
    id VARCHAR(36) PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by VARCHAR(100),
    updated_by VARCHAR(100),
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    tenant_id VARCHAR(50) NOT NULL,
    jurisdiction VARCHAR(50),
    case_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    action VARCHAR(100) NOT NULL,
    actor VARCHAR(100),
    description TEXT,
    details_json TEXT,
    previous_hash VARCHAR(64),
    current_hash VARCHAR(64) NOT NULL,
    timestamp TIMESTAMP WITH TIME ZONE NOT NULL,
    severity VARCHAR(20),
    ip_address VARCHAR(45),
    user_agent VARCHAR(500)
);

CREATE INDEX idx_audit_case ON audit_trail(case_id);
CREATE INDEX idx_audit_tenant ON audit_trail(tenant_id);
CREATE INDEX idx_audit_timestamp ON audit_trail(timestamp);
CREATE INDEX idx_audit_event_type ON audit_trail(event_type);

-- Compliance Check Results
CREATE TABLE compliance_check_results (
    id VARCHAR(36) PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by VARCHAR(100),
    updated_by VARCHAR(100),
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    tenant_id VARCHAR(50) NOT NULL,
    jurisdiction VARCHAR(50),
    case_id VARCHAR(100) NOT NULL,
    entity_id VARCHAR(100),
    entity_type VARCHAR(50),
    overall_decision VARCHAR(20) NOT NULL,
    validation_json TEXT,
    total_violations INTEGER DEFAULT 0,
    total_warnings INTEGER DEFAULT 0,
    validated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    validated_by VARCHAR(100),
    expires_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT chk_decision CHECK (overall_decision IN ('APPROVED', 'FLAGGED', 'REJECTED', 'PENDING'))
);

CREATE INDEX idx_check_case ON compliance_check_results(case_id);
CREATE INDEX idx_check_tenant ON compliance_check_results(tenant_id);
CREATE INDEX idx_check_decision ON compliance_check_results(overall_decision);
