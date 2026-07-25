package com.usora.notification.service;

import com.usora.notification.entity.TenantEntity;
import com.usora.notification.exception.BusinessException.NotificationFailureException;
import com.usora.notification.repository.TenantRepository;
import com.usora.notification.security.TenantContext;
import com.usora.notification.util.EncryptionUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TenantAwareService {

    private final TenantRepository tenantRepository;
    private final EncryptionUtil encryptionUtil;

    public TenantEntity getCurrentTenantConfig() {
        String tenantId = TenantContext.getCurrentTenantId();
        if (tenantId == null) {
            throw new NotificationFailureException("No tenant context available");
        }
        return tenantRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new NotificationFailureException(
                        "Tenant configuration not found: " + tenantId));
    }

    public TenantEntity getTenantConfig(String tenantId) {
        return tenantRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new NotificationFailureException(
                        "Tenant configuration not found: " + tenantId));
    }

    public String decryptProviderSecret(String encryptedSecret) {
        return encryptionUtil.decrypt(encryptedSecret);
    }
}
