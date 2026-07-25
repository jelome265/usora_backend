package com.usora.audit.repository;

import com.usora.audit.entity.AuditEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    @Query("SELECT e FROM AuditEvent e WHERE e.tenantId = :tenantId AND e.resourceType = :resourceType AND e.resourceId = :resourceId ORDER BY e.eventTimestamp DESC")
    List<AuditEvent> findByTenantIdAndResourceTypeAndResourceId(
            @Param("tenantId") String tenantId,
            @Param("resourceType") String resourceType,
            @Param("resourceId") String resourceId,
            Pageable pageable);

    @Query("SELECT e FROM AuditEvent e WHERE e.tenantId = :tenantId ORDER BY e.eventTimestamp DESC")
    Page<AuditEvent> findByTenantId(@Param("tenantId") String tenantId, Pageable pageable);

    @Query("SELECT e FROM AuditEvent e WHERE e.tenantId = :tenantId AND e.eventTimestamp BETWEEN :from AND :to ORDER BY e.eventTimestamp ASC")
    List<AuditEvent> findByTenantIdAndTimestampBetween(
            @Param("tenantId") String tenantId,
            @Param("from") Instant from,
            @Param("to") Instant to);

    @Query("SELECT e FROM AuditEvent e WHERE e.tenantId = :tenantId AND e.eventTimestamp BETWEEN :from AND :to ORDER BY e.eventTimestamp ASC")
    Page<AuditEvent> findByTenantIdAndTimestampBetweenPaged(
            @Param("tenantId") String tenantId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            Pageable pageable);

    @Query("SELECT e FROM AuditEvent e WHERE e.tenantId = :tenantId AND e.category = :category ORDER BY e.eventTimestamp DESC")
    Page<AuditEvent> findByTenantIdAndCategory(
            @Param("tenantId") String tenantId,
            @Param("category") String category,
            Pageable pageable);

    @Query("SELECT e FROM AuditEvent e WHERE e.tenantId = :tenantId AND e.actorId = :actorId ORDER BY e.eventTimestamp DESC")
    Page<AuditEvent> findByTenantIdAndActorId(
            @Param("tenantId") String tenantId,
            @Param("actorId") String actorId,
            Pageable pageable);

    @Query("SELECT e FROM AuditEvent e WHERE e.tenantId = :tenantId AND e.eventTimestamp < :cutoff AND e.archived = false ORDER BY e.eventTimestamp ASC")
    List<AuditEvent> findUnarchivedEventsOlderThan(@Param("tenantId") String tenantId, @Param("cutoff") Instant cutoff);

    @Query("SELECT e FROM AuditEvent e WHERE e.tenantId = :tenantId AND e.archived = false ORDER BY e.eventTimestamp DESC")
    Page<AuditEvent> findUnarchivedByTenantId(@Param("tenantId") String tenantId, Pageable pageable);

    @Query("SELECT e.currentHash FROM AuditEvent e WHERE e.tenantId = :tenantId ORDER BY e.eventTimestamp DESC")
    List<String> findCurrentHashesByTenantId(@Param("tenantId") String tenantId, Pageable pageable);

    Optional<AuditEvent> findTopByTenantIdAndAnchoredOrderByEventTimestampDesc(String tenantId, boolean anchored);

    @Query("SELECT e FROM AuditEvent e WHERE e.tenantId = :tenantId AND e.anchored = false AND e.eventTimestamp <= :before ORDER BY e.eventTimestamp ASC")
    List<AuditEvent> findUnanchoredEventsBefore(@Param("tenantId") String tenantId, @Param("before") Instant before);

    @Query("SELECT e FROM AuditEvent e WHERE e.tenantId = :tenantId AND e.currentHash = :hash")
    Optional<AuditEvent> findByTenantIdAndCurrentHash(@Param("tenantId") String tenantId, @Param("hash") String hash);

    @Query("SELECT e FROM AuditEvent e WHERE e.tenantId = :tenantId AND e.eventTimestamp BETWEEN :from AND :to ORDER BY e.eventTimestamp ASC")
    List<AuditEvent> findByTenantIdAndTimestampBetweenOrdered(
            @Param("tenantId") String tenantId,
            @Param("from") Instant from,
            @Param("to") Instant to);

    long countByTenantIdAndEventTimestampBetween(String tenantId, Instant from, Instant to);
}
