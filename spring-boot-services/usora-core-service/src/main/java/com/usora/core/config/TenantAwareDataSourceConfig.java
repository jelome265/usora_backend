package com.usora.core.config;

import com.usora.core.security.TenantAwareDataSource;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * C7 / Postgres row-level security: wraps Spring Boot's normal
 * auto-configured (Hikari) DataSource with TenantAwareDataSource, which
 * is what actually makes the RLS policies in V3__row_level_security.sql
 * do anything — see that class's javadoc for the full mechanism.
 *
 * This is deliberately the ONLY thing this class does. Spring Boot's
 * DataSourceAutoConfiguration still builds the real (Hikari-backed)
 * DataSource from spring.datasource.* exactly as before; this class just
 * wraps it and marks the wrapped bean @Primary so JPA/Hibernate and any
 * JdbcTemplate use the tenant-aware version, not the raw pool directly.
 * Flyway is unaffected — Spring Boot's Flyway auto-configuration builds
 * its own separate connection from spring.flyway.* (see
 * application.yml), which intentionally never goes through this wrapper.
 */
@Configuration
@AutoConfigureBefore(DataSourceAutoConfiguration.class)
@EnableConfigurationProperties(DataSourceProperties.class)
public class TenantAwareDataSourceConfig {

    @Bean
    @Primary
    public DataSource dataSource(DataSourceProperties dataSourceProperties) {
        DataSource raw = dataSourceProperties.initializeDataSourceBuilder().build();
        return new TenantAwareDataSource(raw);
    }
}
