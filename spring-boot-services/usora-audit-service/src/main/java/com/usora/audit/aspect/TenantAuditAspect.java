package com.usora.audit.aspect;

import com.usora.audit.dto.RequestDto.AuditEventRequest;
import com.usora.audit.service.DomainService;
import com.usora.audit.security.TenantContext;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.Instant;

@Aspect
@Component
public class TenantAuditAspect {

    private static final Logger log = LoggerFactory.getLogger(TenantAuditAspect.class);

    private final DomainService domainService;

    public TenantAuditAspect(DomainService domainService) {
        this.domainService = domainService;
    }

    @AfterReturning(pointcut = "@annotation(audited)", returning = "result")
    public void auditMethodCall(JoinPoint joinPoint, Audited audited, Object result) {
        try {
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            String methodName = signature.getName();
            String className = joinPoint.getTarget().getClass().getSimpleName();

            AuditEventRequest request = AuditEventRequest.builder()
                    .timestamp(Instant.now())
                    .actorId(TenantContext.getUserId() != null ? TenantContext.getUserId() : "system")
                    .action(audited.action().isEmpty() ? methodName : audited.action())
                    .resourceType(audited.resourceType().isEmpty() ? className : audited.resourceType())
                    .resourceId(audited.resourceId())
                    .tenantId(TenantContext.getTenantId() != null ? TenantContext.getTenantId() : "default")
                    .outcome("SUCCESS")
                    .severity(audited.severity())
                    .category(audited.category())
                    .build();

            domainService.logEvent(request);
        } catch (Exception e) {
            log.warn("Failed to auto-audit method call: {}", e.getMessage());
        }
    }

    @AfterReturning("@annotation(com.usora.audit.aspect.TenantAuditAspect.Audited)")
    public void auditAnnotatedMethods(JoinPoint joinPoint) {
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    public @interface Audited {
        String action() default "";
        String resourceType() default "";
        String resourceId() default "";
        String severity() default "INFO";
        String category() default "audit";
    }
}
