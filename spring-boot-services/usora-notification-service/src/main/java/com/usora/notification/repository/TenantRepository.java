package com.usora.notification.repository;

import com.usora.notification.entity.TenantEntity;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TenantRepository extends JpaRepository<TenantEntity, String> {

    @Cacheable(value = "tenantConfigs", key = "#tenantId")
    Optional<TenantEntity> findByTenantId(String tenantId);

    boolean existsByTenantId(String tenantId);
}
