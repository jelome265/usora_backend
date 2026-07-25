package com.usora.integration.security;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@Order(2)
public class TenantInterceptor implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        try {
            if (request instanceof HttpServletRequest httpRequest) {
                String tenantId = httpRequest.getHeader("X-Tenant-Id");
                if (tenantId == null || tenantId.isBlank()) {
                    tenantId = extractTenantFromPath(httpRequest.getRequestURI());
                }

                if (tenantId != null && !tenantId.isBlank()) {
                    TenantContext.setCurrentTenant(tenantId);
                }

                String userId = httpRequest.getHeader("X-User-Id");
                if (userId != null && !userId.isBlank()) {
                    TenantContext.setCurrentUserId(userId);
                }
            }

            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private String extractTenantFromPath(String path) {
        if (path == null) return null;

        String[] segments = path.split("/");
        for (int i = 0; i < segments.length - 1; i++) {
            if ("webhooks".equals(segments[i]) && i + 1 < segments.length) {
                return segments[i + 1];
            }
        }
        return null;
    }
}
