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

    /**
     * CRITICAL BUG found while implementing F-018: screenIndividual
     * previously returned a fabricated result derived from
     * computeFuzzyMatchScore(), which was Math.random() -- not a real
     * screening outcome at all, and this test only ever asserted the
     * response was non-null with the right listType echoed back,
     * completely missing that the actual match verdict was meaningless.
     * There is no real AML/sanctions screening backend anywhere in this
     * repository (see the removed method's replacement for the full
     * explanation). screenIndividual now throws
     * UnsupportedOperationException rather than silently fabricate a
     * result, so every real caller fails closed into the INDETERMINATE
     * decision state (see DomainService.validateCompliance) instead of
     * receiving a plausible-looking random verdict. This test now
     * verifies that fail-closed behavior instead of the fabricated
     * result it previously (and incorrectly) treated as a passing case.
     */
    @Test
    void shouldFailClosedRatherThanFabricateAScreeningResult() {
        assertThrows(UnsupportedOperationException.class, () -> grpcClient.screenIndividual(
                "entity-1",
                Map.of("fullName", "John Doe", "country", "US"),
                "sanctions",
                false));
    }
}
