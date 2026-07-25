package com.usora.identity.aspect;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Slf4j
@Aspect
@Component
public class LoggingAspect {

    @Pointcut("execution(public * com.usora.identity.service.*.*(..))")
    public void serviceMethods() {}

    @Pointcut("execution(public * com.usora.identity.controller.*.*(..))")
    public void controllerMethods() {}

    @Around("serviceMethods() || controllerMethods()")
    public Object logMethodCall(ProceedingJoinPoint joinPoint) throws Throwable {
        var className = joinPoint.getTarget().getClass().getSimpleName();
        var methodName = joinPoint.getSignature().getName();
        var args = Arrays.toString(joinPoint.getArgs());

        log.debug("Entering {}.{}() with args: {}", className, methodName, args);

        long start = System.currentTimeMillis();
        try {
            var result = joinPoint.proceed();
            long elapsed = System.currentTimeMillis() - start;
            log.debug("Exiting {}.{}() completed in {}ms", className, methodName, elapsed);
            return result;
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("Exception in {}.{}() after {}ms: {}", className, methodName, elapsed, e.getMessage());
            throw e;
        }
    }
}
