package com.usora.notification;

import com.usora.notification.client.TenantServiceGrpc;
import com.usora.notification.client.TenantServiceProto;
import io.grpc.ManagedChannel;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(SpringExtension.class)
class GrpcIntegrationTest {

    private final GrpcCleanupRule grpcCleanup = new GrpcCleanupRule();
    private TenantServiceGrpc.TenantServiceBlockingStub blockingStub;

    @BeforeEach
    void setUp() throws IOException {
        var serverName = InProcessServerBuilder.generateName();

        var server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new TestTenantService())
                .build();
        server.start();
        grpcCleanup.register(server);

        ManagedChannel channel = InProcessChannelBuilder.forName(serverName)
                .directExecutor()
                .build();
        grpcCleanup.register(channel);

        blockingStub = TenantServiceGrpc.newBlockingStub(channel);
    }

    @Test
    void shouldGetTenantConfig() {
        var request = TenantServiceProto.GetTenantConfigRequest.newBuilder()
                .setTenantId("test-tenant-1")
                .build();

        var response = blockingStub.getTenantConfig(request);

        assertThat(response.getTenantId()).isEqualTo("test-tenant-1");
        assertThat(response.getTenantName()).isEqualTo("Test Tenant");
        assertThat(response.getActive()).isTrue();
    }

    static class TestTenantService extends TenantServiceGrpc.TenantServiceImplBase {
        @Override
        public void getTenantConfig(TenantServiceProto.GetTenantConfigRequest request,
                                    StreamObserver<TenantServiceProto.GetTenantConfigResponse> responseObserver) {
            var response = TenantServiceProto.GetTenantConfigResponse.newBuilder()
                    .setTenantId(request.getTenantId())
                    .setTenantName("Test Tenant")
                    .setSendgridFromEmail("noreply@test.com")
                    .setTwilioFromNumber("+15551234567")
                    .setRetryMaxAttempts(3)
                    .setRetryInitialDelayMs(1000)
                    .setActive(true)
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }
}
