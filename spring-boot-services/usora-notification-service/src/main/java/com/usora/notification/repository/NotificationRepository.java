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

    List<Notification> findByStatusAndRetryCountLessThan(NotificationStatus status, int maxRetries);

    @Modifying
    @Query("DELETE FROM Notification n WHERE n.createdAt < :cutoff")
    int deleteByCreatedAtBefore(@Param("cutoff") LocalDateTime cutoff);

    long countByStatus(NotificationStatus status);

    long countByTenantIdAndStatus(String tenantId, NotificationStatus status);

    List<Notification> findByStatusAndCreatedAtBefore(NotificationStatus status, LocalDateTime before);
}
