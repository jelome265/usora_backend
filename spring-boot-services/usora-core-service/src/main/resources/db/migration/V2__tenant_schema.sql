CREATE OR REPLACE FUNCTION public.create_tenant_schema(p_tenant_id VARCHAR)
RETURNS void AS $$
DECLARE
    schema_name VARCHAR;
BEGIN
    schema_name := 'tenant_' || p_tenant_id;

    EXECUTE format('CREATE SCHEMA IF NOT EXISTS %I', schema_name);

    EXECUTE format('
        CREATE TABLE IF NOT EXISTS %I.cases (
            id UUID PRIMARY KEY,
            tenant_id VARCHAR(100) NOT NULL,
            customer_id VARCHAR(255) NOT NULL,
            status VARCHAR(50) NOT NULL DEFAULT ''PENDING'',
            stage VARCHAR(100) NOT NULL DEFAULT ''DOCUMENT_COLLECTION'',
            metadata TEXT,
            created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
            updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
            version BIGINT NOT NULL DEFAULT 0
        )', schema_name);

    EXECUTE format('
        CREATE TABLE IF NOT EXISTS %I.verifications (
            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
            case_id UUID NOT NULL,
            tenant_id VARCHAR(100) NOT NULL,
            document_type VARCHAR(50),
            document_number VARCHAR(255),
            issuing_country VARCHAR(10),
            status VARCHAR(50) NOT NULL DEFAULT ''PENDING'',
            result TEXT,
            metadata TEXT,
            created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
            updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
            version BIGINT NOT NULL DEFAULT 0
        )', schema_name);

    EXECUTE format('
        CREATE INDEX IF NOT EXISTS idx_%I_cases_status ON %I.cases(status)', schema_name, schema_name);
    EXECUTE format('
        CREATE INDEX IF NOT EXISTS idx_%I_verifications_case_id ON %I.verifications(case_id)', schema_name, schema_name);
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION public.drop_tenant_schema(p_tenant_id VARCHAR)
RETURNS void AS $$
BEGIN
    EXECUTE format('DROP SCHEMA IF EXISTS %I CASCADE', 'tenant_' || p_tenant_id);
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION public.tenant_schema_exists(p_tenant_id VARCHAR)
RETURNS boolean AS $$
DECLARE
    schema_exists boolean;
BEGIN
    SELECT EXISTS(
        SELECT 1 FROM information_schema.schemata
        WHERE schema_name = 'tenant_' || p_tenant_id
    ) INTO schema_exists;
    RETURN schema_exists;
END;
$$ LANGUAGE plpgsql;
