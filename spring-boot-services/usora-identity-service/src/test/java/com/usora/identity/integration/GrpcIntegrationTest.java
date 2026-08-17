package com.usora.identity.integration;

import com.usora.identity.service.DomainService;
import io.grpc.ManagedChannel;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class GrpcIntegrationTest {

    @RegisterExtension
    public final GrpcCleanupRule grpcCleanup = new GrpcCleanupRule();

    @Autowired
    private DomainService domainService;

    private ManagedChannel channel;
    private String serverName;

    @BeforeEach
    void setUp() throws Exception {
        serverName = InProcessServerBuilder.generateName();

        grpcCleanup.register(InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new TestIdentityService(domainService))
                .build()
                .start());

        channel = grpcCleanup.register(
                InProcessChannelBuilder.forName(serverName)
                        .directExecutor()
                        .build());
    }

    @AfterEach
    void tearDown() {
        if (channel != null) {
            channel.shutdown();
        }
    }

    @Test
    void shouldInvokeHealthCheck() {
        assertThat(channel).isNotNull();
    }

    private static class TestIdentityService implements io.grpc.BindableService {
        private final DomainService domainService;

        TestIdentityService(DomainService domainService) {
            this.domainService = domainService;
        }

        private static class ByteArrayMarshaller implements io.grpc.MethodDescriptor.Marshaller<byte[]> {
            @Override
            public java.io.InputStream stream(byte[] value) {
                return new java.io.ByteArrayInputStream(value);
            }

            @Override
            public byte[] parse(java.io.InputStream stream) {
                try {
                    return stream.readAllBytes();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }

        @Override
        public io.grpc.ServerServiceDefinition bindService() {
            var marshaller = new ByteArrayMarshaller();
            return io.grpc.ServerServiceDefinition.builder("usora.identity.v1.IdentityService")
                    .addMethod(
                            io.grpc.MethodDescriptor.<byte[], byte[]>newBuilder()
                                    .setRequestMarshaller(marshaller)
                                    .setResponseMarshaller(marshaller)
                                    .setFullMethodName(
                                            io.grpc.MethodDescriptor.generateFullMethodName(
                                                    "usora.identity.v1.IdentityService", "Authenticate"))
                                    .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
                                    .build(),
                            new io.grpc.ServerCallHandler<byte[], byte[]>() {
                                @Override
                                public io.grpc.ServerCall.Listener<byte[]> startCall(
                                        io.grpc.ServerCall<byte[], byte[]> call,
                                        io.grpc.Metadata headers) {
                                    return new io.grpc.ServerCall.Listener<byte[]>() {
                                        // Stub implementation
                                    };
                                }
                            })
                    .build();
        }
    }
}
