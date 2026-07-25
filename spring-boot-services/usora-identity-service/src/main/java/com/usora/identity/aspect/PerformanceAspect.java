package com.usora.identity.aspect;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class PerformanceAspect {

    private final MeterRegistry meterRegistry;

    @Pointcut("execution(public * com.usora.identity.service.DomainService.*(..))")
    public void domainServiceMethods() {}

    @Pointcut("execution(public * com.usora.identity.security.PermissionEvaluator.*(..))")
    public void permissionEvaluatorMethods() {}

    @Around("domainServiceMethods()")
    public Object measureDomainService(ProceedingJoinPoint joinPoint) throws Throwable {
        var methodName = joinPoint.getSignature().getName();
        var className = joinPoint.getTarget().getClass().getSimpleName();

        var sample = Timer.start(meterRegistry);
        try {
            return joinPoint.proceed();
        } finally {
            sample.stop(Timer.builder("identity_service_duration_seconds")
                    .tag("class", className)
                    .tag("method", methodName)
                    .register(meterRegistry));
        }
    }

    @Around("permissionEvaluatorMethods()")
    public Object measurePolicyEvaluation(ProceedingJoinPoint joinPoint) throws Throwable {
        var sample = Timer.start(meterRegistry);
        try {
            return joinPoint.proceed();
        } finally {
            sample.stop(Timer.builder("identity_policy_eval_duration_seconds")
                    .tag("evaluator", "opa")
                    .register(meterRegistry));
        }
    }
}
