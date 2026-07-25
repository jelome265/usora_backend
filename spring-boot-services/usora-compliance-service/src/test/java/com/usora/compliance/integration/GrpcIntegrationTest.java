package com.usora.compliance.integration;

import com.usora.compliance.client.GrpcClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class GrpcIntegrationTest {

    @Autowired
    private GrpcClient grpcClient;

    @Test
    void shouldScreenIndividual() {
        var result = grpcClient.screenIndividual(
                "entity-1",
                Map.of("fullName", "John Doe", "country", "US"),
                "sanctions",
                false);
        assertNotNull(result);
        assertNotNull(result.listType());
        assertEquals("sanctions", result.listType());
    }
}
