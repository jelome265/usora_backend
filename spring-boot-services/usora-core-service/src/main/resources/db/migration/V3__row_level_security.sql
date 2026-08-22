-- C7: Postgres row-level security for tenant isolation.
--
-- CONTEXT (see docs/USORA-BACKEND-ENTERPRISE-AUDIT-2026-08-16.md and the
-- follow-up session that found this): tenant isolation across this fleet
-- was application-layer only — a single missed `WHERE tenant_id = ...`
-- in a repository method was a cross-tenant data leak (finding C4), and
-- an audit of usora-integration-service's existing
-- `ALTER TABLE ... ENABLE ROW LEVEL SECURITY` statements found ZERO
-- `CREATE POLICY` statements anywhere — RLS was enabled with no policy,
-- which Postgres treats as "deny all to everyone except the table
-- owner," and the owner is normally the same role Flyway migrations run
-- as. That means RLS was a complete no-op there even before this fix,
-- not a working-but-incomplete control.
--
-- Real RLS needs three things together, not just a CREATE POLICY
-- statement:
--   1. An actual policy tied to a per-connection session variable.
--   2. FORCE ROW LEVEL SECURITY — without it, the owning role still
--      bypasses every policy regardless of how correct the policy is.
--   3. A runtime application role that is NOT the table owner and does
--      NOT have the BYPASSRLS attribute. Migrations continue to run as
--      the owning role (DDL privileges); this service's normal request
--      traffic runs as the new restricted role instead — see
--      TenantAwareDataSource.java / DataSourceConfig.java for how the
--      session variable actually gets set per request, and this chart's
--      values.yaml (postgresql.rlsRuntimeRole.existingSecret) for how
--      the restricted role's credentials are provisioned.
--
-- The role is created defensively: the Flyway-connecting user may not
-- have CREATEROLE in every environment (e.g. a properly least-privileged
-- production setup where role provisioning is a DBA/Terraform
-- responsibility, not something application migrations do). If role
-- creation fails for lack of privilege, this migration logs a NOTICE and
-- continues on the assumption the role already exists — grants below
-- would then simply apply to a role provisioned out of band.
DO $$
BEGIN
    BEGIN
        CREATE ROLE usora_core_runtime LOGIN;
    EXCEPTION
        WHEN duplicate_object THEN
            NULL; -- role already exists, nothing to do
        WHEN insufficient_privilege THEN
            RAISE NOTICE 'Insufficient privilege to CREATE ROLE usora_core_runtime — '
                'assuming it is provisioned out of band (e.g. by a DBA/Terraform) '
                'and continuing with GRANTs only.';
    END;
END
$$;

GRANT USAGE ON SCHEMA public TO usora_core_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO usora_core_runtime;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO usora_core_runtime;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO usora_core_runtime;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE ON SEQUENCES TO usora_core_runtime;

-- cases
ALTER TABLE public.cases ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.cases FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON public.cases;
CREATE POLICY tenant_isolation ON public.cases
    USING (tenant_id = current_setting('app.current_tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.current_tenant_id', true));

-- verifications
ALTER TABLE public.verifications ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.verifications FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON public.verifications;
CREATE POLICY tenant_isolation ON public.verifications
    USING (tenant_id = current_setting('app.current_tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.current_tenant_id', true));

-- tenant_configs (tenant_id IS the primary key here, not a foreign
-- reference — same policy shape still applies)
ALTER TABLE public.tenant_configs ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.tenant_configs FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON public.tenant_configs;
CREATE POLICY tenant_isolation ON public.tenant_configs
    USING (tenant_id = current_setting('app.current_tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.current_tenant_id', true));

-- audit_log: tenant_id is nullable (some events are system-level, not
-- tied to a tenant). A tenant-scoped session must never see another
-- tenant's rows OR unscoped system rows through this policy — system
-- rows need a separate, explicitly-elevated access path (not created
-- here; out of scope for this fix) rather than being folded into the
-- tenant policy's OR clause, which would make it too easy to widen this
-- policy by accident later.
ALTER TABLE public.audit_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.audit_log FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON public.audit_log;
CREATE POLICY tenant_isolation ON public.audit_log
    USING (tenant_id = current_setting('app.current_tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.current_tenant_id', true));
