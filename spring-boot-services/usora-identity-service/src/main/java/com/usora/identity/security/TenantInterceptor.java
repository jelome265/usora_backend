package com.usora.identity.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

@Slf4j
@Component
public class TenantInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        var tenantId = request.getHeader("X-Tenant-Id");
        if (tenantId == null || tenantId.isBlank()) {
            tenantId = request.getParameter("tenant_id");
        }

        var context = TenantContext.getContext();
        if (tenantId != null && !tenantId.isBlank()) {
            context.setTenantId(tenantId);
            log.debug("Tenant context set to: {}", tenantId);
        }

        var clientId = request.getHeader("X-Client-Id");
        if (clientId != null && !clientId.isBlank()) {
            context.setClientId(clientId);
        }

        return true;
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler,
                           @Nullable ModelAndView modelAndView) {
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                @Nullable Exception ex) {
        TenantContext.clear();
    }
}
