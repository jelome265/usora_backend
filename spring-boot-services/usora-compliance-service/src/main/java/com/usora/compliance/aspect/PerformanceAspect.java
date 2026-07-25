package com.usora.compliance.aspect;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Aspect
@Component
public class PerformanceAspect {

    private final MeterRegistry meterRegistry;

    public PerformanceAspect(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Pointcut("execution(public * com.usora.compliance.service.DomainService.*(..))")
    public void domainServiceMethods() {}

    @Around("domainServiceMethods()")
    public Object measurePerformance(ProceedingJoinPoint joinPoint) throws Throwable {
        var methodName = joinPoint.getSignature().getName();
        var className = joinPoint.getTarget().getClass().getSimpleName();

        var timer = Timer.builder("compliance_service_method_duration")
                .tag("class", className)
                .tag("method", methodName)
                .register(meterRegistry);

        var start = System.nanoTime();
        try {
            return joinPoint.proceed();
        } finally {
            var duration = System.nanoTime() - start;
            timer.record(duration, TimeUnit.NANOSECONDS);
        }
    }
}
