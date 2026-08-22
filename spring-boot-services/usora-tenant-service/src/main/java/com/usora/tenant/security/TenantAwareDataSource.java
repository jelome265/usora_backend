package com.usora.tenant.security;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * C7 / Postgres row-level security — see
 * usora-core-service's TenantAwareDataSource.java for the full mechanism
 * (session variable set on checkout, reset before returning to pool, so
 * a value never leaks from one pooled connection to the next request
 * that borrows it).
 *
 * This service sets a SECOND session variable, app.is_platform_admin, in
 * addition to app.current_tenant_id — see
 * V3__row_level_security.sql for why: the `tenants` table's isolation
 * policy has an explicit, narrow bypass for platform-admin callers, who
 * are not scoped to any single tenant by design. Both variables are
 * sourced from TenantContext, which is only ever populated from a
 * verified JWT (see TenantInterceptor / JwtTokenProvider.getAuthentication)
 * — never from anything client-suppliable.
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
        applySessionSettings(physical);
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                new TenantResettingHandler(physical));
    }

    private static void applySessionSettings(Connection connection) throws SQLException {
        var tenantId = TenantContext.getCurrentTenantId();
        var isPlatformAdmin = TenantContext.isPlatformAdmin();

        try (var ps = connection.prepareStatement("SELECT set_config('app.current_tenant_id', ?, false)")) {
            ps.setString(1, tenantId != null ? tenantId.toString() : "");
            ps.execute();
        }
        try (var ps = connection.prepareStatement("SELECT set_config('app.is_platform_admin', ?, false)")) {
            ps.setString(1, isPlatformAdmin ? "true" : "false");
            ps.execute();
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
                resetBeforeReturningToPool();
                return method.invoke(delegate, args);
            }
            try {
                return method.invoke(delegate, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause() != null ? e.getCause() : e;
            }
        }

        private void resetBeforeReturningToPool() {
            if (resetDone) {
                return;
            }
            resetDone = true;
            try {
                if (!delegate.isClosed()) {
                    try (Statement statement = delegate.createStatement()) {
                        statement.execute("RESET app.current_tenant_id");
                        statement.execute("RESET app.is_platform_admin");
                    }
                }
            } catch (SQLException e) {
                try {
                    delegate.abort(Runnable::run);
                } catch (SQLException ignored) {
                    // best-effort — nothing further we can do
                }
            }
        }
    }
}
