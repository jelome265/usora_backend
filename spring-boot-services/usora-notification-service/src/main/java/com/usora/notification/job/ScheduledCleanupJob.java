package com.usora.notification.job;

import com.usora.notification.entity.Notification;
import com.usora.notification.entity.Notification.NotificationStatus;
import com.usora.notification.repository.NotificationRepository;
import com.usora.notification.service.DomainService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledCleanupJob {

    private final NotificationRepository notificationRepository;
    private final DomainService domainService;

    @Scheduled(fixedRate = 300_000)
    @Transactional
    public void retryFailedNotifications() {
        log.info("Starting retry of failed notifications");

        var failedNotifications = notificationRepository
                .findByStatusAndRetryCountLessThan(
                        NotificationStatus.FAILED, 3);

        for (var notification : failedNotifications) {
            try {
                domainService.retryNotification(notification);
            } catch (Exception e) {
                log.error("Failed to retry notification {}: {}",
                        notification.getId(), e.getMessage());
            }
        }

        log.info("Retry job completed. Processed {} notifications",
                failedNotifications.size());
    }

    @Scheduled(cron = "0 0 2 * * ?")
    @Transactional
    public void cleanupExpiredNotifications() {
        log.info("Starting cleanup of expired notifications");

        var cutoffDate = LocalDateTime.now().minusDays(90);

        int deletedCount = notificationRepository
                .deleteByCreatedAtBefore(cutoffDate);

        log.info("Cleanup completed. Deleted {} expired notifications", deletedCount);
    }

    @Scheduled(fixedRate = 600_000)
    public void checkProviderHealth() {
        log.info("Checking notification provider health");

        var pendingCount = notificationRepository
                .countByStatus(NotificationStatus.PENDING);
        var failedCount = notificationRepository
                .countByStatus(NotificationStatus.FAILED);

        log.info("Provider health check - pending: {}, failed: {}",
                pendingCount, failedCount);

        if (failedCount > 100) {
            log.warn("High number of failed notifications: {}. Possible provider issue.", failedCount);
        }
    }
}
