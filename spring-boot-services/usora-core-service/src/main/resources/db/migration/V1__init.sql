CREATE TABLE IF NOT EXISTS public.cases (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(100) NOT NULL,
    customer_id VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    stage VARCHAR(100) NOT NULL DEFAULT 'DOCUMENT_COLLECTION',
    metadata TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_cases_tenant_id ON public.cases(tenant_id);
CREATE INDEX idx_cases_status ON public.cases(status);
CREATE INDEX idx_cases_created_at ON public.cases(created_at);

CREATE TABLE IF NOT EXISTS public.verifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    case_id UUID NOT NULL REFERENCES public.cases(id),
    tenant_id VARCHAR(100) NOT NULL,
    document_type VARCHAR(50),
    document_number VARCHAR(255),
    issuing_country VARCHAR(10),
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    result TEXT,
    metadata TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_verifications_case_id ON public.verifications(case_id);
CREATE INDEX idx_verifications_tenant_id ON public.verifications(tenant_id);

CREATE TABLE IF NOT EXISTS public.tenant_configs (
    tenant_id VARCHAR(100) PRIMARY KEY,
    config TEXT NOT NULL DEFAULT '{}',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS public.audit_log (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(100),
    case_id UUID,
    action VARCHAR(100) NOT NULL,
    actor VARCHAR(255),
    details TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_log_tenant_id ON public.audit_log(tenant_id);
CREATE INDEX idx_audit_log_case_id ON public.audit_log(case_id);
CREATE INDEX idx_audit_log_created_at ON public.audit_log(created_at);
