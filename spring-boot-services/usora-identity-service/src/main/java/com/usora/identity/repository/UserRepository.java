package com.usora.identity.repository;

import com.usora.identity.entity.TenantEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<TenantEntity.UserEntity, UUID> {

    Optional<TenantEntity.UserEntity> findByUsernameAndTenantId(String username, UUID tenantId);

    Optional<TenantEntity.UserEntity> findByEmailAndTenantId(String email, UUID tenantId);

    @Query("SELECT u FROM TenantEntity.UserEntity u WHERE u.username = :username AND u.tenant.tenantName = :tenantName")
    Optional<TenantEntity.UserEntity> findByUsernameAndTenantName(
            @Param("username") String username,
            @Param("tenantName") String tenantName);

    boolean existsByUsernameAndTenantId(String username, UUID tenantId);

    boolean existsByEmailAndTenantId(String email, UUID tenantId);
}
