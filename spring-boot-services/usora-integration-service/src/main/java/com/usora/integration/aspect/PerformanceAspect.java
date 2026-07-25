package com.usora.integration.aspect;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Aspect
@Component
public class PerformanceAspect {

    private final MeterRegistry meterRegistry;

    public PerformanceAspect(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Around("execution(* com.usora.integration.service..*(..))")
    public Object measureServiceTime(ProceedingJoinPoint joinPoint) throws Throwable {
        return measure(joinPoint, "service");
    }

    @Around("execution(* com.usora.integration.client..*(..))")
    public Object measureClientTime(ProceedingJoinPoint joinPoint) throws Throwable {
        return measure(joinPoint, "client");
    }

    @Around("execution(* com.usora.integration.controller..*(..))")
    public Object measureControllerTime(ProceedingJoinPoint joinPoint) throws Throwable {
        return measure(joinPoint, "controller");
    }

    private Object measure(ProceedingJoinPoint joinPoint, String layer) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String className = signature.getDeclaringType().getSimpleName();
        String methodName = signature.getName();

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            return joinPoint.proceed();
        } finally {
            sample.stop(Timer.builder("integration." + layer + ".latency")
                    .description("Method execution time")
                    .tags("class", className, "method", methodName, "layer", layer)
                    .publishPercentiles(0.5, 0.95, 0.99, 0.999)
                    .publishPercentileHistogram()
                    .minimumExpectedValue(Duration.ofMillis(1))
                    .maximumExpectedValue(Duration.ofSeconds(30))
                    .register(meterRegistry));
        }
    }
}
