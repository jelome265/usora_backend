-- C7: Postgres row-level security — REAL implementation, replacing the
-- inert one already in this schema.
--
-- FINDING that started the whole C7 investigation
-- (docs/USORA-BACKEND-ENTERPRISE-AUDIT-2026-08-16.md): V2__tenant_schema.sql
-- already calls `ALTER TABLE ... ENABLE ROW LEVEL SECURITY` on 7 tables
-- — but defines ZERO `CREATE POLICY` statements anywhere. Postgres
-- treats RLS-enabled-with-no-policy as "deny all rows to everyone
-- except the table owner," and the owner is normally the same role
-- Flyway migrations run as — meaning this has been a complete no-op
-- since it was written, not a working-but-incomplete control. On top of
-- that, three more genuinely tenant-scoped tables
-- (tenant_settings, tenant_audit_log, webhook_config_events) never even
-- had ENABLE ROW LEVEL SECURITY called on them at all.
--
-- This migration: re-enables + FORCEs + adds a real policy to the 7
-- tables V2 already touched (safe to re-run — ENABLE/FORCE are
-- idempotent), and does the same for the 3 that were missed. See
-- usora-core-service's V3__row_level_security.sql for the full
-- mechanism (session-variable-bound policy + FORCE RLS + a non-owner
-- runtime role, created below the same way every other service in this
-- fleet does it).
DO $$
BEGIN
    BEGIN
        CREATE ROLE usora_integration_runtime LOGIN;
    EXCEPTION
        WHEN duplicate_object THEN
            NULL;
        WHEN insufficient_privilege THEN
            RAISE NOTICE 'Insufficient privilege to CREATE ROLE usora_integration_runtime — '
                'assuming it is provisioned out of band and continuing with GRANTs only.';
    END;
END
$$;

GRANT USAGE ON SCHEMA public TO usora_integration_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO usora_integration_runtime;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO usora_integration_runtime;

DO $$
DECLARE
    tbl TEXT;
BEGIN
    FOREACH tbl IN ARRAY ARRAY[
        'webhook_configs',
        'integration_providers',
        'banking_links',
        'government_verifications',
        'credit_reports',
        'webhook_delivery_attempts',
        'integration_notification_prefs',
        'tenant_settings',
        'tenant_audit_log'
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

-- webhook_config_events: no tenant_id column of its own — scoped via its
-- parent webhook_configs row, same pattern as identity-service's
-- client_redirect_uris/client_grant_types/client_scopes.
ALTER TABLE webhook_config_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE webhook_config_events FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON webhook_config_events;
CREATE POLICY tenant_isolation ON webhook_config_events
    USING (EXISTS (
        SELECT 1 FROM webhook_configs wc
        WHERE wc.id = webhook_config_events.webhook_config_id
        AND wc.tenant_id = current_setting('app.current_tenant_id', true)
    ))
    WITH CHECK (EXISTS (
        SELECT 1 FROM webhook_configs wc
        WHERE wc.id = webhook_config_events.webhook_config_id
        AND wc.tenant_id = current_setting('app.current_tenant_id', true)
    ));
