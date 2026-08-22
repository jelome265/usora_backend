SET SCHEMA 'identity';

-- C7: Postgres row-level security. See usora-core-service's
-- V3__row_level_security.sql for the full mechanism (session-variable-
-- bound policy + FORCE RLS + non-owner runtime role).
--
-- This service's schema is more varied than most: several tables
-- (client_redirect_uris, client_grant_types, client_scopes, user_roles)
-- have no tenant_id column of their own at all — only a foreign key to
-- a parent table that does. Their policies use an EXISTS subquery
-- against that parent instead of a direct column comparison. This is
-- the same isolation guarantee, just expressed one join away from the
-- row itself.
--
-- authorization_consents/authorization_sessions store OAuth client_id as
-- a plain string (the client's own client_id value, not a foreign key to
-- oauth2_clients.id) — matched here via oauth2_clients.client_id. These
-- are short-lived OAuth flow records (auth codes, consent grants), but
-- an auth code is effectively a bearer credential for a login in
-- progress; leaving them out of RLS because they're "just session data"
-- would be a real gap, not a reasonable simplification.
--
-- NOTE, found while writing this migration: DomainEventListener.java's
-- handleTenantProvisioned/handleTenantDeactivated Kafka handlers are
-- currently stubs that only log — neither actually writes to the
-- `tenants` table. There is no confirmed active write path to this
-- table at all beyond whatever provisions it directly; flagged
-- separately, not fixed here (out of scope for an RLS migration).
--
-- Unlike usora-tenant-service's `tenants` table, no PLATFORM_ADMIN-gated
-- tenant-management endpoints were found anywhere in this service's
-- controllers — so the `tenants` policy below does NOT include a
-- platform-admin bypass. If one turns out to be needed, add it
-- explicitly and document why, the same way
-- usora-tenant-service's V3 migration does; do not add it speculatively.
DO $$
BEGIN
    BEGIN
        CREATE ROLE usora_identity_runtime LOGIN;
    EXCEPTION
        WHEN duplicate_object THEN
            NULL;
        WHEN insufficient_privilege THEN
            RAISE NOTICE 'Insufficient privilege to CREATE ROLE usora_identity_runtime — '
                'assuming it is provisioned out of band and continuing with GRANTs only.';
    END;
END
$$;

GRANT USAGE ON SCHEMA identity TO usora_identity_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA identity TO usora_identity_runtime;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA identity TO usora_identity_runtime;
ALTER DEFAULT PRIVILEGES IN SCHEMA identity
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO usora_identity_runtime;
ALTER DEFAULT PRIVILEGES IN SCHEMA identity
    GRANT USAGE ON SEQUENCES TO usora_identity_runtime;

-- tenants: each row IS a tenant (its own id is the isolation boundary),
-- same shape as usora-tenant-service's table but WITHOUT the
-- platform-admin bypass — see the note above.
ALTER TABLE tenants ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenants FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON tenants;
CREATE POLICY tenant_isolation ON tenants
    USING (id::text = current_setting('app.current_tenant_id', true))
    WITH CHECK (id::text = current_setting('app.current_tenant_id', true));

