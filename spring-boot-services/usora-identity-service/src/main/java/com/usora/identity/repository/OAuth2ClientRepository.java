package com.usora.identity.repository;

import com.usora.identity.entity.TenantEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface OAuth2ClientRepository extends JpaRepository<TenantEntity.OAuth2ClientEntity, UUID> {

    Optional<TenantEntity.OAuth2ClientEntity> findByClientId(String clientId);

    Optional<TenantEntity.OAuth2ClientEntity> findByClientIdAndTenantId(String clientId, UUID tenantId);
}
