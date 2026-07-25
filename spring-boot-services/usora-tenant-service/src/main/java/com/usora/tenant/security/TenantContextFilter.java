package com.usora.tenant.security;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Component
@Order(1)
public class TenantContextFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(TenantContextFilter.class);
    private final JwtTokenProvider jwtTokenProvider;

    public TenantContextFilter(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse,
                         FilterChain chain) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) servletRequest;

        String tenantId = request.getHeader("X-Tenant-ID");
        String authHeader = request.getHeader("Authorization");

        String userId = null;
        List<String> roles = List.of();

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            if (jwtTokenProvider.validateToken(token)) {
                String jwtTenantId = jwtTokenProvider.extractTenantId(token);
                if (tenantId == null && jwtTenantId != null) {
                    tenantId = jwtTenantId;
                }
                userId = jwtTokenProvider.extractUserId(token);
                roles = jwtTokenProvider.extractRoles(token);
            }
        }

        if (tenantId != null) {
            MDC.put("tenantId", tenantId);
        }
        MDC.put("requestUri", request.getRequestURI());
        MDC.put("httpMethod", request.getMethod());

        TenantContext context = TenantContext.builder()
                .userId(userId)
                .currentTenantId(tenantId != null ? UUID.fromString(tenantId) : null)
                .roles(roles)
                .build();

        try {
            TenantContext.runWith(context, () -> {
                try {
                    chain.doFilter(servletRequest, servletResponse);
                } catch (IOException | ServletException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException) {
                throw (IOException) e.getCause();
            }
            if (e.getCause() instanceof ServletException) {
                throw (ServletException) e.getCause();
            }
            throw e;
        } finally {
            MDC.clear();
        }
    }
}