-- oauth2_clients: direct tenant_id column.
ALTER TABLE oauth2_clients ENABLE ROW LEVEL SECURITY;
ALTER TABLE oauth2_clients FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON oauth2_clients;
CREATE POLICY tenant_isolation ON oauth2_clients
    USING (tenant_id::text = current_setting('app.current_tenant_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true));

-- users: direct tenant_id column.
ALTER TABLE users ENABLE ROW LEVEL SECURITY;
ALTER TABLE users FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON users;
CREATE POLICY tenant_isolation ON users
    USING (tenant_id::text = current_setting('app.current_tenant_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true));

-- client_redirect_uris / client_grant_types / client_scopes: scoped via
-- their parent oauth2_clients row's tenant_id, since none of these
-- tables have a tenant_id column of their own.
ALTER TABLE client_redirect_uris ENABLE ROW LEVEL SECURITY;
ALTER TABLE client_redirect_uris FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON client_redirect_uris;
CREATE POLICY tenant_isolation ON client_redirect_uris
    USING (EXISTS (
        SELECT 1 FROM oauth2_clients oc
        WHERE oc.id = client_redirect_uris.client_id
        AND oc.tenant_id::text = current_setting('app.current_tenant_id', true)
    ))
    WITH CHECK (EXISTS (
        SELECT 1 FROM oauth2_clients oc
        WHERE oc.id = client_redirect_uris.client_id
        AND oc.tenant_id::text = current_setting('app.current_tenant_id', true)
    ));

ALTER TABLE client_grant_types ENABLE ROW LEVEL SECURITY;
ALTER TABLE client_grant_types FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON client_grant_types;
CREATE POLICY tenant_isolation ON client_grant_types
    USING (EXISTS (
        SELECT 1 FROM oauth2_clients oc
        WHERE oc.id = client_grant_types.client_id
        AND oc.tenant_id::text = current_setting('app.current_tenant_id', true)
    ))
    WITH CHECK (EXISTS (
        SELECT 1 FROM oauth2_clients oc
        WHERE oc.id = client_grant_types.client_id
        AND oc.tenant_id::text = current_setting('app.current_tenant_id', true)
    ));

ALTER TABLE client_scopes ENABLE ROW LEVEL SECURITY;
ALTER TABLE client_scopes FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON client_scopes;
CREATE POLICY tenant_isolation ON client_scopes
    USING (EXISTS (
        SELECT 1 FROM oauth2_clients oc
        WHERE oc.id = client_scopes.client_id
        AND oc.tenant_id::text = current_setting('app.current_tenant_id', true)
    ))
    WITH CHECK (EXISTS (
        SELECT 1 FROM oauth2_clients oc
        WHERE oc.id = client_scopes.client_id
        AND oc.tenant_id::text = current_setting('app.current_tenant_id', true)
    ));

-- user_roles: scoped via the parent users row's tenant_id.
ALTER TABLE user_roles ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_roles FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON user_roles;
CREATE POLICY tenant_isolation ON user_roles
    USING (EXISTS (
        SELECT 1 FROM users u
        WHERE u.id = user_roles.user_id
        AND u.tenant_id::text = current_setting('app.current_tenant_id', true)
    ))
    WITH CHECK (EXISTS (
        SELECT 1 FROM users u
        WHERE u.id = user_roles.user_id
        AND u.tenant_id::text = current_setting('app.current_tenant_id', true)
    ));

-- authorization_consents / authorization_sessions: scoped via
-- oauth2_clients.client_id (the OAuth client_id STRING value, not a
-- foreign key to oauth2_clients.id).
ALTER TABLE authorization_consents ENABLE ROW LEVEL SECURITY;
ALTER TABLE authorization_consents FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON authorization_consents;
CREATE POLICY tenant_isolation ON authorization_consents
    USING (EXISTS (
        SELECT 1 FROM oauth2_clients oc
        WHERE oc.client_id = authorization_consents.client_id
        AND oc.tenant_id::text = current_setting('app.current_tenant_id', true)
    ))
    WITH CHECK (EXISTS (
        SELECT 1 FROM oauth2_clients oc
        WHERE oc.client_id = authorization_consents.client_id
        AND oc.tenant_id::text = current_setting('app.current_tenant_id', true)
    ));

ALTER TABLE authorization_sessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE authorization_sessions FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON authorization_sessions;
CREATE POLICY tenant_isolation ON authorization_sessions
    USING (EXISTS (
        SELECT 1 FROM oauth2_clients oc
        WHERE oc.client_id = authorization_sessions.client_id
        AND oc.tenant_id::text = current_setting('app.current_tenant_id', true)
    ))
    WITH CHECK (EXISTS (
        SELECT 1 FROM oauth2_clients oc
        WHERE oc.client_id = authorization_sessions.client_id
        AND oc.tenant_id::text = current_setting('app.current_tenant_id', true)
    ));
