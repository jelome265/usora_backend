package com.usora.tenant.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.UUID;

@Component
public class TenantInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(TenantInterceptor.class);

    private final JwtTokenProvider jwtTokenProvider;

    public TenantInterceptor(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        try {
            String tenantId = request.getHeader("X-Tenant-ID");
            String authHeader = request.getHeader("Authorization");

            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                String token = authHeader.substring(7);
                if (jwtTokenProvider.validateToken(token)) {
                    String jwtTenantId = jwtTokenProvider.extractTenantId(token);
                    if (tenantId == null && jwtTenantId != null) {
                        tenantId = jwtTenantId;
                    }
                }
            }

            if (tenantId != null) {
                MDC.put("tenantId", tenantId);

                TenantContext context = TenantContext.get();
                if (context != null) {
                    context.setCurrentTenantId(UUID.fromString(tenantId));
                }
            }

            MDC.put("requestUri", request.getRequestURI());
            MDC.put("httpMethod", request.getMethod());

            return true;
        } catch (Exception e) {
            log.warn("Failed to process tenant context: {}", e.getMessage());
            return true;
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        TenantContext.clear();
        MDC.clear();
    }
}
