-- C7: Postgres row-level security. See usora-core-service's
-- V3__row_level_security.sql for the full mechanism.
--
-- NOTE, found while writing this migration: V2__tenant_schema.sql
-- defines create_tenant_notification_partition()/create_tenant_config()
-- implementing a legacy inheritance-based (INHERITS, not declarative
-- PARTITION BY) per-tenant partitioning scheme — but nothing anywhere in
-- this codebase's Java sources calls either function (confirmed by
-- repository-wide search), same as the dead per-tenant-schema machinery
-- found in usora-tenant-service's V2 migration. In practice every row
-- lives directly in the `notifications` parent table, which is what the
-- policy below actually protects; there are no live child partitions to
-- worry about RLS-inheritance semantics for (which differ meaningfully
-- from declarative partitioning's automatic policy cascade — legacy
-- table inheritance does NOT automatically inherit a parent's RLS
-- policy onto children, which is one more reason this being dead code
-- rather than a real active partitioning strategy is worth confirming
-- explicitly here, not just assuming).
DO $$
BEGIN
    BEGIN
        CREATE ROLE usora_notification_runtime LOGIN;
    EXCEPTION
        WHEN duplicate_object THEN
            NULL;
        WHEN insufficient_privilege THEN
            RAISE NOTICE 'Insufficient privilege to CREATE ROLE usora_notification_runtime — '
                'assuming it is provisioned out of band and continuing with GRANTs only.';
    END;
END
$$;

GRANT USAGE ON SCHEMA public TO usora_notification_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO usora_notification_runtime;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO usora_notification_runtime;

ALTER TABLE notifications ENABLE ROW LEVEL SECURITY;
ALTER TABLE notifications FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON notifications;
CREATE POLICY tenant_isolation ON notifications
    USING (tenant_id = current_setting('app.current_tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.current_tenant_id', true));

ALTER TABLE tenant_configs ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_configs FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON tenant_configs;
CREATE POLICY tenant_isolation ON tenant_configs
    USING (tenant_id = current_setting('app.current_tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.current_tenant_id', true));
