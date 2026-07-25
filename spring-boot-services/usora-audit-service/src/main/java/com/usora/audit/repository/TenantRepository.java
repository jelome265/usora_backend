package com.usora.audit.repository;

import com.usora.audit.entity.TenantEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TenantRepository extends JpaRepository<TenantEntity, String> {

    Optional<TenantEntity> findByTenantIdAndActiveTrue(String tenantId);
}
