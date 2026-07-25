package com.usora.compliance.repository;

import com.usora.compliance.entity.TenantEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

import java.util.List;

@NoRepositoryBean
public interface TenantRepository<T extends TenantEntity> extends JpaRepository<T, String> {

    List<T> findByTenantId(String tenantId);

    List<T> findByTenantIdAndJurisdiction(String tenantId, String jurisdiction);
}
