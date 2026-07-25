package com.usora.tenant.controller.v1;

import com.usora.tenant.dto.ConfigUpdateRequest;
import com.usora.tenant.dto.OnboardRequest;
import com.usora.tenant.dto.SuspendRequest;
import com.usora.tenant.dto.TenantListResponse;
import com.usora.tenant.dto.TenantResponse;
import com.usora.tenant.service.DomainService;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants")
public class ApiController {

    private final DomainService domainService;

    public ApiController(DomainService domainService) {
        this.domainService = domainService;
    }

    @PostMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<TenantResponse> onboardTenant(@Valid @RequestBody OnboardRequest request) {
        TenantResponse response = domainService.onboardTenant(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{tenantId}")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN', 'TENANT_ADMIN')")
    public ResponseEntity<TenantResponse> getTenant(@PathVariable UUID tenantId) {
        TenantResponse response = domainService.getTenant(tenantId);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<TenantListResponse> listTenants(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String plan) {
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by("createdAt").descending());
        TenantListResponse response = domainService.listTenants(pageRequest, status, plan);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{tenantId}/config")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<TenantResponse> updateConfig(
            @PathVariable UUID tenantId,
            @Valid @RequestBody ConfigUpdateRequest request) {
        TenantResponse response = domainService.updateConfig(tenantId, request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{tenantId}/suspend")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<TenantResponse> suspendTenant(
            @PathVariable UUID tenantId,
            @Valid @RequestBody SuspendRequest request) {
        TenantResponse response = domainService.suspendTenant(tenantId, request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{tenantId}/resume")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<TenantResponse> resumeTenant(@PathVariable UUID tenantId) {
        TenantResponse response = domainService.resumeTenant(tenantId);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{tenantId}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<Void> offboardTenant(@PathVariable UUID tenantId) {
        domainService.offboardTenant(tenantId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{tenantId}/status")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN', 'TENANT_ADMIN')")
    public ResponseEntity<TenantResponse> getTenantStatus(@PathVariable UUID tenantId) {
        TenantResponse response = domainService.getTenant(tenantId);
        return ResponseEntity.ok(response);
    }
}
