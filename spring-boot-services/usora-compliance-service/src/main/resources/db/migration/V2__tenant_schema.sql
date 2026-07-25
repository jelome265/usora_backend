SET search_path TO compliance;

-- Tenant-specific rule packages
CREATE TABLE tenant_rule_packages (
    id VARCHAR(36) PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by VARCHAR(100),
    updated_by VARCHAR(100),
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    tenant_id VARCHAR(50) NOT NULL UNIQUE,
    package_name VARCHAR(255) NOT NULL,
    drl_package TEXT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    last_compiled_at TIMESTAMP WITH TIME ZONE,
    compilation_status VARCHAR(20) DEFAULT 'pending',
    error_message TEXT,
    CONSTRAINT chk_compilation CHECK (compilation_status IN ('pending', 'compiled', 'failed'))
);

CREATE INDEX idx_tenant_packages_tenant ON tenant_rule_packages(tenant_id);

-- Tenant OPA policies
CREATE TABLE tenant_opa_policies (
    id VARCHAR(36) PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by VARCHAR(100),
    updated_by VARCHAR(100),
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    tenant_id VARCHAR(50) NOT NULL,
    policy_name VARCHAR(255) NOT NULL,
    rego_content TEXT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    policy_hash VARCHAR(64),
    UNIQUE(tenant_id, policy_name)
);

CREATE INDEX idx_tenant_opa_tenant ON tenant_opa_policies(tenant_id);

-- Sanctions/PEP screening cache
CREATE TABLE screening_cache (
    id VARCHAR(36) PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by VARCHAR(100),
    updated_by VARCHAR(100),
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    tenant_id VARCHAR(50) NOT NULL,
    entity_name VARCHAR(500) NOT NULL,
    list_type VARCHAR(50) NOT NULL,
    match_score DOUBLE PRECISION,
    is_match BOOLEAN NOT NULL DEFAULT FALSE,
    matched_entity_name VARCHAR(500),
    category VARCHAR(100),
    risk_level VARCHAR(20),
    source_list VARCHAR(200),
    cached_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_screening_tenant_name ON screening_cache(tenant_id, entity_name);
CREATE INDEX idx_screening_match ON screening_cache(is_match);
CREATE INDEX idx_screening_expires ON screening_cache(expires_at);

-- Report metadata
CREATE TABLE report_metadata (
    id VARCHAR(36) PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by VARCHAR(100),
    updated_by VARCHAR(100),
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    tenant_id VARCHAR(50) NOT NULL,
    report_type VARCHAR(50) NOT NULL,
    format VARCHAR(10) NOT NULL,
    case_id VARCHAR(100),
    jurisdiction VARCHAR(50),
    storage_path VARCHAR(500) NOT NULL,
    file_size_bytes BIGINT,
    row_count INTEGER,
    generated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    generated_by VARCHAR(100),
    status VARCHAR(20) NOT NULL DEFAULT 'pending',
    parameters_json TEXT,
    CONSTRAINT chk_report_status CHECK (status IN ('pending', 'generating', 'completed', 'failed'))
);

CREATE INDEX idx_report_tenant ON report_metadata(tenant_id);
CREATE INDEX idx_report_case ON report_metadata(case_id);
CREATE INDEX idx_report_status ON report_metadata(status);

-- Notarization records for evidence
CREATE TABLE notarization_records (
    id VARCHAR(36) PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by VARCHAR(100),
    updated_by VARCHAR(100),
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    tenant_id VARCHAR(50) NOT NULL,
    evidence_id VARCHAR(36) NOT NULL,
    notarization_status VARCHAR(30) NOT NULL DEFAULT 'pending',
    notary_provider VARCHAR(100),
    blockchain_tx_id VARCHAR(100),
    block_number BIGINT,
    notarized_at TIMESTAMP WITH TIME ZONE,
    verified_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_notarization_evidence FOREIGN KEY (evidence_id) REFERENCES evidence_records(id)
);

CREATE INDEX idx_notarization_evidence ON notarization_records(evidence_id);
CREATE INDEX idx_notarization_status ON notarization_records(notarization_status);
