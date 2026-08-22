SET search_path TO audit;

-- C7: Postgres row-level security. See usora-core-service's
-- V3__row_level_security.sql for the full mechanism.
--
-- audit_log and merkle_roots are LIST-partitioned by tenant_id (see
-- V2__tenant_schema.sql's dynamic per-tenant partition creation) — that
-- partitioning provides physical separation for performance/maintenance,
-- NOT access control: a query with no WHERE clause still scans every
-- partition unless something restricts which rows come back. A policy
-- created on the PARENT (partitioned) table applies automatically to
-- every child partition in Postgres 11+, so this migration only needs
-- to touch the parent, not each per-tenant partition individually.
--
-- NOTE, found while writing this migration: V1__init.sql seeds
-- tenant_config with a row for tenant_id='default' using a literal,
-- publicly-visible HMAC key ('default-hmac-key-change-in-production').
-- The policy below means that row becomes invisible to every real
-- tenant's session going forward (good — it accidentally reduces
-- exposure), but the row and its known key still exist in the database.
-- Not removed here — deleting seed data is a different kind of change
-- than adding RLS, and something might depend on a 'default' tenant
-- config row existing; flagged for separate follow-up.
DO $$
BEGIN
    BEGIN
        CREATE ROLE usora_audit_runtime LOGIN;
    EXCEPTION
        WHEN duplicate_object THEN
            NULL;
        WHEN insufficient_privilege THEN
            RAISE NOTICE 'Insufficient privilege to CREATE ROLE usora_audit_runtime — '
                'assuming it is provisioned out of band and continuing with GRANTs only.';
    END;
END
$$;

GRANT USAGE ON SCHEMA audit TO usora_audit_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA audit TO usora_audit_runtime;
ALTER DEFAULT PRIVILEGES IN SCHEMA audit
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO usora_audit_runtime;

-- audit_log: partitioned parent — policy cascades to every
-- audit.audit_log_<tenant> child partition automatically.
ALTER TABLE audit_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_log FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON audit_log;
CREATE POLICY tenant_isolation ON audit_log
    USING (tenant_id = current_setting('app.current_tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.current_tenant_id', true));

-- tamper_alerts
ALTER TABLE tamper_alerts ENABLE ROW LEVEL SECURITY;
ALTER TABLE tamper_alerts FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON tamper_alerts;
CREATE POLICY tenant_isolation ON tamper_alerts
    USING (tenant_id = current_setting('app.current_tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.current_tenant_id', true));

-- tenant_config (tenant_id is the PK here, same shape as core-service's
-- tenant_configs table)
ALTER TABLE tenant_config ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_config FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON tenant_config;
CREATE POLICY tenant_isolation ON tenant_config
    USING (tenant_id = current_setting('app.current_tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.current_tenant_id', true));

-- merkle_roots: also partitioned by tenant_id — same cascade behavior as
-- audit_log above.
ALTER TABLE merkle_roots ENABLE ROW LEVEL SECURITY;
ALTER TABLE merkle_roots FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON merkle_roots;
CREATE POLICY tenant_isolation ON merkle_roots
    USING (tenant_id = current_setting('app.current_tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.current_tenant_id', true));
