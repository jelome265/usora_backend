package com.usora.notification;

import com.usora.notification.dto.ResponseDto.NotificationResponse;
import com.usora.notification.entity.Notification;
import com.usora.notification.entity.Notification.NotificationChannel;
import com.usora.notification.entity.Notification.NotificationPriority;
import com.usora.notification.entity.Notification.NotificationStatus;
import com.usora.notification.mapper.EntityMapperImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MapperUnitTest {

    private EntityMapperImpl mapper;

    @BeforeEach
    void setUp() {
        mapper = new EntityMapperImpl();
    }

    @Test
    void shouldMapNotificationToResponse() {
        var id = UUID.randomUUID();
        var now = LocalDateTime.now();
        var notification = Notification.builder()
                .id(id)
                .tenantId("tenant-1")
                .channel(NotificationChannel.EMAIL)
                .toAddress("test@example.com")
                .templateId("welcome-email")
                .status(NotificationStatus.SENT)
                .priority(NotificationPriority.NORMAL)
                .sentAt(now)
                .build();

        var response = mapper.toResponse(notification);

        assertThat(response.getId()).isEqualTo(id);
        assertThat(response.getChannel()).isEqualTo("EMAIL");
        assertThat(response.getStatus()).isEqualTo("SENT");
        assertThat(response.getToAddress()).isEqualTo("test@example.com");
        assertThat(response.getSentAt()).isEqualTo(now);
    }

    @Test
    void shouldMapNotificationListToResponseList() {
        var notifications = List.of(
                Notification.builder().id(UUID.randomUUID())
                        .channel(NotificationChannel.SMS).status(NotificationStatus.PENDING)
                        .toAddress("+1234567890").templateId("sms-template")
                        .priority(NotificationPriority.HIGH).retryCount(0).build(),
                Notification.builder().id(UUID.randomUUID())
                        .channel(NotificationChannel.EMAIL).status(NotificationStatus.DELIVERED)
                        .toAddress("user@test.com").templateId("email-template")
                        .priority(NotificationPriority.LOW).retryCount(1).build()
        );

        var responses = mapper.toResponseList(notifications);

        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).getChannel()).isEqualTo("SMS");
        assertThat(responses.get(1).getChannel()).isEqualTo("EMAIL");
    }
}
