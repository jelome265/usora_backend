-- Create tenant-specific schemas for notification partitioning
-- This migration sets up per-tenant notification partitions

-- Function to create tenant notification partition
CREATE OR REPLACE FUNCTION create_tenant_notification_partition(p_tenant_id VARCHAR)
RETURNS VOID AS $$
DECLARE
    partition_name VARCHAR;
BEGIN
    partition_name := 'notifications_' || replace(p_tenant_id, '-', '_');

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_name = partition_name
    ) THEN
        EXECUTE format('
            CREATE TABLE IF NOT EXISTS %I (
                CHECK (tenant_id = %L)
            ) INHERITS (notifications)', partition_name, p_tenant_id);

        EXECUTE format('
            CREATE INDEX IF NOT EXISTS %I ON %I (created_at DESC)',
            partition_name || '_created_idx', partition_name);

        EXECUTE format('
            CREATE INDEX IF NOT EXISTS %I ON %I (status)',
            partition_name || '_status_idx', partition_name);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Function to create tenant config schema
CREATE OR REPLACE FUNCTION create_tenant_config(p_tenant_id VARCHAR, p_tenant_name VARCHAR)
RETURNS VOID AS $$
BEGIN
    INSERT INTO tenant_configs (tenant_id, tenant_name)
    VALUES (p_tenant_id, p_tenant_name)
    ON CONFLICT (tenant_id) DO NOTHING;

    PERFORM create_tenant_notification_partition(p_tenant_id);
END;
$$ LANGUAGE plpgsql;

-- Add table partitioning support
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS partition_key VARCHAR(36);

CREATE INDEX IF NOT EXISTS idx_notifications_partition ON notifications(partition_key);
