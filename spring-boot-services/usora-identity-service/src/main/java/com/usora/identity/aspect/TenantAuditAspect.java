package com.usora.identity.aspect;

import com.usora.identity.event.DomainEventPublisher;
import com.usora.identity.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class TenantAuditAspect {

    private final DomainEventPublisher eventPublisher;

    @Pointcut("execution(public * com.usora.identity.service.DomainService.createUser(..))")
    public void createUserOperation() {}

    @Pointcut("execution(public * com.usora.identity.service.DomainService.updateUserRoles(..))")
    public void updateUserRolesOperation() {}

    @AfterReturning(pointcut = "createUserOperation()")
    public void auditUserCreation(JoinPoint joinPoint) {
        var context = TenantContext.getContext();
        eventPublisher.publishUserEvent("audit.user.created",
                context.getUserId(),
                context.getTenantId(),
                Map.of("operation", "create_user", "tenant_id", context.getTenantId()));
    }

    @AfterReturning(pointcut = "updateUserRolesOperation()")
    public void auditRoleUpdate(JoinPoint joinPoint) {
        var context = TenantContext.getContext();
        eventPublisher.publishUserEvent("audit.user.roles.updated",
                context.getUserId(),
                context.getTenantId(),
                Map.of("operation", "update_roles", "tenant_id", context.getTenantId()));
    }
}
