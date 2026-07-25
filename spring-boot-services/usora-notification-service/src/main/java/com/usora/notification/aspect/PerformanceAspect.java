package com.usora.notification.aspect;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Aspect
@Component
@RequiredArgsConstructor
public class PerformanceAspect {

    private final MeterRegistry meterRegistry;

    @Pointcut("execution(* com.usora.notification.service.DomainService.sendNotification(..))")
    public void sendNotification() {}

    @Pointcut("execution(* com.usora.notification.service.DomainService.NotificationDeliveryService.*(..))")
    public void channelDelivery() {}

    @Around("sendNotification()")
    public Object measureSendNotification(ProceedingJoinPoint joinPoint) throws Throwable {
        var timer = Timer.builder("notification.send.duration")
                .description("Time taken to send a notification")
                .register(meterRegistry);

        return timer.record(() -> {
            try {
                return joinPoint.proceed();
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Around("channelDelivery()")
    public Object measureChannelDelivery(ProceedingJoinPoint joinPoint) throws Throwable {
        var methodName = joinPoint.getSignature().getName();
        var timer = Timer.builder("notification.channel.delivery")
                .tag("method", methodName)
                .description("Time taken for channel-specific delivery")
                .register(meterRegistry);

        var start = System.nanoTime();
        try {
            return joinPoint.proceed();
        } finally {
            timer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }
}
