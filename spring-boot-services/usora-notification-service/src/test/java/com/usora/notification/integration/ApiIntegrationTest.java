package com.usora.notification.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.usora.notification.dto.NotificationRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void createNotification_ShouldReturn201() throws Exception {
        NotificationRequest request = new NotificationRequest();
        request.setRecipientId("user-001");
        request.setType("EMAIL");
        request.setTitle("Integration Test");
        request.setMessage("Created during integration test.");

        mockMvc.perform(post("/api/v1/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.recipientId", is("user-001")))
                .andExpect(jsonPath("$.type", is("EMAIL")))
                .andExpect(jsonPath("$.read", is(false)))
                .andExpect(jsonPath("$.id", notNullValue()));
    }

    @Test
    void getNotificationById_ShouldReturn200() throws Exception {
        NotificationRequest request = new NotificationRequest();
        request.setRecipientId("user-002");
        request.setType("SMS");
        request.setTitle("Get Test");
        request.setMessage("Testing GET endpoint.");

        String createResponse = mockMvc.perform(post("/api/v1/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String id = objectMapper.readTree(createResponse).get("id").asText();

        mockMvc.perform(get("/api/v1/notifications/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(id)))
                .andExpect(jsonPath("$.recipientId", is("user-002")))
                .andExpect(jsonPath("$.type", is("SMS")));
    }

    @Test
    void getNotificationsByRecipientId_ShouldReturn200() throws Exception {
        NotificationRequest request = new NotificationRequest();
        request.setRecipientId("user-003");
        request.setType("EMAIL");
        request.setTitle("List Test");
        request.setMessage("Testing list endpoint.");

        mockMvc.perform(post("/api/v1/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/notifications").param("recipientId", "user-003"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", is(not(empty()))));
    }

    @Test
    void markAsRead_ShouldReturn200() throws Exception {
        NotificationRequest request = new NotificationRequest();
        request.setRecipientId("user-004");
        request.setType("EMAIL");
        request.setTitle("Read Test");
        request.setMessage("Testing mark-as-read.");

        String createResponse = mockMvc.perform(post("/api/v1/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String id = objectMapper.readTree(createResponse).get("id").asText();

        mockMvc.perform(put("/api/v1/notifications/{id}/read", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read", is(true)));
    }

    @Test
    void deleteNotification_ShouldReturn204() throws Exception {
        NotificationRequest request = new NotificationRequest();
        request.setRecipientId("user-005");
        request.setType("EMAIL");
        request.setTitle("Delete Test");
        request.setMessage("Testing delete.");

        String createResponse = mockMvc.perform(post("/api/v1/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String id = objectMapper.readTree(createResponse).get("id").asText();

        mockMvc.perform(delete("/api/v1/notifications/{id}", id))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/notifications/{id}", id))
                .andExpect(status().isNotFound());
    }

    @Test
    void createNotification_WithInvalidPayload_ShouldReturn400() throws Exception {
        NotificationRequest request = new NotificationRequest();
        request.setRecipientId("");
        request.setType("");

        mockMvc.perform(post("/api/v1/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }
}
