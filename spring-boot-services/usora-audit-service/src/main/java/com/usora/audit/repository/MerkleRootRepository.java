package com.usora.audit.repository;

import com.usora.audit.entity.MerkleRoot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MerkleRootRepository extends JpaRepository<MerkleRoot, UUID> {

    Optional<MerkleRoot> findTopByTenantIdOrderByIntervalEndDesc(String tenantId);

    @Query("SELECT m FROM MerkleRoot m WHERE m.tenantId = :tenantId AND m.intervalStart >= :from AND m.intervalEnd <= :to ORDER BY m.intervalStart ASC")
    java.util.List<MerkleRoot> findByTenantIdAndIntervalRange(
            @Param("tenantId") String tenantId,
            @Param("from") Instant from,
            @Param("to") Instant to);

    Optional<MerkleRoot> findByTenantIdAndMerkleRoot(String tenantId, String merkleRoot);
}
