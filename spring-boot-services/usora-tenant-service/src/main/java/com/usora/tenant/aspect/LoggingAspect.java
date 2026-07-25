package com.usora.tenant.aspect;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Aspect
@Component
public class LoggingAspect {

    @Pointcut("within(@org.springframework.web.bind.annotation.RestController *)")
    public void controllerMethods() {}

    @Pointcut("within(com.usora.tenant.service..*)")
    public void serviceMethods() {}

    @Around("controllerMethods() || serviceMethods()")
    public Object logMethodEntryExit(ProceedingJoinPoint joinPoint) throws Throwable {
        Logger log = LoggerFactory.getLogger(joinPoint.getSignature().getDeclaringType());
        String methodName = joinPoint.getSignature().getName();
        String className = joinPoint.getTarget().getClass().getSimpleName();

        String tenantId = MDC.get("tenantId");
        if (tenantId != null) {
            log.debug("Entering {}.{} with tenantId={}", className, methodName, tenantId);
        } else {
            log.debug("Entering {}.{}", className, methodName);
        }

        long start = System.currentTimeMillis();
        try {
            Object result = joinPoint.proceed();
            long duration = System.currentTimeMillis() - start;
            log.debug("Exiting {}.{} completed in {}ms", className, methodName, duration);
            return result;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("Exception in {}.{} after {}ms: {}", className, methodName, duration, e.getMessage());
            throw e;
        }
    }
}
