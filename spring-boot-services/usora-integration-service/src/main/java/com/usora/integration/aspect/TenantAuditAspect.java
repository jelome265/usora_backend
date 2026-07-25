package com.usora.integration.aspect;

import com.usora.integration.security.TenantContext;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Aspect
@Component
public class TenantAuditAspect {

    private static final Logger auditLog = LoggerFactory.getLogger("TENANT_AUDIT");

    @Before("@annotation(org.springframework.web.bind.annotation.PostMapping) || "
            + "@annotation(org.springframework.web.bind.annotation.PutMapping) || "
            + "@annotation(org.springframework.web.bind.annotation.DeleteMapping) || "
            + "@annotation(org.springframework.web.bind.annotation.PatchMapping)")
    public void auditMutatingOperation(JoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String methodName = signature.getMethod().getName();
        String className = signature.getDeclaringType().getSimpleName();
        String tenantId = TenantContext.getCurrentTenant();
        String userId = TenantContext.getCurrentUserId();

        auditLog.info("AUDIT|{}|{}|{}|{}|{}|{}",
                Instant.now(),
                tenantId != null ? tenantId : "system",
                userId != null ? userId : "anonymous",
                className + "." + methodName,
                "MUTATE",
                UUID.randomUUID().toString().substring(0, 8));
    }

    @AfterReturning("@annotation(org.springframework.web.bind.annotation.GetMapping)")
    public void auditReadOperation(JoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String methodName = signature.getMethod().getName();
        String className = signature.getDeclaringType().getSimpleName();
        String tenantId = TenantContext.getCurrentTenant();
        String userId = TenantContext.getCurrentUserId();

        auditLog.debug("AUDIT|{}|{}|{}|{}|{}|{}",
                Instant.now(),
                tenantId != null ? tenantId : "system",
                userId != null ? userId : "anonymous",
                className + "." + methodName,
                "READ",
                UUID.randomUUID().toString().substring(0, 8));
    }
}
