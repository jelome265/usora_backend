package com.usora.compliance.controller.v1;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.usora.compliance.dto.*;
import com.usora.compliance.service.DomainService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.bean.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ApiController.class)
@ActiveProfiles("test")
class ApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private DomainService domainService;

    @Test
    void shouldReturnCreatedForEvidenceSubmission() throws Exception {
        var request = new EvidenceSubmissionRequest("case1", "document", "hash",
                "content".getBytes(), "text/plain", null, null, false);
        var response = new EvidenceSubmissionResponse("ev-1", "case1", "SUBMITTED",
                "/path", "vhash", null, java.time.Instant.now(), "OK");

        when(domainService.submitEvidence(any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/compliance/evidence")
                        .header("X-Tenant-Id", "tenant1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
    }

    @Test
    void shouldReturnAcceptedForReportGeneration() throws Exception {
        var request = new ReportGenerationRequest("summary", "pdf", null, null,
                null, null, null, null, null, null);
        var response = new ReportGenerationResponse("rep-1", "COMPLETED", "pdf",
                "/reports/rep-1.pdf", java.time.Instant.now(), java.time.Instant.now(), "OK");

        when(domainService.generateReport(any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/compliance/reports")
                        .header("X-Tenant-Id", "tenant1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted());
    }

    @Test
    void shouldReturnOkForRulesGet() throws Exception {
        var response = new RegulatoryRulesResponse(List.of(), 0, java.time.Instant.now(), "v1");
        when(domainService.getRegulatoryRules(any())).thenReturn(response);

        mockMvc.perform(get("/api/v1/compliance/rules")
                        .header("X-Tenant-Id", "tenant1"))
                .andExpect(status().isOk());
    }
}
