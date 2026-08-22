package com.usora.tenant.config;

import com.usora.tenant.security.TenantAwareDataSource;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * C7 / Postgres row-level security — see usora-core-service's identically
 * named class for the full rationale. Wraps Spring Boot's normal
 * auto-configured DataSource with TenantAwareDataSource; Flyway is
 * unaffected (see application.yml's spring.flyway.* split).
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
