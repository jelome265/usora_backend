package com.usora.identity.repository;

import com.usora.identity.entity.TenantEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface TenantRepository extends JpaRepository<TenantEntity, UUID> {

    Optional<TenantEntity> findByTenantName(String tenantName);

    Optional<TenantEntity> findByDomain(String domain);

    @Query("SELECT t FROM TenantEntity t WHERE t.enabled = true AND t.id = :id")
    Optional<TenantEntity> findActiveById(@Param("id") UUID id);

    @Query("SELECT t FROM TenantEntity t WHERE t.enabled = true AND t.tenantName = :name")
    Optional<TenantEntity> findActiveByTenantName(@Param("name") String name);
}
