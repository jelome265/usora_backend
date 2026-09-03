package com.usora.notification.repository;

import com.usora.notification.entity.Notification;
import com.usora.notification.entity.Notification.NotificationChannel;
import com.usora.notification.entity.Notification.NotificationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, UUID>,
        JpaSpecificationExecutor<Notification> {

    Page<Notification> findByTenantId(String tenantId, Pageable pageable);

    Page<Notification> findByTenantIdAndChannel(String tenantId, NotificationChannel channel, Pageable pageable);

    Page<Notification> findByTenantIdAndStatus(String tenantId, NotificationStatus status, Pageable pageable);

    Page<Notification> findByTenantIdAndCreatedAtBetween(String tenantId, LocalDateTime from, LocalDateTime to, Pageable pageable);

    // F-023: used by DomainService.sendNotification's idempotent-insert
    // path. Looked up by (tenantId, idempotencyKey) after a unique
    // constraint violation on insert, to return the ALREADY-existing
    // notification from the original call rather than creating a
    // duplicate -- see V4__idempotency_key.sql for the constraint this
    // relies on.
    java.util.Optional<Notification> findByTenantIdAndIdempotencyKey(String tenantId, String idempotencyKey);

    List<Notification> findByStatusAndRetryCountLessThan(NotificationStatus status, int maxRetries);

    @Modifying
    @Query("DELETE FROM Notification n WHERE n.createdAt < :cutoff")
    int deleteByCreatedAtBefore(@Param("cutoff") LocalDateTime cutoff);

    long countByStatus(NotificationStatus status);

    long countByTenantIdAndStatus(String tenantId, NotificationStatus status);

    List<Notification> findByStatusAndCreatedAtBefore(NotificationStatus status, LocalDateTime before);
}
