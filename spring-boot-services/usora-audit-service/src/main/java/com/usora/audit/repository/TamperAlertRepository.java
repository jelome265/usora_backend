package com.usora.audit.repository;

import com.usora.audit.entity.TamperAlert;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface TamperAlertRepository extends JpaRepository<TamperAlert, UUID> {

    Page<TamperAlert> findByTenantIdOrderByDetectedAtDesc(String tenantId, Pageable pageable);

    @Query("SELECT t FROM TamperAlert t WHERE t.resolved = :resolved ORDER BY t.detectedAt DESC")
    List<TamperAlert> findByResolved(@Param("resolved") boolean resolved);

    @Query("SELECT t FROM TamperAlert t WHERE t.severity = :severity AND t.resolved = false ORDER BY t.detectedAt DESC")
    List<TamperAlert> findUnresolvedBySeverity(@Param("severity") String severity);

    long countByResolvedAndDetectedAtAfter(boolean resolved, Instant after);
}
