package com.usora.compliance.client;

import com.usora.compliance.dto.ComplianceValidationResponse;
import io.grpc.ManagedChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class GrpcClient {

    private static final Logger log = LoggerFactory.getLogger(GrpcClient.class);

    private final ManagedChannel amlScreeningChannel;

    public GrpcClient(@Qualifier("amlScreeningChannel") ManagedChannel amlScreeningChannel) {
        this.amlScreeningChannel = amlScreeningChannel;
    }

    public ComplianceValidationResponse.AmlScreeningResult screenIndividual(
            String entityId, Map<String, Object> entityData, String listType, Boolean includeAdverseMedia) {

        log.debug("Screening individual {} against {} list type", entityId, listType);

        String name = entityData.containsKey("fullName") ? entityData.get("fullName").toString() : entityId;
        String country = entityData.containsKey("country") ? entityData.get("country").toString() : "unknown";

        var score = computeFuzzyMatchScore(name, listType);
        var isMatch = score >= 0.85;
        var riskLevel = isMatch ? (score >= 0.95 ? "CRITICAL" : score >= 0.90 ? "HIGH" : "MEDIUM") : "LOW";

        return new ComplianceValidationResponse.AmlScreeningResult(
                "aml_" + entityId, listType.toUpperCase() + "_LIST", listType,
                score, isMatch, isMatch ? name : null,
                isMatch ? "PEP" : null, riskLevel);
    }

    public Double computeFuzzyMatchScore(String name, String listType) {
        // Simplified fuzzy matching simulation
        // In production, this would call the Rust AML screening service via gRPC
        return Math.round(Math.random() * 100.0) / 100.0;
    }

    public boolean isChannelHealthy() {
        try {
            return amlScreeningChannel != null
                    && !amlScreeningChannel.isShutdown()
                    && !amlScreeningChannel.isTerminated();
        } catch (Exception e) {
            return false;
        }
    }

    public void shutdown() {
        try {
            amlScreeningChannel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
