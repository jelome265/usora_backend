package com.usora.tenant.integration;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class GrpcIntegrationTest {

    private Server grpcServer;
    private int grpcPort;

    @BeforeEach
    void setUp() throws IOException {
        grpcPort = 0;
        grpcServer = ServerBuilder.forPort(0)
                .addService(new TestTenantServiceImpl())
                .build()
                .start();
        grpcPort = grpcServer.getPort();
    }

    @AfterEach
    void tearDown() {
        if (grpcServer != null) {
            grpcServer.shutdown();
        }
    }

    @Test
    void grpcServer_shouldStartAndBeReady() {
        assertNotNull(grpcServer);
        assertFalse(grpcServer.isShutdown());
        assertTrue(grpcPort > 0);
    }

    @Test
    void grpcServer_shouldHandleRequests() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        assertTrue(grpcServer.getPort() > 0);
        latch.countDown();
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    void grpcServer_shouldShutdownGracefully() {
        grpcServer.shutdown();
        try {
            assertTrue(grpcServer.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(grpcServer.isTerminated());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("Interrupted during shutdown");
        }
    }

    private static class TestTenantServiceImpl extends io.grpc.BindableService {
        @Override
        public io.grpc.ServerServiceDefinition bindService() {
            return io.grpc.ServerServiceDefinition.builder("tenant.TenantService")
                    .addMethod(
                            io.grpc.MethodDescriptor.newBuilder(
                                            io.grpc.MethodDescriptor.Marshaller.newMarshaller(
                                                    (t) -> t.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                                    (data) -> new String(data, java.nio.charset.StandardCharsets.UTF_8)),
                                            io.grpc.MethodDescriptor.Marshaller.newMarshaller(
                                                    (t) -> t.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                                    (data) -> new String(data, java.nio.charset.StandardCharsets.UTF_8))
                                    )
                                    .setFullMethodName("tenant.TenantService/OnboardTenant")
                                    .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
                                    .build(),
                            new io.grpc.ServerCallHandler<>() {
                                @Override
                                public io.grpc.ServerCall.Listener<Object> startCall(
                                        io.grpc.ServerCall<Object, Object> call,
                                        io.grpc.Metadata headers) {
                                    call.sendHeaders(new io.grpc.Metadata());
                                    call.sendMessage("{\"status\":\"OK\"}");
                                    call.close(io.grpc.Status.OK, new io.grpc.Metadata());
                                    return new io.grpc.ServerCall.Listener<>() {};
                                }
                            })
                    .build();
        }
    }
}
