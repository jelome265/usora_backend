package com.usora.notification.aspect;

import com.usora.notification.security.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;

@Slf4j
@Aspect
@Component
public class TenantAuditAspect {

    @Pointcut("execution(* com.usora.notification.service.DomainService.*(..))")
    public void domainServiceMethods() {}

    @Before("domainServiceMethods()")
    public void auditTenantOperation(JoinPoint joinPoint) {
        var tenantId = TenantContext.getCurrentTenantId();
        var methodName = joinPoint.getSignature().toShortString();

        if (tenantId != null) {
            log.debug("Tenant {} executing: {}", tenantId, methodName);
        }
    }
}
