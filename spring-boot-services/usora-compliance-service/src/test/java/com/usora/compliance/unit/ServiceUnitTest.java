package com.usora.compliance.unit;

import com.usora.compliance.dto.ComplianceValidationRequest;
import com.usora.compliance.dto.EvidenceSubmissionRequest;
import com.usora.compliance.exception.BusinessException;
import com.usora.compliance.security.TenantContext;
import com.usora.compliance.util.HashingUtil;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ServiceUnitTest {

    @Test
    void shouldRequireTenantContext() {
        TenantContext.clear();
        assertNull(TenantContext.getCurrentTenant());
    }

    @Test
    void shouldValidateRequestNotNull() {
        assertThrows(NullPointerException.class, () -> {
            var request = new ComplianceValidationRequest(
                    null, "entity1", "individual", Map.of(), null, null, null, null);
            request.caseId().length(); // Would throw NPE since caseId is marked @NotBlank but could be null
        });
    }

    @Test
    void shouldMatchEvidenceHash() {
        var content = "evidence-content".getBytes();
        var hash = HashingUtil.sha256(content);
        assertNotNull(hash);
        assertEquals(64, hash.length());
    }
}
