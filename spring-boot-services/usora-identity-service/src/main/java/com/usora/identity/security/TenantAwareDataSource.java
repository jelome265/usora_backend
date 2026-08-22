package com.usora.identity.security;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * C7 / Postgres row-level security — see usora-core-service's
 * TenantAwareDataSource.java for the full mechanism explanation. Sets
 * app.current_tenant_id from TenantContext (populated exclusively from
 * the verified JWT's "tid" claim — see TenantInterceptor.java) on every
 * connection checkout, and resets it before the connection returns to
 * the pool so a value can never leak between requests sharing a pooled
 * connection.
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
        String tenantId = TenantContext.getContext().getTenantId();
        try (var ps = connection.prepareStatement("SELECT set_config('app.current_tenant_id', ?, false)")) {
            ps.setString(1, tenantId != null ? tenantId : "");
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
