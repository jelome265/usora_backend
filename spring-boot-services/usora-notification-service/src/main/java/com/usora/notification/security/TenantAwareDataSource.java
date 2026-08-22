package com.usora.notification.security;

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
 * TenantAwareDataSource.java for the full mechanism explanation. Note
 * that for this service specifically, TenantContext is populated from
 * two different call sites: JwtTokenProvider (HTTP requests) and
 * DomainEventListener (Kafka-triggered sends, fixed alongside this
 * migration — see that class's javadoc for why that fix was necessary
 * for RLS not to break the Kafka path entirely).
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
