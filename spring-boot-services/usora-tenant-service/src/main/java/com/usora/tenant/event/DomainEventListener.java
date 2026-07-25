package com.usora.tenant.event;

import com.usora.tenant.event.DomainEventPublisher.TenantOffboardedEvent;
import com.usora.tenant.event.DomainEventPublisher.TenantProvisionedEvent;
import com.usora.tenant.event.DomainEventPublisher.TenantSuspendedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class DomainEventListener {

    private static final Logger log = LoggerFactory.getLogger(DomainEventListener.class);

    private final DomainEventPublisher eventPublisher;

    public DomainEventListener(DomainEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @EventListener
    public void handleTenantProvisioned(TenantProvisionedEvent event) {
        try (var ignored = MDC.putCloseable("tenantId", event.tenantId().toString())) {
            log.info("Tenant provisioned: {} ({}) - plan={}, region={}",
                    event.name(), event.tenantId(), event.plan(), event.region());
            // Additional post-provisioning logic: create K8s namespace, configure network policies, etc.
        }
    }

    @EventListener
    public void handleTenantSuspended(TenantSuspendedEvent event) {
        try (var ignored = MDC.putCloseable("tenantId", event.tenantId().toString())) {
            log.warn("Tenant suspended: {} ({}) - reason: {}",
                    event.name(), event.tenantId(), event.reason());
            // Additional suspension logic: block ingress, scale down compute
        }
    }

    @EventListener
    public void handleTenantOffboarded(TenantOffboardedEvent event) {
        try (var ignored = MDC.putCloseable("tenantId", event.tenantId().toString())) {
            log.info("Tenant offboarded: {} ({})", event.name(), event.tenantId());
            // Additional offboarding logic: GDPR purge, cleanup resources
        }
    }
}
