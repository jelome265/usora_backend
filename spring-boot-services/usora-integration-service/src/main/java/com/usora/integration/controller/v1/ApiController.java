package com.usora.integration.controller.v1;

import com.usora.integration.dto.RequestDto;
import com.usora.integration.dto.ResponseDto;
import com.usora.integration.security.TenantContext;
import com.usora.integration.service.DomainService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
public class ApiController {

    private static final Logger log = LoggerFactory.getLogger(ApiController.class);

    private final DomainService domainService;

    public ApiController(DomainService domainService) {
        this.domainService = domainService;
    }

    // ========================================================================
    // ADMIN WEBHOOK CONFIG ENDPOINTS
    // ========================================================================

    @PostMapping("/v1/admin/integrations")
    @PreAuthorize("hasAuthority('SCOPE_integration:admin')")
    public ResponseEntity<ResponseDto.WebhookConfigResponse> createWebhookConfig(
            @Valid @RequestBody RequestDto.WebhookConfigRequest request) {
        String tenantId = TenantContext.getRequiredCurrentTenant();
        ResponseDto.WebhookConfigResponse response = domainService.createWebhookConfig(tenantId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/v1/admin/integrations")
    @PreAuthorize("hasAuthority('SCOPE_integration:admin')")
    public ResponseEntity<List<ResponseDto.WebhookConfigResponse>> listWebhookConfigs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        String tenantId = TenantContext.getRequiredCurrentTenant();
        List<ResponseDto.WebhookConfigResponse> configs = domainService.listWebhookConfigs(tenantId, page, size);
        return ResponseEntity.ok(configs);
    }

    @GetMapping("/v1/admin/integrations/{id}")
    @PreAuthorize("hasAuthority('SCOPE_integration:admin')")
    public ResponseEntity<ResponseDto.WebhookConfigResponse> getWebhookConfig(@PathVariable UUID id) {
        String tenantId = TenantContext.getRequiredCurrentTenant();
        ResponseDto.WebhookConfigResponse response = domainService.getWebhookConfig(tenantId, id);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/v1/admin/integrations/{id}")
    @PreAuthorize("hasAuthority('SCOPE_integration:admin')")
    public ResponseEntity<ResponseDto.WebhookConfigResponse> updateWebhookConfig(
            @PathVariable UUID id,
            @Valid @RequestBody RequestDto.WebhookConfigRequest request) {
        String tenantId = TenantContext.getRequiredCurrentTenant();
        ResponseDto.WebhookConfigResponse response = domainService.updateWebhookConfig(tenantId, id, request);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/v1/admin/integrations/{id}")
    @PreAuthorize("hasAuthority('SCOPE_integration:admin')")
    public ResponseEntity<Void> deleteWebhookConfig(@PathVariable UUID id) {
        String tenantId = TenantContext.getRequiredCurrentTenant();
        domainService.deleteWebhookConfig(tenantId, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/v1/admin/integrations/{id}/replay")
    @PreAuthorize("hasAuthority('SCOPE_integration:admin')")
    public ResponseEntity<ResponseDto.ReplayResponse> replayWebhookEvents(@PathVariable UUID id) {
        String tenantId = TenantContext.getRequiredCurrentTenant();
        ResponseDto.ReplayResponse response = domainService.replayWebhookEvents(tenantId, id);
        return ResponseEntity.ok(response);
    }

    // ========================================================================
    // INTEGRATION PROVIDER ADMIN ENDPOINTS
    // ========================================================================

    @PostMapping("/v1/admin/providers")
    @PreAuthorize("hasAuthority('SCOPE_integration:admin')")
    public ResponseEntity<ResponseDto.IntegrationProviderResponse> createProvider(
            @Valid @RequestBody RequestDto.IntegrationProviderRequest request) {
        String tenantId = TenantContext.getRequiredCurrentTenant();
        ResponseDto.IntegrationProviderResponse response = domainService.createProvider(tenantId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/v1/admin/providers")
    @PreAuthorize("hasAuthority('SCOPE_integration:admin')")
    public ResponseEntity<List<ResponseDto.IntegrationProviderResponse>> listProviders(
            @RequestParam(required = false) String providerType) {
        String tenantId = TenantContext.getRequiredCurrentTenant();
        List<ResponseDto.IntegrationProviderResponse> providers = domainService.listProviders(tenantId, providerType);
        return ResponseEntity.ok(providers);
    }

    // ========================================================================
    // BANKING ENDPOINTS
    // ========================================================================

    @PostMapping("/api/v1/banking/link")
    @PreAuthorize("hasAuthority('SCOPE_banking:access')")
    public ResponseEntity<ResponseDto.BankingLinkResponse> initiateAccountLinking(
            @Valid @RequestBody RequestDto.BankingLinkRequest request) {
        String tenantId = TenantContext.getRequiredCurrentTenant();
        ResponseDto.BankingLinkResponse response = domainService.initiateAccountLinking(tenantId, request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/api/v1/banking/verify")
    @PreAuthorize("hasAuthority('SCOPE_banking:access')")
    public ResponseEntity<ResponseDto.BankingVerifyResponse> verifyAccount(
            @Valid @RequestBody RequestDto.BankingVerifyRequest request) {
        String tenantId = TenantContext.getRequiredCurrentTenant();
        ResponseDto.BankingVerifyResponse response = domainService.verifyAccount(tenantId, request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/api/v1/banking/transactions")
    @PreAuthorize("hasAuthority('SCOPE_banking:access')")
    public ResponseEntity<ResponseDto.BankingTransactionResponse> getTransactions(
            @Valid @RequestBody RequestDto.BankingTransactionRequest request) {
        String tenantId = TenantContext.getRequiredCurrentTenant();
        ResponseDto.BankingTransactionResponse response = domainService.getTransactions(tenantId, request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/api/v1/banking/income")
    @PreAuthorize("hasAuthority('SCOPE_banking:access')")
    public ResponseEntity<ResponseDto.BankingIncomeResponse> verifyIncome(
            @Valid @RequestBody RequestDto.BankingIncomeRequest request) {
        String tenantId = TenantContext.getRequiredCurrentTenant();
        ResponseDto.BankingIncomeResponse response = domainService.verifyIncome(tenantId, request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/api/v1/banking/balance")
    @PreAuthorize("hasAuthority('SCOPE_banking:access')")
    public ResponseEntity<ResponseDto.BankingBalanceResponse> getBalance(
            @Valid @RequestBody RequestDto.BankingBalanceRequest request) {
        String tenantId = TenantContext.getRequiredCurrentTenant();
        ResponseDto.BankingBalanceResponse response = domainService.getBalance(tenantId, request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/api/v1/banking/disconnect")
    @PreAuthorize("hasAuthority('SCOPE_banking:access')")
    public ResponseEntity<ResponseDto.BankingDisconnectResponse> disconnectAccount(
            @Valid @RequestBody RequestDto.BankingDisconnectRequest request) {
        String tenantId = TenantContext.getRequiredCurrentTenant();
        ResponseDto.BankingDisconnectResponse response = domainService.disconnectAccount(tenantId, request);
        return ResponseEntity.ok(response);
    }

    // ========================================================================
    // GOVERNMENT VERIFICATION ENDPOINTS
    // ========================================================================

    @PostMapping("/api/v1/government/eidas")
    @PreAuthorize("hasAuthority('SCOPE_government:access')")
    public ResponseEntity<ResponseDto.GovernmentVerificationResponse> verifyEidas(
            @Valid @RequestBody RequestDto.GovernmentVerificationRequest request) {
        request.setVerificationType(RequestDto.GovernmentVerificationType.EIDAS);
        String tenantId = TenantContext.getRequiredCurrentTenant();
        ResponseDto.GovernmentVerificationResponse response = domainService.verifyGovernment(tenantId, request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/api/v1/government/aadhaar")
    @PreAuthorize("hasAuthority('SCOPE_government:access')")
    public ResponseEntity<ResponseDto.GovernmentVerificationResponse> verifyAadhaar(
            @Valid @RequestBody RequestDto.GovernmentVerificationRequest request) {
        request.setVerificationType(RequestDto.GovernmentVerificationType.AADHAAR);
        String tenantId = TenantContext.getRequiredCurrentTenant();
        ResponseDto.GovernmentVerificationResponse response = domainService.verifyGovernment(tenantId, request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/api/v1/government/dmv")
    @PreAuthorize("hasAuthority('SCOPE_government:access')")
    public ResponseEntity<ResponseDto.GovernmentVerificationResponse> verifyDmv(
            @Valid @RequestBody RequestDto.GovernmentVerificationRequest request) {
        request.setVerificationType(RequestDto.GovernmentVerificationType.DMV);
        String tenantId = TenantContext.getRequiredCurrentTenant();
        ResponseDto.GovernmentVerificationResponse response = domainService.verifyGovernment(tenantId, request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/api/v1/government/passport")
    @PreAuthorize("hasAuthority('SCOPE_government:access')")
    public ResponseEntity<ResponseDto.GovernmentVerificationResponse> verifyPassport(
            @Valid @RequestBody RequestDto.GovernmentVerificationRequest request) {
        request.setVerificationType(RequestDto.GovernmentVerificationType.PASSPORT);
        String tenantId = TenantContext.getRequiredCurrentTenant();
        ResponseDto.GovernmentVerificationResponse response = domainService.verifyGovernment(tenantId, request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/api/v1/government/status/{verificationId}")
    @PreAuthorize("hasAuthority('SCOPE_government:access')")
    public ResponseEntity<ResponseDto.VerificationStatusResponse> getVerificationStatus(
            @PathVariable UUID verificationId) {
        String tenantId = TenantContext.getRequiredCurrentTenant();
        ResponseDto.VerificationStatusResponse response = domainService.getVerificationStatus(tenantId, verificationId);
        return ResponseEntity.ok(response);
    }

    // ========================================================================
    // CREDIT BUREAU ENDPOINTS
    // ========================================================================

    @PostMapping("/api/v1/credit/verify")
    @PreAuthorize("hasAuthority('SCOPE_credit:access')")
    public ResponseEntity<ResponseDto.CreditVerificationResponse> verifyIdentity(
            @Valid @RequestBody RequestDto.CreditVerificationRequest request) {
        String tenantId = TenantContext.getRequiredCurrentTenant();
        ResponseDto.CreditVerificationResponse response = domainService.verifyIdentity(tenantId, request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/api/v1/credit/report")
    @PreAuthorize("hasAuthority('SCOPE_credit:access')")
    public ResponseEntity<ResponseDto.CreditReportResponse> getCreditReport(
            @Valid @RequestBody RequestDto.CreditReportRequest request) {
        String tenantId = TenantContext.getRequiredCurrentTenant();
        ResponseDto.CreditReportResponse response = domainService.getCreditReport(tenantId, request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/api/v1/credit/fraud-check")
    @PreAuthorize("hasAuthority('SCOPE_credit:access')")
    public ResponseEntity<ResponseDto.CreditFraudCheckResponse> checkFraud(
            @Valid @RequestBody RequestDto.CreditFraudCheckRequest request) {
        String tenantId = TenantContext.getRequiredCurrentTenant();
        ResponseDto.CreditFraudCheckResponse response = domainService.checkFraud(tenantId, request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/api/v1/credit/alternative")
    @PreAuthorize("hasAuthority('SCOPE_credit:access')")
    public ResponseEntity<ResponseDto.CreditAlternativeDataResponse> getAlternativeData(
            @Valid @RequestBody RequestDto.CreditAlternativeDataRequest request) {
        String tenantId = TenantContext.getRequiredCurrentTenant();
        ResponseDto.CreditAlternativeDataResponse response = domainService.getAlternativeData(tenantId, request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/api/v1/credit/score")
    @PreAuthorize("hasAuthority('SCOPE_credit:access')")
    public ResponseEntity<ResponseDto.CreditScoreResponse> getCreditScore(
            @Valid @RequestBody RequestDto.CreditScoreRequest request) {
        String tenantId = TenantContext.getRequiredCurrentTenant();
        ResponseDto.CreditScoreResponse response = domainService.getCreditScore(tenantId, request);
        return ResponseEntity.ok(response);
    }
}
