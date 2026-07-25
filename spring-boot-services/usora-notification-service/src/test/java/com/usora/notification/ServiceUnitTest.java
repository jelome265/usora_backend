package com.usora.notification;

import com.usora.notification.dto.RequestDto.SendNotificationRequest;
import com.usora.notification.entity.Notification;
import com.usora.notification.entity.Notification.NotificationChannel;
import com.usora.notification.entity.Notification.NotificationStatus;
import com.usora.notification.entity.TenantEntity;
import com.usora.notification.event.DomainEventPublisher;
import com.usora.notification.mapper.EntityMapperImpl;
import com.usora.notification.repository.NotificationRepository;
import com.usora.notification.repository.TenantRepository;
import com.usora.notification.security.TenantContext;
import com.usora.notification.service.DomainService;
import com.usora.notification.service.TenantAwareService;
import com.usora.notification.util.EncryptionUtil;
import com.usora.notification.util.HashingUtil;
import com.usora.notification.util.ValidationUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServiceUnitTest {

    @Mock
    private NotificationRepository notificationRepository;
    @Mock
    private TenantRepository tenantRepository;
    @Mock
    private DomainEventPublisher eventPublisher;
    @Mock
    private EncryptionUtil encryptionUtil;

    private DomainService domainService;
    private TenantAwareService tenantAwareService;
    private ValidationUtil validationUtil;
    private HashingUtil hashingUtil;
    private EntityMapperImpl entityMapper;

    @BeforeEach
    void setUp() {
        validationUtil = new ValidationUtil();
        hashingUtil = new HashingUtil();
        entityMapper = new EntityMapperImpl();
        tenantAwareService = new TenantAwareService(tenantRepository, encryptionUtil);
        domainService = new DomainService(notificationRepository, entityMapper,
                eventPublisher, tenantAwareService, validationUtil, hashingUtil);

        TenantContext.setCurrentTenantId("tenant-1");
    }

    @Test
    void shouldGetNotificationStatus() {
        var id = UUID.randomUUID();
        var notification = Notification.builder()
                .id(id).tenantId("tenant-1")
                .channel(NotificationChannel.EMAIL)
                .toAddress("test@test.com").templateId("t1")
                .status(NotificationStatus.SENT)
                .priority(Notification.NotificationPriority.NORMAL)
                .sentAt(LocalDateTime.now())
                .build();

        when(notificationRepository.findById(id)).thenReturn(Optional.of(notification));

        var response = domainService.getNotificationStatus(id);
        assertThat(response.getStatus()).isEqualTo("SENT");
        assertThat(response.getId()).isEqualTo(id);
    }

    @Test
    void shouldThrowWhenNotificationNotFound() {
        var id = UUID.randomUUID();
        when(notificationRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> domainService.getNotificationStatus(id))
                .hasMessageContaining("Notification not found");
    }

    @Test
    void shouldListNotificationsWithPagination() {
        var notification = Notification.builder()
                .id(UUID.randomUUID()).tenantId("tenant-1")
                .channel(NotificationChannel.EMAIL)
                .toAddress("test@test.com").templateId("t1")
                .status(NotificationStatus.SENT)
                .priority(Notification.NotificationPriority.NORMAL)
                .build();

        var page = new PageImpl<>(List.of(notification));
        when(notificationRepository.findAll(any(Specification.class), any(PageRequest.class)))
                .thenReturn(page);

        var pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
        var request = com.usora.notification.dto.RequestDto.NotificationListRequest.builder()
                .page(0).size(20).build();

        var response = domainService.listNotifications(request, pageable);
        assertThat(response.getNotifications()).hasSize(1);
        assertThat(response.getTotalElements()).isEqualTo(1);
    }
}
