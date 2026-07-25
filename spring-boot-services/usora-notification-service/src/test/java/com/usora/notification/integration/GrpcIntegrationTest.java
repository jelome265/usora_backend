package com.usora.notification.integration;

import com.usora.notification.NotificationServiceGrpc;
import com.usora.notification.SendNotificationRequest;
import com.usora.notification.SendNotificationResponse;
import com.usora.notification.GetNotificationsRequest;
import com.usora.notification.GetNotificationsResponse;
import com.usora.notification.MarkAsReadRequest;
import com.usora.notification.MarkAsReadResponse;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import com.usora.notification.service.GrpcNotificationService;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class GrpcIntegrationTest {

    private Server grpcServer;
    private ManagedChannel channel;
    private NotificationServiceGrpc.NotificationServiceBlockingStub blockingStub;

    @Autowired
    private GrpcNotificationService grpcNotificationService;

    @BeforeEach
    void setUp() throws IOException {
        int port = 50051;
        grpcServer = ServerBuilder.forPort(port)
                .addService(grpcNotificationService)
                .build()
                .start();

        channel = ManagedChannelBuilder.forAddress("localhost", port)
                .usePlaintext()
                .build();

        blockingStub = NotificationServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void tearDown() {
        if (channel != null) {
            channel.shutdown();
        }
        if (grpcServer != null) {
            grpcServer.shutdown();
        }
    }

    @Test
    void sendNotification_ShouldReturnSuccess() {
        SendNotificationRequest request = SendNotificationRequest.newBuilder()
                .setRecipientId("user-grpc-001")
                .setType("EMAIL")
                .setTitle("gRPC Test")
                .setMessage("Sent via gRPC integration test.")
                .build();

        SendNotificationResponse response = blockingStub.sendNotification(request);

        assertNotNull(response);
        assertTrue(response.getSuccess());
        assertNotNull(response.getNotificationId());
    }

    @Test
    void getNotifications_ShouldReturnList() {
        SendNotificationRequest sendRequest = SendNotificationRequest.newBuilder()
                .setRecipientId("user-grpc-002")
                .setType("SMS")
                .setTitle("gRPC List Test")
                .setMessage("Message for list test.")
                .build();
        blockingStub.sendNotification(sendRequest);

        GetNotificationsRequest getRequest = GetNotificationsRequest.newBuilder()
                .setRecipientId("user-grpc-002")
                .build();

        GetNotificationsResponse response = blockingStub.getNotifications(getRequest);

        assertNotNull(response);
        assertFalse(response.getNotificationsList().isEmpty());
        assertEquals(1, response.getNotificationsCount());
    }

    @Test
    void markAsRead_ShouldUpdateNotification() {
        SendNotificationRequest sendRequest = SendNotificationRequest.newBuilder()
                .setRecipientId("user-grpc-003")
                .setType("EMAIL")
                .setTitle("gRPC Read Test")
                .setMessage("Will be marked as read.")
                .build();
        SendNotificationResponse sendResponse = blockingStub.sendNotification(sendRequest);

        MarkAsReadRequest markRequest = MarkAsReadRequest.newBuilder()
                .setNotificationId(sendResponse.getNotificationId())
                .build();

        MarkAsReadResponse markResponse = blockingStub.markAsRead(markRequest);

        assertNotNull(markResponse);
        assertTrue(markResponse.getSuccess());
    }

    @Test
    void sendNotification_WithEmptyRecipient_ShouldReturnFailure() {
        SendNotificationRequest request = SendNotificationRequest.newBuilder()
                .setRecipientId("")
                .setType("EMAIL")
                .setTitle("Invalid")
                .setMessage("Missing recipient.")
                .build();

        SendNotificationResponse response = blockingStub.sendNotification(request);

        assertNotNull(response);
        assertFalse(response.getSuccess());
    }
}
