package com.usora.notification.client;

import com.usora.notification.entity.TenantEntity;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class GrpcClient {

    private final ManagedChannel tenantServiceChannel;

    public TenantEntity getTenantConfig(String tenantId) {
        try {
            var request = TenantServiceProto.GetTenantConfigRequest.newBuilder()
                    .setTenantId(tenantId)
                    .build();

            var stub = TenantServiceGrpc.newBlockingStub(tenantServiceChannel)
                    .withDeadlineAfter(5, TimeUnit.SECONDS);

            var response = stub.getTenantConfig(request);

            return mapToTenantEntity(response);
        } catch (StatusRuntimeException e) {
            log.error("gRPC call to tenant service failed for tenant {}: {}",
                    tenantId, e.getMessage());
            throw new RuntimeException("Failed to fetch tenant config from tenant service", e);
        }
    }

    private TenantEntity mapToTenantEntity(
            TenantServiceProto.GetTenantConfigResponse response) {
        return TenantEntity.builder()
                .tenantId(response.getTenantId())
                .tenantName(response.getTenantName())
                .sendgridApiKey(response.getSendgridApiKey())
                .sendgridFromEmail(response.getSendgridFromEmail())
                .twilioAccountSid(response.getTwilioAccountSid())
                .twilioAuthToken(response.getTwilioAuthToken())
                .twilioFromNumber(response.getTwilioFromNumber())
                .webhookUrlTemplate(response.getWebhookUrlTemplate())
                .webhookSecret(response.getWebhookSecret())
                .retryMaxAttempts(response.getRetryMaxAttempts())
                .retryInitialDelayMs(response.getRetryInitialDelayMs())
                .active(response.getActive())
                .build();
    }
}
