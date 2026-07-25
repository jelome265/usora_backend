package com.usora.tenant.repository;

import com.usora.tenant.entity.TenantEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TenantRepository extends JpaRepository<TenantEntity, UUID> {

    Optional<TenantEntity> findByDomain(String domain);

    List<TenantEntity> findByStatus(TenantEntity.TenantStatus status);

    Page<TenantEntity> findByStatus(TenantEntity.TenantStatus status, Pageable pageable);

    Page<TenantEntity> findByPlan(String plan, Pageable pageable);

    Page<TenantEntity> findByStatusAndPlan(TenantEntity.TenantStatus status, String plan, Pageable pageable);

    boolean existsByDomain(String domain);

    Optional<TenantEntity> findByStripeCustomerId(String stripeCustomerId);

    long countByStatus(TenantEntity.TenantStatus status);
}
