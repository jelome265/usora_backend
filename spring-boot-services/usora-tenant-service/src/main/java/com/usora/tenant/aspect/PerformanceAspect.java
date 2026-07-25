package com.usora.tenant.aspect;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Aspect
@Component
public class PerformanceAspect {

    private static final Logger log = LoggerFactory.getLogger(PerformanceAspect.class);
    private final MeterRegistry meterRegistry;

    public PerformanceAspect(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Pointcut("within(com.usora.tenant.service..*)")
    public void serviceMethods() {}

    @Pointcut("within(com.usora.tenant.repository..*)")
    public void repositoryMethods() {}

    @Around("serviceMethods() || repositoryMethods()")
    public Object measureExecutionTime(ProceedingJoinPoint joinPoint) throws Throwable {
        String className = joinPoint.getTarget().getClass().getSimpleName();
        String methodName = joinPoint.getSignature().getName();
        String metricName = "tenant." + className + "." + methodName;

        Timer.Sample sample = Timer.start(meterRegistry);
        long startNanos = System.nanoTime();

        try {
            Object result = joinPoint.proceed();
            long durationNanos = System.nanoTime() - startNanos;
            long durationMs = TimeUnit.NANOSECONDS.toMillis(durationNanos);

            sample.stop(Timer.builder(metricName)
                    .description("Execution time of " + className + "." + methodName)
                    .tag("class", className)
                    .tag("method", methodName)
                    .register(meterRegistry));

            if (durationMs > 1000) {
                log.warn("SLOW OPERATION: {}.{} took {}ms", className, methodName, durationMs);
            }

            return result;
        } catch (Exception e) {
            long durationNanos = System.nanoTime() - startNanos;
            meterRegistry.counter(metricName + ".errors",
                    "class", className, "method", methodName).increment();
            throw e;
        }
    }
}
