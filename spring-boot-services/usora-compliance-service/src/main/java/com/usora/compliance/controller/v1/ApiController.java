package com.usora.compliance.controller.v1;

import com.usora.compliance.dto.*;
import com.usora.compliance.service.DomainService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/v1/compliance")
public class ApiController {

    private final DomainService domainService;

    public ApiController(DomainService domainService) {
        this.domainService = domainService;
    }

    @PostMapping("/validate")
    @PreAuthorize("hasAnyRole('compliance:manage', 'compliance:admin')")
    public ResponseEntity<ComplianceValidationResponse> validateCompliance(
            @Valid @RequestBody ComplianceValidationRequest request) {
        var response = domainService.validateCompliance(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/rules")
    @PreAuthorize("hasAnyRole('compliance:read', 'compliance:manage', 'compliance:admin')")
    public ResponseEntity<RegulatoryRulesResponse> getRegulatoryRules(
            @RequestParam(required = false) String jurisdiction) {
        return ResponseEntity.ok(domainService.getRegulatoryRules(jurisdiction));
    }

    @PutMapping("/rules")
    @PreAuthorize("hasRole('compliance:admin')")
    public ResponseEntity<RegulatoryRulesUpdateResponse> updateRegulatoryRules(
            @Valid @RequestBody RegulatoryRulesUpdateRequest request,
            @RequestHeader("X-Authorization-Officer") String officerToken,
            @RequestHeader("X-Authorization-Legal") String legalToken) {
        var response = domainService.updateRegulatoryRules(request, officerToken, legalToken);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/reports")
    @PreAuthorize("hasAnyRole('compliance:read', 'compliance:manage')")
    public ResponseEntity<ReportGenerationResponse> generateReport(
            @Valid @RequestBody ReportGenerationRequest request) {
        var response = domainService.generateReport(request);
        return ResponseEntity.accepted().body(response);
    }

    @GetMapping("/audit/{caseId}")
    @PreAuthorize("hasAnyRole('compliance:read', 'compliance:manage', 'compliance:admin')")
    public ResponseEntity<AuditTrailResponse> getAuditTrail(@PathVariable String caseId) {
        return ResponseEntity.ok(domainService.getAuditTrail(caseId));
    }

    @PostMapping("/jurisdiction-check")
    @PreAuthorize("hasAnyRole('compliance:manage', 'compliance:admin')")
    public ResponseEntity<JurisdictionCheckResponse> checkJurisdictionCompliance(
            @Valid @RequestBody JurisdictionCheckRequest request) {
        return ResponseEntity.ok(domainService.checkJurisdictionCompliance(request));
    }

    @PostMapping("/evidence")
    @PreAuthorize("hasRole('compliance:manage')")
    public ResponseEntity<EvidenceSubmissionResponse> submitEvidence(
            @Valid @RequestBody EvidenceSubmissionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(domainService.submitEvidence(request));
    }
}
