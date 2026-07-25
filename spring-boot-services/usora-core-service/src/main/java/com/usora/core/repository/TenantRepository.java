package com.usora.core.repository;

import com.usora.core.entity.TenantEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TenantRepository extends JpaRepository<TenantEntity, String> {

    Optional<TenantEntity> findByTenantId(String tenantId);

    boolean existsByTenantId(String tenantId);
}
