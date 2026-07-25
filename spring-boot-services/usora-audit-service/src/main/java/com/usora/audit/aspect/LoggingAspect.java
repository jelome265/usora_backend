package com.usora.audit.aspect;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class LoggingAspect {

    @Before("execution(* com.usora.audit.service.*.*(..))")
    public void logBefore(JoinPoint joinPoint) {
        Logger log = LoggerFactory.getLogger(joinPoint.getTarget().getClass());
        log.debug("Entering: {}.{}()", joinPoint.getSignature().getDeclaringTypeName(),
                joinPoint.getSignature().getName());
    }

    @AfterReturning(pointcut = "execution(* com.usora.audit.service.*.*(..))", returning = "result")
    public void logAfterReturning(JoinPoint joinPoint, Object result) {
        Logger log = LoggerFactory.getLogger(joinPoint.getTarget().getClass());
        log.debug("Exiting: {}.{}() with result: {}", joinPoint.getSignature().getDeclaringTypeName(),
                joinPoint.getSignature().getName(), result);
    }

    @AfterThrowing(pointcut = "execution(* com.usora.audit.service.*.*(..))", throwing = "exception")
    public void logAfterThrowing(JoinPoint joinPoint, Throwable exception) {
        Logger log = LoggerFactory.getLogger(joinPoint.getTarget().getClass());
        log.error("Exception in {}.{}(): {}", joinPoint.getSignature().getDeclaringTypeName(),
                joinPoint.getSignature().getName(), exception.getMessage(), exception);
    }
}
