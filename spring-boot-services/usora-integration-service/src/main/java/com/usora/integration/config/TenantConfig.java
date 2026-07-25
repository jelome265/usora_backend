package com.usora.integration.config;

import com.usora.integration.security.TenantContext;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.io.IOException;

@Configuration
public class TenantConfig {

    @Bean
    public FilterRegistrationBean<TenantContextFilter> tenantContextFilter() {
        FilterRegistrationBean<TenantContextFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new TenantContextFilter());
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }

    static class TenantContextFilter implements Filter {
        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            try {
                if (request instanceof HttpServletRequest httpRequest) {
                    String tenantId = httpRequest.getHeader("X-Tenant-Id");
                    if (tenantId != null && !tenantId.isBlank()) {
                        TenantContext.setCurrentTenant(tenantId);
                    }
                }
                chain.doFilter(request, response);
            } finally {
                TenantContext.clear();
            }
        }
    }
}
