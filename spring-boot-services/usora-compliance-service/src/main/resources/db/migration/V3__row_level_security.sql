SET search_path TO compliance;

-- C7: Postgres row-level security. See usora-core-service's
-- V3__row_level_security.sql for the full mechanism.
--
-- Every table in this schema has a direct tenant_id column, so this is
-- the straightforward case — no subqueries needed. Note this closes the
-- database-layer half of findings C4/C7 in
-- docs/USORA-BACKEND-ENTERPRISE-AUDIT-2026-08-16.md: the application-
-- layer tenant filters fixed earlier this session in
-- ComplianceRuleRepository/AuditTrailRepository/
-- ComplianceCheckResultRepository (finding C4 — a missing WHERE
-- tenant_id clause was a cross-tenant IDOR) now have a database-level
-- backstop. A future repository method that forgets a tenant filter
-- fails closed here instead of leaking cross-tenant rows.
DO $$
BEGIN
    BEGIN
        CREATE ROLE usora_compliance_runtime LOGIN;
    EXCEPTION
        WHEN duplicate_object THEN
            NULL;
        WHEN insufficient_privilege THEN
            RAISE NOTICE 'Insufficient privilege to CREATE ROLE usora_compliance_runtime — '
                'assuming it is provisioned out of band and continuing with GRANTs only.';
    END;
END
$$;

GRANT USAGE ON SCHEMA compliance TO usora_compliance_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA compliance TO usora_compliance_runtime;
ALTER DEFAULT PRIVILEGES IN SCHEMA compliance
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO usora_compliance_runtime;

DO $$
DECLARE
    tbl TEXT;
BEGIN
    FOREACH tbl IN ARRAY ARRAY[
        'compliance_rules',
        'jurisdiction_configs',
        'evidence_records',
        'audit_trail',
        'compliance_check_results',
        'tenant_rule_packages',
        'tenant_opa_policies',
        'screening_cache',
        'report_metadata',
        'notarization_records'
    ]
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', tbl);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', tbl);
        EXECUTE format('DROP POLICY IF EXISTS tenant_isolation ON %I', tbl);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %I '
            'USING (tenant_id = current_setting(''app.current_tenant_id'', true)) '
            'WITH CHECK (tenant_id = current_setting(''app.current_tenant_id'', true))',
            tbl
        );
    END LOOP;
END
$$;
