package com.usora.core.integration;

import com.usora.core.client.GrpcClient;
import io.grpc.ManagedChannel;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext
class GrpcIntegrationTest {

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private org.springframework.security.oauth2.jwt.JwtEncoder jwtEncoder;

    private ManagedChannel inProcessChannel;

    @BeforeEach
    void setUp() {
        var serverName = InProcessServerBuilder.generateName();
        inProcessChannel = InProcessChannelBuilder.forName(serverName).build();
    }

    @AfterEach
    void tearDown() {
        if (inProcessChannel != null) {
            inProcessChannel.shutdown();
        }
    }

    @Test
    void shouldCreateGrpcClient() {
        assertDoesNotThrow(() -> new GrpcClient(inProcessChannel, inProcessChannel, inProcessChannel));
    }
}
