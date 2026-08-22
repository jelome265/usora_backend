-- C7: Postgres row-level security for tenant isolation. See
-- usora-core-service's V3__row_level_security.sql for the full mechanism
-- explanation (session-variable-bound policy + FORCE RLS + a non-owner
-- runtime role) — this migration follows the same pattern with one
-- important addition specific to this service.
--
-- IMPORTANT ARCHITECTURAL NOTE: unlike every other service in this
-- fleet, the `tenants` table is not a per-tenant business record scoped
-- BY a tenant_id column — each ROW *is* a tenant, and the table is
-- fundamentally operated by PLATFORM_ADMIN callers who are not
-- themselves scoped to any single tenant at all (see ApiController.java
-- — nearly every endpoint requires PLATFORM_ADMIN and must see/manage
-- every tenant, not just one). A blanket tenant-scoped policy here would
-- have broken the service's actual purpose: it would make every
-- platform-admin request — list tenants, create a tenant, suspend a
-- tenant — silently return zero rows, since a platform admin's JWT
-- carries no single tid claim to match against.
--
-- The policy below allows a row through if EITHER the session's tenant
-- matches the row's own id (a TENANT_ADMIN reading their own tenant's
-- record — see ApiController's GET /{tenantId} and /{tenantId}/status,
-- both hasAnyRole('PLATFORM_ADMIN','TENANT_ADMIN')) OR the session is
-- explicitly marked as a platform admin (set by
-- TenantAwareDataSource.java from the verified JWT's roles claim, never
-- from anything client-suppliable). This is a deliberate, narrow
-- exception to tenant isolation, not a general-purpose bypass pattern —
-- do not copy this shape to a table that doesn't have the same
-- admin-operates-across-all-tenants requirement.
DO $$
BEGIN
    BEGIN
        CREATE ROLE usora_tenant_runtime LOGIN;
    EXCEPTION
        WHEN duplicate_object THEN
            NULL;
        WHEN insufficient_privilege THEN
            RAISE NOTICE 'Insufficient privilege to CREATE ROLE usora_tenant_runtime — '
                'assuming it is provisioned out of band and continuing with GRANTs only.';
    END;
END
$$;

GRANT USAGE ON SCHEMA public TO usora_tenant_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO usora_tenant_runtime;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO usora_tenant_runtime;

ALTER TABLE public.tenants ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.tenants FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON public.tenants;
CREATE POLICY tenant_isolation ON public.tenants
    USING (
        id::text = current_setting('app.current_tenant_id', true)
        OR current_setting('app.is_platform_admin', true) = 'true'
    )
    WITH CHECK (
        id::text = current_setting('app.current_tenant_id', true)
        OR current_setting('app.is_platform_admin', true) = 'true'
    );

-- NOTE, found while writing this migration: V2__tenant_schema.sql
-- defines create_tenant_schema()/drop_tenant_schema()/
-- list_tenant_schemas() functions implementing a completely different,
-- per-tenant-physical-schema isolation strategy — but nothing anywhere
-- in this codebase's Java sources ever calls any of them (confirmed by
-- repository-wide search). That machinery is dead code, not an
-- alternative isolation layer already in effect; RLS on the `tenants`
-- table above is the only tenant-isolation control actually live for
-- this service. Left in place rather than dropped, since a migration
-- that already ran in some environment shouldn't be retroactively
-- removed as part of an unrelated fix — but noted here so a future
-- reader doesn't assume it's doing anything.
