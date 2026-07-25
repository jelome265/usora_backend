package com.usora.identity.service;

import com.usora.identity.entity.TenantEntity;
import com.usora.identity.exception.BusinessException;
import com.usora.identity.repository.TenantRepository;
import com.usora.identity.security.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public abstract class TenantAwareService {

    protected final TenantRepository tenantRepository;

    protected TenantEntity getCurrentTenant() {
        var tenantId = TenantContext.getContext().getTenantId();
        if (tenantId == null) {
            throw BusinessException.badRequest("Tenant context is required");
        }
        return tenantRepository.findActiveById(UUID.fromString(tenantId))
                .orElseThrow(() -> BusinessException.notFound("Tenant not found: " + tenantId));
    }

    protected TenantEntity getTenantById(String tenantId) {
        return tenantRepository.findActiveById(UUID.fromString(tenantId))
                .orElseThrow(() -> BusinessException.notFound("Tenant not found: " + tenantId));
    }

    protected void validateTenantAccess(String targetTenantId) {
        var currentTenantId = TenantContext.getContext().getTenantId();
        if (currentTenantId != null && !currentTenantId.equals(targetTenantId)) {
            throw BusinessException.forbidden("Cross-tenant access is not permitted");
        }
    }
}
