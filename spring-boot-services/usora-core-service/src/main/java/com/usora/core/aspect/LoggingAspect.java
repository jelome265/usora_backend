package com.usora.core.aspect;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Aspect
@Component
public class LoggingAspect {

    @Pointcut("within(com.usora.core.service..*) || within(com.usora.core.controller..*)")
    public void serviceLayer() {}

    @Around("serviceLayer()")
    public Object logMethodEntryExit(ProceedingJoinPoint joinPoint) throws Throwable {
        var log = LoggerFactory.getLogger(joinPoint.getTarget().getClass());
        var methodName = joinPoint.getSignature().toShortString();
        var args = Arrays.toString(joinPoint.getArgs());

        log.info("Enter: {} with args={}", methodName, args);
        var start = System.currentTimeMillis();

        try {
            var result = joinPoint.proceed();
            var duration = System.currentTimeMillis() - start;
            log.info("Exit: {} returned in {}ms", methodName, duration);
            return result;
        } catch (Exception e) {
            var duration = System.currentTimeMillis() - start;
            log.error("Exception: {} failed after {}ms: {}", methodName, duration, e.getMessage());
            throw e;
        }
    }
}
