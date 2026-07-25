SET search_path TO audit;

CREATE OR REPLACE FUNCTION create_tenant_audit_partition(p_tenant_id VARCHAR)
RETURNS VOID AS $$
DECLARE
    partition_name TEXT;
BEGIN
    partition_name := 'audit_log_' || lower(regexp_replace(p_tenant_id, '[^a-zA-Z0-9]', '_', 'g'));

    IF NOT EXISTS (
        SELECT 1 FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE c.relname = partition_name
        AND n.nspname = 'audit'
    ) THEN
        EXECUTE format(
            'CREATE TABLE audit.%I PARTITION OF audit.audit_log FOR VALUES IN (%L)',
            partition_name, p_tenant_id
        );

        EXECUTE format(
            'CREATE INDEX %I ON audit.%I (event_timestamp DESC)',
            partition_name || '_ts_idx', partition_name
        );

        EXECUTE format(
            'CREATE INDEX %I ON audit.%I (actor_id)',
            partition_name || '_actor_idx', partition_name
        );

        EXECUTE format(
            'CREATE INDEX %I ON audit.%I (resource_type, resource_id)',
            partition_name || '_resource_idx', partition_name
        );

        EXECUTE format(
            'ALTER TABLE audit.%I SET (autovacuum_vacuum_scale_factor = 0.01)',
            partition_name
        );
    END IF;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION create_tenant_merkle_partition(p_tenant_id VARCHAR)
RETURNS VOID AS $$
DECLARE
    partition_name TEXT;
BEGIN
    partition_name := 'merkle_roots_' || lower(regexp_replace(p_tenant_id, '[^a-zA-Z0-9]', '_', 'g'));

    IF NOT EXISTS (
        SELECT 1 FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE c.relname = partition_name
        AND n.nspname = 'audit'
    ) THEN
        EXECUTE format(
            'CREATE TABLE audit.%I PARTITION OF audit.merkle_roots FOR VALUES IN (%L)',
            partition_name, p_tenant_id
        );
    END IF;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION on_tenant_created()
RETURNS TRIGGER AS $$
BEGIN
    PERFORM create_tenant_audit_partition(NEW.tenant_id);
    PERFORM create_tenant_merkle_partition(NEW.tenant_id);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_tenant_created ON audit.tenant_config;
CREATE TRIGGER trg_tenant_created
    AFTER INSERT ON audit.tenant_config
    FOR EACH ROW
    EXECUTE FUNCTION on_tenant_created();

CREATE OR REPLACE FUNCTION get_tenant_partition_size(p_tenant_id VARCHAR)
RETURNS TABLE(partition_name TEXT, row_count BIGINT, size_bytes BIGINT) AS $$
DECLARE
    partition_name TEXT;
BEGIN
    partition_name := 'audit_log_' || lower(regexp_replace(p_tenant_id, '[^a-zA-Z0-9]', '_', 'g'));

    RETURN QUERY EXECUTE format(
        'SELECT
            %L::TEXT,
            COUNT(*)::BIGINT,
            pg_total_relation_size(%L)::BIGINT
        FROM audit.%I',
        partition_name, 'audit.' || partition_name, partition_name
    );
END;
$$ LANGUAGE plpgsql;
