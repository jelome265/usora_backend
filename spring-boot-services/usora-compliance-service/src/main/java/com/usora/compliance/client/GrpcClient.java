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

        // CRITICAL BUG found while implementing F-018 (fail-closed AML/
        // sanctions decision logic): this method previously computed
        // isMatch/riskLevel from computeFuzzyMatchScore(), which returned
        // Math.round(Math.random() * 100.0) / 100.0 -- a genuinely random
        // number, not any real fuzzy name matching or sanctions-list
        // lookup. There is no AML/sanctions screening backend anywhere in
        // this repository to call (no proto contract, no service
        // directory under rust-services/ or spring-boot-services/ for
        // one) despite amlScreeningChannel, AML_SCREENING_HOST, and
        // AML_SCREENING_PORT all being real, correctly-wired
        // infrastructure pointed at a service that does not exist. Every
        // prior call to this method had roughly an 85% chance of
        // fabricating a "clean" result (score < 0.85) for ANY entity,
        // sanctioned or not -- including the actual F-018 fix in
        // DomainService, which correctly makes a real sanctions HIT
        // impossible to approve, but cannot protect against a screening
        // call that silently invents a "no hit" result instead of ever
        // reporting the hit truthfully.
        //
        // Until a real screening integration exists, the only safe,
        // honest behavior is to fail loudly rather than return a
        // plausible-looking fabricated verdict. This deliberately throws
        // so every caller (see DomainService.validateCompliance) routes
        // through the fail-closed INDETERMINATE path added for F-018 --
        // requiring manual review -- for every single screening request,
        // rather than silently degrading to a random pass/fail that looks
        // like a working control.
        throw new UnsupportedOperationException(
                "AML/sanctions screening for entity '" + name + "' against list type '" + listType + "' is not "
                        + "implemented. No real screening backend exists at " + amlScreeningChannel
                        + " -- computeFuzzyMatchScore() previously returned a random number here instead of a real "
                        + "result, which has been removed rather than left in place as a silent false sense of "
                        + "compliance coverage. This must be wired to a real sanctions/PEP/watchlist provider "
                        + "before this service can be relied upon for actual compliance decisions.");
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
