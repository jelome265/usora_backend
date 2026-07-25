package com.usora.tenant.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.usora.tenant.config.TenantConfig;
import com.usora.tenant.dto.ConfigUpdateRequest;
import com.usora.tenant.dto.OnboardRequest;
import com.usora.tenant.dto.SuspendRequest;
import com.usora.tenant.dto.TenantListResponse;
import com.usora.tenant.dto.TenantResponse;
import com.usora.tenant.entity.TenantEntity;
import com.usora.tenant.entity.TenantEntity.TenantStatus;
import com.usora.tenant.event.DomainEventPublisher;
import com.usora.tenant.exception.BusinessException;
import com.usora.tenant.mapper.EntityMapper;
import com.usora.tenant.repository.TenantRepository;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
public class DomainService extends TenantAwareService {

    private final TenantRepository tenantRepository;
    private final EntityMapper entityMapper;
    private final TenantConfig tenantConfig;
    private final DomainEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public DomainService(TenantRepository tenantRepository,
                         EntityMapper entityMapper,
                         TenantConfig tenantConfig,
                         DomainEventPublisher eventPublisher,
                         ObjectMapper objectMapper) {
        this.tenantRepository = tenantRepository;
        this.entityMapper = entityMapper;
        this.tenantConfig = tenantConfig;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    @Transactional
    @Retry(name = "tenantProvisioning")
    @CacheEvict(value = "tenants", allEntries = true)
    public TenantResponse onboardTenant(OnboardRequest request) {
        log.info("Onboarding tenant: {} ({})", request.getName(), request.getDomain());

        if (tenantRepository.existsByDomain(request.getDomain())) {
            throw BusinessException.tenantAlreadyExists(request.getDomain());
        }

        TenantEntity entity = entityMapper.toEntity(request);

        if (request.getFeatures() != null) {
            try {
                entity.setFeatures(objectMapper.writeValueAsString(request.getFeatures()));
            } catch (JsonProcessingException e) {
                throw new BusinessException("INVALID_FEATURES", "Failed to serialize features", e);
            }
        }

        entity.setProvisioningStatus("PENDING");
        entity = tenantRepository.save(entity);

        try {
            provisionInfrastructure(entity);
            entity.setStatus(TenantStatus.ACTIVE);
            entity.setProvisioningStatus("COMPLETED");
            entity = tenantRepository.save(entity);

            eventPublisher.publishTenantProvisioned(entity);
            log.info("Tenant onboarded successfully: {} ({})", entity.getName(), entity.getId());
        } catch (Exception e) {
            entity.setStatus(TenantStatus.PROVISIONING);
            entity.setProvisioningStatus("FAILED:" + e.getMessage());
            tenantRepository.save(entity);
            throw BusinessException.provisioningFailed(entity.getId().toString(), e.getMessage());
        }

        return entityMapper.toResponse(entity);
    }

    @Transactional
    @CacheEvict(value = {"tenants", "tenantById"}, key = "#tenantId")
    public void offboardTenant(UUID tenantId) {
        log.info("Offboarding tenant: {}", tenantId);
        TenantEntity entity = findTenantOrThrow(tenantId);

        if (entity.getStatus() == TenantStatus.DELETED) {
            throw BusinessException.invalidOperation("Tenant is already offboarded");
        }

        try {
            deprovisionInfrastructure(entity);

            if (tenantConfig.getOffboarding().isGdprCompliance()) {
                performGdprPurge(entity);
            }

            entity.setStatus(TenantStatus.DELETED);
            entity.setProvisioningStatus("OFFBOARDED");
            tenantRepository.save(entity);

            eventPublisher.publishTenantOffboarded(entity);
            log.info("Tenant offboarded successfully: {}", tenantId);
        } catch (Exception e) {
            throw BusinessException.offboardingFailed(tenantId.toString(), e.getMessage());
        }
    }

    @Cacheable(value = "tenantById", key = "#tenantId")
    @Transactional(readOnly = true)
    public TenantResponse getTenant(UUID tenantId) {
        TenantEntity entity = findTenantOrThrow(tenantId);
        return entityMapper.toResponse(entity);
    }

    @Transactional(readOnly = true)
    public TenantListResponse listTenants(PageRequest pageRequest, String status, String plan) {
        Page<TenantEntity> page;
        if (status != null && plan != null) {
            page = tenantRepository.findByStatusAndPlan(TenantStatus.valueOf(status.toUpperCase()), plan, pageRequest);
        } else if (status != null) {
            page = tenantRepository.findByStatus(TenantStatus.valueOf(status.toUpperCase()), pageRequest);
        } else if (plan != null) {
            page = tenantRepository.findByPlan(plan, pageRequest);
        } else {
            page = tenantRepository.findAll(pageRequest);
        }

        return TenantListResponse.builder()
                .items(page.getContent().stream()
                        .map(entityMapper::toResponse)
                        .collect(Collectors.toList()))
                .page(page.getNumber())
                .size(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .hasNext(page.hasNext())
                .hasPrevious(page.hasPrevious())
                .build();
    }

    @Transactional
    @CacheEvict(value = {"tenants", "tenantById", "tenantConfig"}, key = "#tenantId")
    public TenantResponse updateConfig(UUID tenantId, ConfigUpdateRequest request) {
        log.info("Updating config for tenant: {}", tenantId);
        TenantEntity entity = findTenantOrThrow(tenantId);

        if (entity.getStatus() != TenantStatus.ACTIVE && entity.getStatus() != TenantStatus.SUSPENDED) {
            throw BusinessException.tenantNotActive(tenantId.toString());
        }

        Map<String, Object> oldConfig = parseJsonConfig(entity.getConfig());
        Map<String, Object> newConfig = new HashMap<>(oldConfig);

        if (request.getConfig() != null) {
            newConfig.putAll(request.getConfig());
        }

        if (request.getMaxUsers() != null) {
            entity.setMaxUsers(request.getMaxUsers());
        }

        if (request.getStorageQuotaBytes() != null) {
            entity.setStorageQuotaBytes(request.getStorageQuotaBytes());
        }

        if (request.getPlan() != null) {
            entity.setPlan(request.getPlan());
        }

        try {
            entity.setConfig(objectMapper.writeValueAsString(newConfig));
        } catch (JsonProcessingException e) {
            throw new BusinessException("INVALID_CONFIG", "Failed to serialize config", e);
        }

        entity = tenantRepository.save(entity);

        eventPublisher.publishTenantConfigUpdated(entity, oldConfig, newConfig);
        log.info("Config updated for tenant: {}", tenantId);

        return entityMapper.toResponse(entity);
    }

    @Transactional
    @CacheEvict(value = {"tenants", "tenantById"}, key = "#tenantId")
    public TenantResponse suspendTenant(UUID tenantId, SuspendRequest request) {
        log.warn("Suspending tenant: {} - reason: {}", tenantId, request.getReason());
        TenantEntity entity = findTenantOrThrow(tenantId);

        if (entity.getStatus() == TenantStatus.SUSPENDED) {
            throw BusinessException.invalidOperation("Tenant is already suspended");
        }

        if (entity.getStatus() != TenantStatus.ACTIVE) {
            throw BusinessException.tenantNotActive(tenantId.toString());
        }

        entity.setStatus(TenantStatus.SUSPENDED);
        entity.setProvisioningStatus("SUSPENDED:" + request.getReason());
        entity = tenantRepository.save(entity);

        eventPublisher.publishTenantSuspended(entity, request.getReason());
        log.info("Tenant suspended: {}", tenantId);

        return entityMapper.toResponse(entity);
    }

    @Transactional
    @CacheEvict(value = {"tenants", "tenantById"}, key = "#tenantId")
    public TenantResponse resumeTenant(UUID tenantId) {
        log.info("Resuming tenant: {}", tenantId);
        TenantEntity entity = findTenantOrThrow(tenantId);

        if (entity.getStatus() != TenantStatus.SUSPENDED) {
            throw BusinessException.invalidOperation("Tenant is not suspended");
        }

        entity.setStatus(TenantStatus.ACTIVE);
        entity.setProvisioningStatus("RESUMED");
        entity = tenantRepository.save(entity);

        eventPublisher.publishTenantResumed(entity);
        log.info("Tenant resumed: {}", tenantId);

        return entityMapper.toResponse(entity);
    }

    private TenantEntity findTenantOrThrow(UUID tenantId) {
        return tenantRepository.findById(tenantId)
                .orElseThrow(() -> BusinessException.tenantNotFound(tenantId.toString()));
    }

    private Map<String, Object> parseJsonConfig(String json) {
        if (json == null || json.isBlank()) return new HashMap<>();
        try {
            return objectMapper.readValue(json, HashMap.class);
        } catch (JsonProcessingException e) {
            return new HashMap<>();
        }
    }

    private void provisionInfrastructure(TenantEntity tenant) {
        String schemaName = tenantConfig.getProvisioning().getSchemaPrefix() + tenant.getId().toString().replace("-", "_");
        log.info("Creating schema: {} for tenant: {}", schemaName, tenant.getId());
        // In production: execute CREATE SCHEMA IF NOT EXISTS via JdbcTemplate

        String namespace = tenantConfig.getProvisioning().getNamespacePrefix() + tenant.getId();
        log.info("Creating K8s namespace: {} for tenant: {}", namespace, tenant.getId());
        // In production: call K8s API to create namespace with ResourceQuota

        log.info("Generating Vault secrets for tenant: {}", tenant.getId());
        // In production: call Vault API to generate DB credentials, API keys

        log.info("Creating Kafka topics for tenant: {}", tenant.getId());
        // In production: create topics with tenant prefix via KafkaAdmin

        log.info("Configuring Redis namespaces for tenant: {}", tenant.getId());
        // In production: pre-warm Redis with tenant config
    }

    private void deprovisionInfrastructure(TenantEntity tenant) {
        String schemaName = tenantConfig.getProvisioning().getSchemaPrefix() + tenant.getId().toString().replace("-", "_");
        log.info("Dropping schema: {} for tenant: {}", schemaName, tenant.getId());

        String namespace = tenantConfig.getProvisioning().getNamespacePrefix() + tenant.getId();
        log.info("Deleting K8s namespace: {} for tenant: {}", namespace, tenant.getId());

        log.info("Revoking Vault secrets for tenant: {}", tenant.getId());

        log.info("Deleting Kafka topics for tenant: {}", tenant.getId());

        log.info("Purging Redis keys for tenant: {}", tenant.getId());
    }

    private void performGdprPurge(TenantEntity tenant) {
        log.info("Performing GDPR purge for tenant: {}", tenant.getId());
        // In production:
        // 1. Anonymize personal data in shared tables
        // 2. Delete S3 prefixes with tenant data
        // 3. Remove PII from audit logs after retention period
        // 4. Generate GDPR compliance report
    }
}
