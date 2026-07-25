package com.usora.core.aspect;

import com.usora.core.security.TenantContext;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Aspect
@Component
public class TenantAuditAspect {

    private static final Logger auditLog = LoggerFactory.getLogger("AUDIT_LOGGER");

    @Pointcut("execution(* com.usora.core.service.DomainService.*(..))")
    public void domainServiceMethods() {}

    @AfterReturning(pointcut = "domainServiceMethods()", returning = "result")
    public void auditStateChange(JoinPoint joinPoint, Object result) {
        var methodName = joinPoint.getSignature().getName();
        var tenantId = TenantContext.getCurrentTenantId();
        var args = joinPoint.getArgs();

        auditLog.info("AUDIT: method={}, tenantId={}, args={}, result={}, timestamp={}",
                methodName, tenantId, args, result, Instant.now());
    }
}
