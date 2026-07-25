package com.usora.compliance.repository;

import com.usora.compliance.entity.JurisdictionConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface JurisdictionConfigRepository extends JpaRepository<JurisdictionConfig, String> {

    Optional<JurisdictionConfig> findByTenantIdAndJurisdictionCode(String tenantId, String jurisdictionCode);

    List<JurisdictionConfig> findByTenantIdAndActiveTrue(String tenantId);

    List<JurisdictionConfig> findByJurisdictionCode(String jurisdictionCode);

    boolean existsByTenantIdAndJurisdictionCode(String tenantId, String jurisdictionCode);
}
