package com.usora.core.controller.v1;

import com.usora.core.dto.RequestDto.KYCSubmissionRequest;
import com.usora.core.dto.RequestDto.CaseStatusUpdateRequest;
import com.usora.core.dto.RequestDto.TenantConfigUpdateRequest;
import com.usora.core.dto.ResponseDto.CaseResponse;
import com.usora.core.dto.ResponseDto.KYCStatusResponse;
import com.usora.core.dto.ResponseDto.KYCSubmissionResponse;
import com.usora.core.dto.ResponseDto.TenantConfigResponse;
import com.usora.core.service.DomainService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class ApiController {

    private final DomainService domainService;

    public ApiController(DomainService domainService) {
        this.domainService = domainService;
    }

    @PostMapping("/kyc/submit")
    @PreAuthorize("hasAuthority('SCOPE_kyc:submit')")
    public ResponseEntity<KYCSubmissionResponse> submitKYC(@Valid @RequestBody KYCSubmissionRequest request) {
        var response = domainService.submitKYC(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/kyc/{caseId}/status")
    @PreAuthorize("hasAuthority('SCOPE_kyc:read')")
    public ResponseEntity<KYCStatusResponse> getKYCStatus(@PathVariable UUID caseId) {
        var response = domainService.getKYCStatus(caseId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/kyc/{caseId}/cancel")
    @PreAuthorize("hasAuthority('SCOPE_kyc:submit')")
    public ResponseEntity<Void> cancelKYC(@PathVariable UUID caseId) {
        domainService.cancelKYC(caseId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/cases")
    @PreAuthorize("hasAuthority('SCOPE_case:manage')")
    public ResponseEntity<Page<CaseResponse>> listCases(Pageable pageable) {
        var cases = domainService.listCases(pageable);
        return ResponseEntity.ok(cases);
    }

    @GetMapping("/cases/{caseId}")
    @PreAuthorize("hasAuthority('SCOPE_case:manage')")
    public ResponseEntity<CaseResponse> getCaseDetails(@PathVariable UUID caseId) {
        var response = domainService.getCaseDetails(caseId);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/cases/{caseId}/status")
    @PreAuthorize("hasAuthority('SCOPE_case:manage')")
    public ResponseEntity<CaseResponse> updateCaseStatus(
            @PathVariable UUID caseId,
            @Valid @RequestBody CaseStatusUpdateRequest request) {
        var response = domainService.updateCaseStatus(caseId, request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/tenants/{tenantId}/config")
    @PreAuthorize("hasAuthority('SCOPE_tenant:admin')")
    public ResponseEntity<TenantConfigResponse> getTenantConfig(@PathVariable String tenantId) {
        var response = domainService.getTenantConfig(tenantId);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/tenants/{tenantId}/config")
    @PreAuthorize("hasAuthority('SCOPE_tenant:admin')")
    public ResponseEntity<TenantConfigResponse> updateTenantConfig(
            @PathVariable String tenantId,
            @Valid @RequestBody TenantConfigUpdateRequest request) {
        var response = domainService.updateTenantConfig(tenantId, request);
        return ResponseEntity.ok(response);
    }
}
