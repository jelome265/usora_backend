-- V2: Dynamic tenant schema creation functions
-- These functions create isolated schemas per tenant for multi-tenancy

CREATE OR REPLACE FUNCTION create_tenant_schema(p_tenant_id UUID)
RETURNS VOID AS $$
DECLARE
    schema_name VARCHAR;
    tenant_prefix CONSTANT VARCHAR := 'tenant_';
BEGIN
    schema_name := tenant_prefix || REPLACE(p_tenant_id::TEXT, '-', '_');

    EXECUTE format('CREATE SCHEMA IF NOT EXISTS %I', schema_name);

    EXECUTE format('
        CREATE TABLE IF NOT EXISTS %I.tenant_config (
            key VARCHAR(255) PRIMARY KEY,
            value TEXT,
            encrypted BOOLEAN DEFAULT FALSE,
            created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
            updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
        )
    ', schema_name);

    EXECUTE format('
        CREATE TABLE IF NOT EXISTS %I.audit_log (
            id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
            action VARCHAR(100) NOT NULL,
            entity_type VARCHAR(100),
            entity_id UUID,
            performed_by VARCHAR(255),
            details JSONB,
            ip_address VARCHAR(45),
            created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
        )
    ', schema_name);

    EXECUTE format('
        CREATE INDEX IF NOT EXISTS idx_%I_audit_created
        ON %I.audit_log(created_at DESC)
    ', REPLACE(p_tenant_id::TEXT, '-', '_'), schema_name);

    EXECUTE format('
        CREATE INDEX IF NOT EXISTS idx_%I_audit_action
        ON %I.audit_log(action)
    ', REPLACE(p_tenant_id::TEXT, '-', '_'), schema_name);
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION drop_tenant_schema(p_tenant_id UUID)
RETURNS VOID AS $$
DECLARE
    schema_name VARCHAR;
    tenant_prefix CONSTANT VARCHAR := 'tenant_';
BEGIN
    schema_name := tenant_prefix || REPLACE(p_tenant_id::TEXT, '-', '_');
    EXECUTE format('DROP SCHEMA IF EXISTS %I CASCADE', schema_name);
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION list_tenant_schemas()
RETURNS TABLE(schema_name VARCHAR, table_count BIGINT) AS $$
BEGIN
    RETURN QUERY
    SELECT
        n.nspname AS schema_name,
        COUNT(t.relname)::BIGINT AS table_count
    FROM pg_namespace n
    LEFT JOIN pg_class t ON t.relnamespace = n.oid AND t.relkind = 'r'
    WHERE n.nspname LIKE 'tenant\_%'
    GROUP BY n.nspname
    ORDER BY n.nspname;
END;
$$ LANGUAGE plpgsql;
