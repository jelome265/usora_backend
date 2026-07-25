package com.usora.compliance.aspect;

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

    @Pointcut("execution(public * com.usora.compliance.service.*.*(..))")
    public void serviceMethods() {}

    @Pointcut("execution(public * com.usora.compliance.controller.*.*(..))")
    public void controllerMethods() {}

    @Around("serviceMethods() || controllerMethods()")
    public Object logAround(ProceedingJoinPoint joinPoint) throws Throwable {
        var log = LoggerFactory.getLogger(joinPoint.getTarget().getClass());
        var methodName = joinPoint.getSignature().getName();

        if (log.isDebugEnabled()) {
            log.debug("Entering {}.{} with arguments: {}", joinPoint.getTarget().getClass().getSimpleName(),
                    methodName, Arrays.toString(joinPoint.getArgs()));
        }

        var start = System.currentTimeMillis();
        try {
            var result = joinPoint.proceed();
            var duration = System.currentTimeMillis() - start;

            if (duration > 1000) {
                log.warn("Slow method {} executed in {} ms", methodName, duration);
            } else if (log.isDebugEnabled()) {
                log.debug("Method {} executed in {} ms", methodName, duration);
            }

            return result;
        } catch (Exception e) {
            log.error("Exception in {}: {}", methodName, e.getMessage(), e);
            throw e;
        }
    }
}
