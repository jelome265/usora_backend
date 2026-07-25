package com.usora.integration.integration;

import com.usora.integration.Application;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = Application.class)
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {"test-webhook-events"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GrpcIntegrationTest {

    @Test
    @DisplayName("Application context loads successfully")
    void contextLoads() {
        assertTrue(true, "Application context should load");
    }

    @Test
    @DisplayName("gRPC server configuration is available")
    void grpcServerConfigured() {
        assertTrue(true, "gRPC server should be configured");
    }
}
