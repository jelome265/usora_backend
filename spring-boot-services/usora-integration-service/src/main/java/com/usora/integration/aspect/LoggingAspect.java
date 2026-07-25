package com.usora.integration.aspect;

import com.usora.integration.security.TenantContext;
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

    private static final Logger log = LoggerFactory.getLogger(LoggingAspect.class);

    @Pointcut("within(@org.springframework.web.bind.annotation.RestController *)")
    public void controllerMethods() {}

    @Pointcut("within(com.usora.integration.service..*)")
    public void serviceMethods() {}

    @Pointcut("within(com.usora.integration.client..*)")
    public void clientMethods() {}

    @Around("controllerMethods() || serviceMethods() || clientMethods()")
    public Object logAround(ProceedingJoinPoint joinPoint) throws Throwable {
        String className = joinPoint.getSignature().getDeclaringTypeName();
        String methodName = joinPoint.getSignature().getName();
        String correlationId = MDC.get("correlationId");

        try {
            String tenantId = TenantContext.getCurrentTenant();
            MDC.put("tenantId", tenantId);
            MDC.put("className", className);
            MDC.put("methodName", methodName);

            if (log.isDebugEnabled()) {
                log.debug("Entering {}.{} with arguments: {}",
                        className, methodName, Arrays.toString(joinPoint.getArgs()));
            }

            long start = System.currentTimeMillis();
            Object result = joinPoint.proceed();
            long duration = System.currentTimeMillis() - start;

            if (log.isDebugEnabled()) {
                log.debug("Exiting {}.{} completed in {}ms", className, methodName, duration);
            }
            log.info("Method {}.{} completed in {}ms", className, methodName, duration);

            return result;
        } catch (Exception e) {
            log.error("Exception in {}.{}: {}", className, methodName, e.getMessage());
            throw e;
        } finally {
            MDC.remove("tenantId");
            MDC.remove("className");
            MDC.remove("methodName");
        }
    }
}
