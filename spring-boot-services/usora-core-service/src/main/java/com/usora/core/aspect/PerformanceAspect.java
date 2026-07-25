package com.usora.core.aspect;

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

    @Pointcut("within(com.usora.core.service.DomainService)")
    public void domainService() {}

    @Around("domainService()")
    public Object measureExecutionTime(ProceedingJoinPoint joinPoint) throws Throwable {
        var methodName = joinPoint.getSignature().getName();
        var timer = Timer.builder("orchestrator_method_duration_seconds")
                .tag("method", methodName)
                .tag("class", joinPoint.getTarget().getClass().getSimpleName())
                .register(meterRegistry);

        var sample = Timer.start();
        try {
            return joinPoint.proceed();
        } finally {
            sample.stop(timer);
        }
    }
}
