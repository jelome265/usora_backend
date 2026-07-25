package com.usora.compliance.aspect;

import com.usora.compliance.security.TenantContext;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class TenantAuditAspect {

    private static final Logger log = LoggerFactory.getLogger(TenantAuditAspect.class);

    @Pointcut("within(@org.springframework.stereotype.Service *) && execution(public * *(..))")
    public void serviceMethods() {}

    @Before("serviceMethods()")
    public void logTenantContext(JoinPoint joinPoint) {
        var tenantId = TenantContext.getCurrentTenant();
        var methodName = joinPoint.getSignature().getName();
        if (tenantId != null) {
            log.debug("Tenant {} invoking method {}", tenantId, methodName);
        }
    }

    @AfterThrowing(pointcut = "serviceMethods()", throwing = "ex")
    public void logTenantException(JoinPoint joinPoint, Exception ex) {
        var tenantId = TenantContext.getCurrentTenant();
        var methodName = joinPoint.getSignature().getName();
        log.error("Tenant {} exception in method {}: {}", tenantId, methodName, ex.getMessage());
    }
}
