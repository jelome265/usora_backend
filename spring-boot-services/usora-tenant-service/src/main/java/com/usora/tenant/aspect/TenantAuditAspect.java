package com.usora.tenant.aspect;

import com.usora.tenant.security.TenantContext;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

@Aspect
@Component
public class TenantAuditAspect {

    private static final Logger auditLog = LoggerFactory.getLogger("AUDIT_LOG");

    @Pointcut("execution(* com.usora.tenant.service.DomainService.onboardTenant(..))")
    public void onboardOperation() {}

    @Pointcut("execution(* com.usora.tenant.service.DomainService.offboardTenant(..))")
    public void offboardOperation() {}

    @Pointcut("execution(* com.usora.tenant.service.DomainService.suspendTenant(..))")
    public void suspendOperation() {}

    @Pointcut("execution(* com.usora.tenant.service.DomainService.resumeTenant(..))")
    public void resumeOperation() {}

    @Pointcut("execution(* com.usora.tenant.service.DomainService.updateConfig(..))")
    public void configOperation() {}

    @Before("onboardOperation() || offboardOperation() || suspendOperation() || resumeOperation() || configOperation()")
    public void auditBefore(JoinPoint joinPoint) {
        String methodName = joinPoint.getSignature().getName();
        String userId = TenantContext.getCurrentUserId();
        UUID tenantId = TenantContext.getCurrentTenantId();

        String args = Arrays.toString(joinPoint.getArgs());
        String tenantIdFromArgs = joinPoint.getArgs().length > 0 ? joinPoint.getArgs()[0].toString() : null;

        auditLog.info("AUDIT - User: {} | Tenant: {} | Action: {} | Args: {} | Timestamp: {}",
                userId, tenantIdFromArgs != null ? tenantIdFromArgs : tenantId,
                methodName, args, Instant.now());
    }

    @AfterReturning(pointcut = "onboardOperation() || offboardOperation() || suspendOperation() || resumeOperation() || configOperation()",
            returning = "result")
    public void auditAfterSuccess(JoinPoint joinPoint, Object result) {
        String methodName = joinPoint.getSignature().getName();
        auditLog.info("AUDIT - Action: {} completed successfully | Result: {} | Timestamp: {}",
                methodName, result != null ? result.toString() : "void", Instant.now());
    }

    @AfterThrowing(pointcut = "onboardOperation() || offboardOperation() || suspendOperation() || resumeOperation() || configOperation()",
            throwing = "exception")
    public void auditAfterFailure(JoinPoint joinPoint, Exception exception) {
        String methodName = joinPoint.getSignature().getName();
        auditLog.error("AUDIT - Action: {} failed | Error: {} | Timestamp: {}",
                methodName, exception.getMessage(), Instant.now());
    }
}
