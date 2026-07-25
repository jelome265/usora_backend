package com.usora.notification.aspect;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;

@Slf4j
@Aspect
@Component
public class LoggingAspect {

    @Pointcut("execution(* com.usora.notification.service.*.*(..))")
    public void serviceMethods() {}

    @Pointcut("execution(* com.usora.notification.controller.*.*(..))")
    public void controllerMethods() {}

    @Around("serviceMethods() || controllerMethods()")
    public Object logMethodExecution(ProceedingJoinPoint joinPoint) throws Throwable {
        var methodName = joinPoint.getSignature().toShortString();
        log.debug("Entering {}", methodName);

        var startTime = System.currentTimeMillis();
        try {
            var result = joinPoint.proceed();
            var duration = System.currentTimeMillis() - startTime;
            log.debug("Exiting {} ({} ms)", methodName, duration);
            return result;
        } catch (Exception e) {
            log.error("Exception in {}: {}", methodName, e.getMessage());
            throw e;
        }
    }
}
