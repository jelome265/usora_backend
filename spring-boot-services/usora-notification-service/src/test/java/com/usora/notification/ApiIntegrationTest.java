package com.usora.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.usora.notification.dto.RequestDto.SendNotificationRequest;
import com.usora.notification.dto.ResponseDto.NotificationResponse;
import com.usora.notification.entity.Notification;
import com.usora.notification.entity.Notification.NotificationChannel;
import com.usora.notification.entity.Notification.NotificationStatus;
import com.usora.notification.repository.NotificationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
    "spring.kafka.listener.auto-startup=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(authorities = {"notification:send", "notification:read"})
class ApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private NotificationRepository notificationRepository;

    @Test
    void shouldSendAndRetrieveNotification() throws Exception {
        var request = SendNotificationRequest.builder()
                .to("user@example.com")
                .channel("EMAIL")
                .templateId("welcome-email")
                .subject("Welcome!")
                .priority("NORMAL")
                .build();

        var result = mockMvc.perform(post("/api/v1/notifications/send")
                        .header("X-Tenant-Id", "test-tenant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();

        var response = objectMapper.readValue(
                result.getResponse().getContentAsString(), NotificationResponse.class);

        mockMvc.perform(get("/api/v1/notifications/{id}/status", response.getId())
                        .header("X-Tenant-Id", "test-tenant"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(response.getId().toString()));
    }

    @Test
    void shouldListNotifications() throws Exception {
        mockMvc.perform(get("/api/v1/notifications")
                        .header("X-Tenant-Id", "test-tenant")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notifications").isArray());
    }

    @Test
    void shouldAcknowledgeNotification() throws Exception {
        var notification = Notification.builder()
                .tenantId("test-tenant")
                .channel(NotificationChannel.EMAIL)
                .toAddress("ack@test.com")
                .templateId("ack-template")
                .status(NotificationStatus.SENT)
                .priority(Notification.NotificationPriority.NORMAL)
                .build();
        notificationRepository.save(notification);

        mockMvc.perform(post("/api/v1/notifications/{id}/acknowledge", notification.getId())
                        .header("X-Tenant-Id", "test-tenant"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"));
    }
}
