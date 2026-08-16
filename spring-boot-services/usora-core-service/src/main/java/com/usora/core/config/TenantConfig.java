package com.usora.core.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Configuration
public class TenantConfig {

    @Value("${spring.datasource.url}")
    private String baseUrl;

    @Value("${spring.datasource.username}")
    private String username;

    @Value("${spring.datasource.password}")
    private String password;

    @Value("${spring.datasource.hikari.maximum-pool-size:20}")
    private int maxPoolSize;

    @Value("${spring.datasource.hikari.minimum-idle:5}")
    private int minIdle;

    @Value("${spring.datasource.hikari.connection-timeout:30000}")
    private long connectionTimeout;

    @Value("${spring.datasource.hikari.idle-timeout:600000}")
    private long idleTimeout;

    @Value("${spring.datasource.hikari.max-lifetime:1800000}")
    private long maxLifetime;

    private final Map<String, DataSource> tenantDataSources = new ConcurrentHashMap<>();

    @Bean
    @Primary
    public DataSource dataSource() {
        var config = new HikariConfig();
        config.setJdbcUrl(baseUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(maxPoolSize);
        config.setMinimumIdle(minIdle);
        config.setConnectionTimeout(connectionTimeout);
        config.setIdleTimeout(idleTimeout);
        config.setMaxLifetime(maxLifetime);
        // config.setSchema("public");
        config.setPoolName("default-pool");
        return new HikariDataSource(config);
    }

    public DataSource createTenantDataSource(String tenantId) {
        return tenantDataSources.computeIfAbsent(tenantId, tid -> {
            var config = new HikariConfig();
            var schema = "tenant_" + tid;
            config.setJdbcUrl(baseUrl + "&currentSchema=" + schema);
            config.setUsername(username);
            config.setPassword(password);
            config.setMaximumPoolSize(maxPoolSize);
            config.setMinimumIdle(minIdle);
            config.setConnectionTimeout(connectionTimeout);
            config.setIdleTimeout(idleTimeout);
            config.setMaxLifetime(maxLifetime);
            config.setSchema(schema);
            config.setPoolName("tenant-" + tid + "-pool");
            return new HikariDataSource(config);
        });
    }
}
