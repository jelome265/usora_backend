package com.usora.notification.unit;

import com.usora.notification.dto.NotificationRequest;
import com.usora.notification.dto.NotificationResponse;
import com.usora.notification.mapper.EntityMapper;
import com.usora.notification.model.Notification;
import com.usora.notification.repository.NotificationRepository;
import com.usora.notification.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ServiceUnitTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private EntityMapper entityMapper;

    @InjectMocks
    private NotificationService notificationService;

    @Captor
    private ArgumentCaptor<Notification> notificationCaptor;

    private Notification notification;
    private NotificationRequest request;
    private NotificationResponse response;

    @BeforeEach
    void setUp() {
        notification = new Notification();
        notification.setId(UUID.randomUUID().toString());
        notification.setRecipientId("user-001");
        notification.setType("EMAIL");
        notification.setTitle("Test Title");
        notification.setMessage("Test Message");
        notification.setCreatedAt(LocalDateTime.now());
        notification.setRead(false);

        request = new NotificationRequest();
        request.setRecipientId("user-001");
        request.setType("EMAIL");
        request.setTitle("Test Title");
        request.setMessage("Test Message");

        response = new NotificationResponse();
        response.setId(notification.getId());
        response.setRecipientId("user-001");
        response.setType("EMAIL");
        response.setTitle("Test Title");
        response.setMessage("Test Message");
        response.setRead(false);
    }

    @Test
    void createNotification_ShouldReturnResponse() {
        when(entityMapper.toEntity(any(NotificationRequest.class))).thenReturn(notification);
        when(notificationRepository.save(any(Notification.class))).thenReturn(notification);
        when(entityMapper.toResponse(any(Notification.class))).thenReturn(response);

        NotificationResponse result = notificationService.createNotification(request);

        assertNotNull(result);
        assertEquals("user-001", result.getRecipientId());
        assertEquals("EMAIL", result.getType());

        verify(notificationRepository).save(notificationCaptor.capture());
        assertEquals("user-001", notificationCaptor.getValue().getRecipientId());
    }

    @Test
    void getNotificationById_WhenExists_ShouldReturnResponse() {
        when(notificationRepository.findById("notif-001")).thenReturn(Optional.of(notification));
        when(entityMapper.toResponse(notification)).thenReturn(response);

        NotificationResponse result = notificationService.getNotificationById("notif-001");

        assertNotNull(result);
        assertEquals("Test Title", result.getTitle());
    }

    @Test
    void getNotificationById_WhenNotExists_ShouldThrowException() {
        when(notificationRepository.findById("notif-999")).thenReturn(Optional.empty());

        assertThrows(RuntimeException.class, () -> notificationService.getNotificationById("notif-999"));
    }

    @Test
    void getNotificationsByRecipientId_ShouldReturnList() {
        when(notificationRepository.findByRecipientId("user-001")).thenReturn(List.of(notification));
        when(entityMapper.toResponse(notification)).thenReturn(response);

        List<NotificationResponse> results = notificationService.getNotificationsByRecipientId("user-001");

        assertEquals(1, results.size());
    }

    @Test
    void markAsRead_ShouldUpdateEntity() {
        when(notificationRepository.findById("notif-001")).thenReturn(Optional.of(notification));
        when(notificationRepository.save(any(Notification.class))).thenReturn(notification);
        when(entityMapper.toResponse(any(Notification.class))).thenReturn(response);

        NotificationResponse result = notificationService.markAsRead("notif-001");

        assertNotNull(result);
        verify(notificationRepository).save(notificationCaptor.capture());
        assertTrue(notificationCaptor.getValue().isRead());
    }

    @Test
    void deleteNotification_ShouldCallRepository() {
        doNothing().when(notificationRepository).deleteById("notif-001");

        notificationService.deleteNotification("notif-001");

        verify(notificationRepository).deleteById("notif-001");
    }
}
