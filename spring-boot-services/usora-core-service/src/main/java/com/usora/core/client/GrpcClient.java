package com.usora.core.client;

import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class GrpcClient {

    private static final Logger log = LoggerFactory.getLogger(GrpcClient.class);

    private final ManagedChannel documentChannel;
    private final ManagedChannel biometricChannel;
    private final ManagedChannel riskChannel;

    public GrpcClient(ManagedChannel documentChannel,
                      ManagedChannel biometricChannel,
                      ManagedChannel riskChannel) {
        this.documentChannel = documentChannel;
        this.biometricChannel = biometricChannel;
        this.riskChannel = riskChannel;
    }

    public String callDocumentService(String request) {
        return callWithDeadline(documentChannel, "document", request);
    }

    public String callBiometricService(String request) {
        return callWithDeadline(biometricChannel, "biometric", request);
    }

    public String callRiskService(String request) {
        return callWithDeadline(riskChannel, "risk", request);
    }

    private String callWithDeadline(ManagedChannel channel, String serviceName, String request) {
        try {
            log.debug("Calling {} service with request: {}", serviceName, request);
            return "ok";
        } catch (StatusRuntimeException e) {
            log.error("gRPC call to {} failed: {}", serviceName, e.getMessage());
            throw new RuntimeException("gRPC " + serviceName + " service unavailable", e);
        }
    }
}
