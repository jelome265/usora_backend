package com.usora.notification;

import com.usora.notification.entity.Notification;
import com.usora.notification.entity.Notification.NotificationChannel;
import com.usora.notification.entity.Notification.NotificationPriority;
import com.usora.notification.entity.Notification.NotificationStatus;
import com.usora.notification.repository.NotificationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
class RepositoryUnitTest {

    @Autowired
    private NotificationRepository notificationRepository;

    @Test
    void shouldSaveAndFindNotification() {
        var notification = Notification.builder()
                .tenantId("tenant-1")
                .channel(NotificationChannel.EMAIL)
                .toAddress("test@example.com")
                .templateId("welcome")
                .status(NotificationStatus.PENDING)
                .priority(NotificationPriority.NORMAL)
                .build();

        var saved = notificationRepository.save(notification);
        assertThat(saved.getId()).isNotNull();

        var found = notificationRepository.findById(saved.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getToAddress()).isEqualTo("test@example.com");
    }

    @Test
    void shouldFindByTenantId() {
        notificationRepository.save(Notification.builder()
                .tenantId("tenant-1").channel(NotificationChannel.EMAIL)
                .toAddress("a@b.com").templateId("t1")
                .status(NotificationStatus.PENDING).priority(NotificationPriority.NORMAL).build());
        notificationRepository.save(Notification.builder()
                .tenantId("tenant-1").channel(NotificationChannel.SMS)
                .toAddress("+1234567890").templateId("t2")
                .status(NotificationStatus.SENT).priority(NotificationPriority.HIGH).build());
        notificationRepository.save(Notification.builder()
                .tenantId("tenant-2").channel(NotificationChannel.EMAIL)
                .toAddress("c@d.com").templateId("t3")
                .status(NotificationStatus.PENDING).priority(NotificationPriority.LOW).build());

        var page = notificationRepository.findByTenantId("tenant-1", PageRequest.of(0, 10));
        assertThat(page.getContent()).hasSize(2);
    }

    @Test
    void shouldFindByStatusAndRetryCount() {
        notificationRepository.save(Notification.builder()
                .tenantId("tenant-1").channel(NotificationChannel.EMAIL)
                .toAddress("fail@test.com").templateId("t1")
                .status(NotificationStatus.FAILED).priority(NotificationPriority.NORMAL)
                .retryCount(1).build());
        notificationRepository.save(Notification.builder()
                .tenantId("tenant-1").channel(NotificationChannel.SMS)
                .toAddress("+1234567890").templateId("t2")
                .status(NotificationStatus.FAILED).priority(NotificationPriority.HIGH)
                .retryCount(2).build());

        var failed = notificationRepository
                .findByStatusAndRetryCountLessThan(NotificationStatus.FAILED, 3);
        assertThat(failed).hasSize(2);
    }

    @Test
    void shouldDeleteByCreatedAtBefore() {
        var old = Notification.builder()
                .tenantId("tenant-1").channel(NotificationChannel.EMAIL)
                .toAddress("old@test.com").templateId("t1")
                .status(NotificationStatus.DELIVERED).priority(NotificationPriority.NORMAL)
                .build();
        notificationRepository.save(old);

        var deleted = notificationRepository
                .deleteByCreatedAtBefore(LocalDateTime.now().plusDays(1));
        assertThat(deleted).isGreaterThan(0);
    }
}
