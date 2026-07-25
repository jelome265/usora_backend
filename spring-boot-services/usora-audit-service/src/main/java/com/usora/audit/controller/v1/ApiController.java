package com.usora.audit.controller.v1;

import com.usora.audit.dto.RequestDto.*;
import com.usora.audit.dto.ResponseDto.*;
import com.usora.audit.service.DomainService;
import com.usora.audit.service.TenantAwareService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/audit")
public class ApiController {

    private final DomainService domainService;
    private final TenantAwareService tenantAwareService;

    public ApiController(DomainService domainService, TenantAwareService tenantAwareService) {
        this.domainService = domainService;
        this.tenantAwareService = tenantAwareService;
    }

    @PostMapping("/events")
    @PreAuthorize("hasAuthority('SCOPE_audit:write')")
    public ResponseEntity<AuditEventResponse> logEvent(@Valid @RequestBody AuditEventRequest request) {
        AuditEventResponse response = domainService.logEvent(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/trail/{entityType}/{entityId}")
    @PreAuthorize("hasAuthority('SCOPE_audit:read')")
    public ResponseEntity<AuditTrailResponse> getAuditTrail(
            @PathVariable String entityType,
            @PathVariable String entityId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        String tenantId = tenantAwareService.getCurrentTenantId();
        AuditTrailResponse response = domainService.getAuditTrail(tenantId, entityType, entityId, page, size);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/verify")
    @PreAuthorize("hasAuthority('SCOPE_audit:read')")
    public ResponseEntity<IntegrityResponse> verifyIntegrity(@Valid @RequestBody IntegrityVerificationRequest request) {
        IntegrityResponse response = domainService.verifyIntegrity(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/search")
    @PreAuthorize("hasAuthority('SCOPE_audit:read')")
    public ResponseEntity<SearchResponse> searchEvents(@Valid @RequestBody AuditSearchRequest request) {
        SearchResponse response = domainService.searchEvents(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/reports/compliance")
    @PreAuthorize("hasAuthority('SCOPE_audit:admin')")
    public ResponseEntity<ComplianceReportResponse> generateComplianceReport(
            @Valid @RequestBody ComplianceReportRequest request) {
        ComplianceReportResponse response = domainService.generateComplianceReport(request);
        return ResponseEntity.accepted().body(response);
    }

    @GetMapping("/tamper-alerts")
    @PreAuthorize("hasAuthority('SCOPE_audit:admin')")
    public ResponseEntity<List<TamperAlertResponse>> getTamperAlerts(
            @RequestParam(required = false) String tenantId,
            @RequestParam(defaultValue = "false") boolean resolved,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        List<TamperAlertResponse> alerts = domainService.getTamperAlerts(tenantId, resolved, page, size);
        return ResponseEntity.ok(alerts);
    }
}
