package com.usora.core.security;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * C7 / Postgres row-level security: this is the actual mechanism that
 * makes the policies in V3__row_level_security.sql enforce anything.
 * A CREATE POLICY referencing current_setting('app.current_tenant_id')
 * does nothing on its own — something has to set that session variable
 * on every physical connection this application uses, scoped to the
 * verified tenant for whatever request is currently running.
 *
 * HOW IT WORKS: every connection this DataSource hands out has
 * `SET app.current_tenant_id = '<tenant>'` executed immediately after
 * checkout, using the verified value already resolved into
 * {@link TenantContext} by TenantInterceptor (which — see that class's
 * own fix — resolves exclusively from the verified JWT, never a
 * client-supplied header; RLS enforced against a spoofable tenant value
 * would enforce nothing). The returned Connection is a dynamic proxy
 * that intercepts close(): before the physical connection actually
 * returns to the pool, it executes `RESET app.current_tenant_id` so no
 * tenant value can ever leak from one pooled connection into whichever
 * request borrows it next. Without that reset, this would be a much
 * worse bug than no RLS at all — silent cross-tenant access under load,
 * intermittent and very hard to reproduce, rather than an absence of a
 * control.
 *
 * If no tenant is set in TenantContext (an unauthenticated request, or a
 * background job with no request-scoped tenant), app.current_tenant_id
 * is explicitly reset to empty — the RLS policies then match nothing,
 * which is the correct fail-closed behavior: a connection with no known
 * tenant sees no tenant-scoped rows, rather than however Postgres would
 * otherwise evaluate an unset current_setting.
 */
public class TenantAwareDataSource extends DelegatingDataSource {

    public TenantAwareDataSource(DataSource targetDataSource) {
        super(targetDataSource);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return wrap(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return wrap(super.getConnection(username, password));
    }

    private Connection wrap(Connection physical) throws SQLException {
        applyTenantSetting(physical);
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                new TenantResettingHandler(physical));
    }

    private static void applyTenantSetting(Connection connection) throws SQLException {
        String tenantId = TenantContext.getCurrentTenantId();
        try (Statement statement = connection.createStatement()) {
            if (tenantId != null && !tenantId.isBlank()) {
                // set_config's third argument (is_local=false) scopes
                // this to the SESSION, i.e. the lifetime of this pooled
                // physical connection until close() resets it below —
                // not just the current transaction, since plain
                // autocommit reads outside an explicit @Transactional
                // block must be covered too.
                try (var ps = connection.prepareStatement("SELECT set_config('app.current_tenant_id', ?, false)")) {
                    ps.setString(1, tenantId);
                    ps.execute();
                }
            } else {
                statement.execute("RESET app.current_tenant_id");
            }
        }
    }

    private static final class TenantResettingHandler implements InvocationHandler {
        private final Connection delegate;
        private boolean resetDone = false;

        private TenantResettingHandler(Connection delegate) {
            this.delegate = delegate;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("close".equals(method.getName()) && (args == null || args.length == 0)) {
                resetTenantSettingBeforeReturningToPool();
                return method.invoke(delegate, args);
            }
            try {
                return method.invoke(delegate, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause() != null ? e.getCause() : e;
            }
        }

        private void resetTenantSettingBeforeReturningToPool() {
            if (resetDone) {
                return;
            }
            resetDone = true;
            try {
                if (!delegate.isClosed()) {
                    try (Statement statement = delegate.createStatement()) {
                        statement.execute("RESET app.current_tenant_id");
                    }
                }
            } catch (SQLException e) {
                // Do not let a failure to reset the session variable
                // prevent the connection from being closed/returned —
                // but this connection must not be silently reused with a
                // stale tenant setting either. Closing it here (rather
                // than letting the pool recycle it) forces the pool to
                // open a fresh physical connection next time, which is
                // safe by construction.
                try {
                    delegate.abort(Runnable::run);
                } catch (SQLException ignored) {
                    // best-effort — nothing further we can do
                }
            }
        }
    }
}
