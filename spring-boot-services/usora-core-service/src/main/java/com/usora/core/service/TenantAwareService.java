package com.usora.core.service;

import com.usora.core.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

public abstract class TenantAwareService {

    protected final Logger log = LoggerFactory.getLogger(getClass());
    protected final JdbcTemplate jdbcTemplate;

    protected TenantAwareService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    protected String getCurrentTenantId() {
        var tenantId = TenantContext.getCurrentTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant context available");
        }
        return tenantId;
    }

    protected String qualifyTable(String tableName) {
        var tenantId = getCurrentTenantId();
        return "tenant_" + tenantId + "." + tableName;
    }

    protected void executeInTenantSchema(String sql, Object... args) {
        var tenantId = getCurrentTenantId();
        jdbcTemplate.execute("SET search_path TO tenant_" + tenantId);
        jdbcTemplate.update(sql, args);
        jdbcTemplate.execute("SET search_path TO public");
    }
}
