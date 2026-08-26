package com.usora.integration.config;

import com.usora.integration.security.TenantAwareDataSource;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * C7 / Postgres row-level security — see usora-core-service's identically
 * named class for the full rationale.
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
