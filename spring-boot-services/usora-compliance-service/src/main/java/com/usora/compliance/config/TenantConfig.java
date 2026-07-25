package com.usora.compliance.config;

import com.usora.compliance.security.TenantContext;
import com.usora.compliance.security.TenantInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Configuration
public class TenantConfig implements WebMvcConfigurer {

    private static final Map<String, List<String>> JURISDICTION_TENANTS = new HashMap<>();

    static {
        JURISDICTION_TENANTS.put("eu_gdpr", List.of("tenant_eu_1", "tenant_eu_2"));
        JURISDICTION_TENANTS.put("us_aml", List.of("tenant_us_1", "tenant_us_2"));
        JURISDICTION_TENANTS.put("uk_aml", List.of("tenant_uk_1"));
        JURISDICTION_TENANTS.put("singapore_mas", List.of("tenant_sg_1"));
        JURISDICTION_TENANTS.put("uae_central_bank", List.of("tenant_uae_1"));
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new TenantInterceptor());
    }

    public static List<String> getTenantsForJurisdiction(String jurisdiction) {
        return JURISDICTION_TENANTS.getOrDefault(jurisdiction, List.of());
    }

    public static List<String> getAllJurisdictions() {
        return List.of("eu_gdpr", "us_aml", "uk_aml", "singapore_mas", "uae_central_bank");
    }
}
