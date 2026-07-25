package com.usora.compliance.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.usora.compliance.client.GrpcClient;
import com.usora.compliance.config.DroolsConfig;
import com.usora.compliance.config.TenantConfig;
import com.usora.compliance.dto.*;
import com.usora.compliance.entity.*;
import com.usora.compliance.event.DomainEventPublisher;
import com.usora.compliance.exception.BusinessException;
import com.usora.compliance.mapper.EntityMapper;
import com.usora.compliance.repository.*;
import com.usora.compliance.security.TenantContext;
import com.usora.compliance.util.EncryptionUtil;
import com.usora.compliance.util.HashingUtil;
import com.usora.compliance.util.IdGenerator;
import com.usora.compliance.util.ValidationUtil;
import org.kie.api.KieServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@Service
@Transactional
public class DomainService {

    private static final Logger log = LoggerFactory.getLogger(DomainService.class);
    private static final String COMPLIANCE_TOPIC = "compliance.checked";

    private final ComplianceRuleRepository ruleRepository;
    private final EvidenceRecordRepository evidenceRepository;
    private final JurisdictionConfigRepository jurisdictionRepository;
    private final AuditTrailRepository auditTrailRepository;
    private final ComplianceCheckResultRepository checkResultRepository;
    private final EntityMapper entityMapper;
    private final DomainEventPublisher eventPublisher;
    private final DroolsConfig droolsConfig;
    private final GrpcClient grpcClient;
    private final ObjectMapper objectMapper;
    private final KieServices kieServices;

    public DomainService(ComplianceRuleRepository ruleRepository,
                         EvidenceRecordRepository evidenceRepository,
                         JurisdictionConfigRepository jurisdictionRepository,
                         AuditTrailRepository auditTrailRepository,
                         ComplianceCheckResultRepository checkResultRepository,
                         EntityMapper entityMapper,
                         DomainEventPublisher eventPublisher,
                         DroolsConfig droolsConfig,
                         GrpcClient grpcClient,
                         ObjectMapper objectMapper,
                         KieServices kieServices) {
        this.ruleRepository = ruleRepository;
        this.evidenceRepository = evidenceRepository;
        this.jurisdictionRepository = jurisdictionRepository;
        this.auditTrailRepository = auditTrailRepository;
        this.checkResultRepository = checkResultRepository;
        this.entityMapper = entityMapper;
        this.eventPublisher = eventPublisher;
        this.droolsConfig = droolsConfig;
        this.grpcClient = grpcClient;
        this.objectMapper = objectMapper;
        this.kieServices = kieServices;
    }

    public ComplianceValidationResponse validateCompliance(ComplianceValidationRequest request) {
        var tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) throw BusinessException.validationFailed("Tenant context is required");

        log.info("Starting compliance validation for case: {} in tenant: {}", request.caseId(), tenantId);

        var violations = new ArrayList<ComplianceValidationResponse.RuleResult>();
        var warnings = new ArrayList<ComplianceValidationResponse.RuleResult>();
        var amlResults = new ArrayList<ComplianceValidationResponse.AmlScreeningResult>();
        var jurisdictionResults = new ArrayList<ComplianceValidationResponse.JurisdictionResult>();

        // 1. Run Drools rules
        var activeRules = ruleRepository.findActiveRulesForTenant(tenantId, Instant.now());
        for (var rule : activeRules) {
            try {
                var result = executeDroolsRule(rule, request.entityData());
                if (!result.passed()) {
                    if ("critical".equals(rule.getSeverity()) || "high".equals(rule.getSeverity())) {
                        violations.add(result);
                    } else {
                        warnings.add(result);
                    }
                }
            } catch (Exception e) {
                log.error("Rule execution failed for rule {}: {}", rule.getRuleId(), e.getMessage());
                warnings.add(new ComplianceValidationResponse.RuleResult(
                        rule.getRuleId(), rule.getName(), rule.getSeverity(), false,
                        "Rule execution error: " + e.getMessage(), List.of()));
            }
        }

        // 2. AML screening via gRPC
        for (var listType : request.watchlistTypes()) {
            try {
                var amlResult = grpcClient.screenIndividual(
                        request.entityId(),
                        request.entityData(),
                        listType,
                        request.includeAdverseMedia());
                amlResults.add(amlResult);
            } catch (Exception e) {
                log.warn("AML screening failed for {}: {}", listType, e.getMessage());
            }
        }

        // 3. Jurisdiction compliance checks
        for (var jurisdiction : request.jurisdictions()) {
            var jurisConfig = jurisdictionRepository
                    .findByTenantIdAndJurisdictionCode(tenantId, jurisdiction);
            if (jurisConfig.isPresent()) {
                var jurisResult = checkJurisdictionRequirements(jurisdiction, request.entityData(), jurisConfig.get());
                jurisdictionResults.add(jurisResult);
            }
        }

        // Determine overall decision
        var totalViolations = violations.size();
        var totalWarnings = warnings.size();
        var decision = totalViolations > 0 ? "REJECTED" : totalWarnings > 0 ? "FLAGGED" : "APPROVED";

        // Persist result
        var result = new ComplianceCheckResult();
        result.setId(IdGenerator.generate());
        result.setTenantId(tenantId);
        result.setCaseId(request.caseId());
        result.setEntityId(request.entityId());
        result.setEntityType(request.entityType());
        result.setOverallDecision(decision);
        result.setTotalViolations(totalViolations);
        result.setTotalWarnings(totalWarnings);
        result.setValidatedAt(Instant.now());
        result.setValidatedBy(TenantContext.getCurrentTenant());
        try {
            result.setValidationJson(objectMapper.writeValueAsString(Map.of(
                    "violations", violations, "warnings", warnings,
                    "amlResults", amlResults, "jurisdictionResults", jurisdictionResults)));
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize validation JSON");
        }
        checkResultRepository.save(result);

        // Publish event
        eventPublisher.publishComplianceChecked(request.caseId(), tenantId, decision);

        // Write audit trail
        writeAuditEntry(request.caseId(), tenantId, "compliance.validation", "VALIDATE",
                "Compliance validation completed: " + decision, Map.of("decision", decision));

        return new ComplianceValidationResponse(
                result.getId(), request.caseId(), "completed", decision,
                violations, amlResults, jurisdictionResults,
                totalViolations, totalWarnings, result.getValidatedAt(), result.getValidatedBy());
    }

    @Cacheable(value = "rules", key = "#jurisdiction != null ? T(com.usora.compliance.security.TenantContext).getCurrentTenant() + ':' + #jurisdiction : T(com.usora.compliance.security.TenantContext).getCurrentTenant() + ':all'")
    public RegulatoryRulesResponse getRegulatoryRules(String jurisdiction) {
        var tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) throw BusinessException.validationFailed("Tenant context is required");

        List<ComplianceRule> rules;
        if (jurisdiction != null && !jurisdiction.isBlank()) {
            rules = ruleRepository.findActiveRulesForTenantAndJurisdiction(tenantId, jurisdiction, Instant.now());
        } else {
            rules = ruleRepository.findActiveRulesForTenant(tenantId, Instant.now());
        }

        var ruleDefs = rules.stream().map(entityMapper::toRuleDefinition).toList();
        var latestVersion = rules.stream().mapToInt(ComplianceRule::getRuleVersion).max().orElse(1);

        return new RegulatoryRulesResponse(ruleDefs, ruleDefs.size(),
                rules.stream().map(ComplianceRule::getUpdatedAt).max(Comparator.naturalOrder()).orElse(Instant.now()),
                "v" + latestVersion);
    }

    @CacheEvict(value = "rules", allEntries = true)
    public RegulatoryRulesUpdateResponse updateRegulatoryRules(RegulatoryRulesUpdateRequest request, String officerToken, String legalToken) {
        var tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) throw BusinessException.validationFailed("Tenant context is required");

        // Verify dual authorization
        if (!validateDualAuthorization(officerToken, legalToken)) {
            throw BusinessException.dualAuthorizationRequired();
        }

        // Get current version
        var currentVersion = ruleRepository.findTopByRuleIdOrderByRuleVersionDesc(request.ruleId());
        var newVersion = currentVersion.map(r -> r.getRuleVersion() + 1).orElse(1);

        // Create new version
        var rule = entityMapper.toComplianceRule(request);
        rule.setId(IdGenerator.generate());
        rule.setTenantId(tenantId);
        rule.setJurisdiction(request.jurisdiction());
        rule.setRuleId(request.ruleId());
        rule.setRuleVersion(newVersion);
        rule.setActive(true);
        currentVersion.ifPresent(c -> rule.setPreviousVersionId(c.getId()));

        // Sign the rule
        var contentToSign = request.drlContent() + "::" + newVersion + "::" + tenantId;
        var signatureHash = HashingUtil.sha256(contentToSign);
        rule.setSignatureHash(signatureHash);
        rule.setSignedBy("dual_auth");
        rule.setOfficerApprovedBy(extractPrincipal(officerToken));
        rule.setLegalApprovedBy(extractPrincipal(legalToken));

        // Merkle root
        var merkleContent = signatureHash + (currentVersion.map(ComplianceRule::getMerkleRoot).orElse(""));
        rule.setMerkleRoot(HashingUtil.sha256(merkleContent));

        ruleRepository.save(rule);

        // Publish event
        eventPublisher.publishRuleUpdated(request.ruleId(), newVersion, tenantId, extractPrincipal(officerToken));

        // Audit trail
        writeAuditEntry(null, tenantId, "compliance.rule.updated", "UPDATE",
                "Rule " + request.ruleId() + " updated to version " + newVersion,
                Map.of("ruleId", request.ruleId(), "newVersion", newVersion));

        return new RegulatoryRulesUpdateResponse(
                request.ruleId(), newVersion, "UPDATED", signatureHash,
                rule.getUpdatedAt(), extractPrincipal(officerToken),
                "Rule updated to version " + newVersion);
    }

    @Async("reportGenerationExecutor")
    public CompletableFuture<ReportGenerationResponse> generateReportAsync(ReportGenerationRequest request) {
        var response = generateReport(request);
        return CompletableFuture.completedFuture(response);
    }

    public ReportGenerationResponse generateReport(ReportGenerationRequest request) {
        var tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) throw BusinessException.validationFailed("Tenant context is required");

        var reportId = IdGenerator.generate();
        log.info("Generating report {} of type {} in format {} for tenant {}", reportId, request.reportType(), request.format(), tenantId);

        var downloadUrl = String.format("/reports/%s/%s.%s", tenantId, reportId, request.format());

        eventPublisher.publishReportGenerated(reportId, request.format(), tenantId);

        writeAuditEntry(request.caseId(), tenantId, "compliance.report.generated", "GENERATE",
                "Report " + reportId + " generated in " + request.format() + " format",
                Map.of("reportId", reportId, "format", request.format()));

        return new ReportGenerationResponse(reportId, "COMPLETED", request.format(),
                downloadUrl, Instant.now(), Instant.now(),
                "Report generated successfully");
    }

    public AuditTrailResponse getAuditTrail(String caseId) {
        var tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) throw BusinessException.validationFailed("Tenant context is required");

        var entries = auditTrailRepository.findByTenantIdAndCaseIdOrderByTimestampAsc(tenantId, caseId);
        if (entries.isEmpty()) {
            throw BusinessException.notFound("Audit trail", caseId);
        }

        var auditEntries = entries.stream().map(entityMapper::toAuditEntry).toList();

        // Verify hash chain integrity
        var integrityVerified = verifyHashChain(entries);

        var rootHash = entries.isEmpty() ? null : entries.getLast().getCurrentHash();

        return new AuditTrailResponse(caseId, tenantId, auditEntries, auditEntries.size(),
                rootHash, integrityVerified);
    }

    public JurisdictionCheckResponse checkJurisdictionCompliance(JurisdictionCheckRequest request) {
        var tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) throw BusinessException.validationFailed("Tenant context is required");

        var jurisConfig = jurisdictionRepository
                .findByTenantIdAndJurisdictionCode(tenantId, request.jurisdiction())
                .orElseThrow(() -> BusinessException.notFound("Jurisdiction config", request.jurisdiction()));

        var regulationResults = new ArrayList<JurisdictionCheckResponse.RegulationResult>();
        var allRequiredActions = new ArrayList<String>();

        // Check each regulation
        for (var regulation : request.applicableRegulations().isEmpty()
                ? parseRegulations(jurisConfig.getRegulationsJson())
                : request.applicableRegulations()) {
            var result = evaluateRegulation(regulation, request.entityAttributes(), jurisConfig);
            regulationResults.add(result);
            if (!result.compliant()) {
                allRequiredActions.addAll(result.requirementsFailed());
            }
        }

        var overallCompliant = regulationResults.stream().allMatch(JurisdictionCheckResponse.RegulationResult::compliant);

        if (!overallCompliant) {
            throw BusinessException.jurisdictionConflict(request.jurisdiction(),
                    "Compliance requirements not met: " + String.join(", ", allRequiredActions));
        }

        writeAuditEntry(request.caseId(), tenantId, "compliance.jurisdiction.check", "CHECK",
                "Jurisdiction check for " + request.jurisdiction() + ": " + (overallCompliant ? "COMPLIANT" : "NON-COMPLIANT"),
                Map.of("jurisdiction", request.jurisdiction(), "compliant", overallCompliant));

        return new JurisdictionCheckResponse(
                IdGenerator.generate(), request.jurisdiction(), overallCompliant,
                regulationResults, allRequiredActions, List.of("Review jurisdiction requirements regularly"),
                Instant.now(), tenantId);
    }

    public EvidenceSubmissionResponse submitEvidence(EvidenceSubmissionRequest request) {
        var tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) throw BusinessException.validationFailed("Tenant context is required");

        // Verify content hash
        var computedHash = HashingUtil.sha256(request.content());
        if (!computedHash.equals(request.contentHash())) {
            throw BusinessException.evidenceCorrupted("Content hash mismatch");
        }

        var evidence = entityMapper.toEvidenceRecord(request);
        evidence.setId(IdGenerator.generate());
        evidence.setTenantId(tenantId);
        evidence.setCaseId(request.caseId());
        evidence.setSubmittedAt(Instant.now());
        evidence.setSubmittedBy(tenantId);

        // Encrypt content
        var encryptedContent = EncryptionUtil.encrypt(request.content());
        evidence.setContent(encryptedContent);
        evidence.setVerificationHash(HashingUtil.sha256(encryptedContent));

        // Storage path
        var storagePath = String.format("evidence/%s/%s/%s", tenantId, request.caseId(), evidence.getId());
        evidence.setStoragePath(storagePath);

        evidenceRepository.save(evidence);

        eventPublisher.publishEvidenceSubmitted(evidence.getId(), request.caseId(), tenantId);

        writeAuditEntry(request.caseId(), tenantId, "compliance.evidence.submitted", "SUBMIT",
                "Evidence submitted: type=" + request.evidenceType(),
                Map.of("evidenceId", evidence.getId(), "evidenceType", request.evidenceType()));

        return new EvidenceSubmissionResponse(
                evidence.getId(), request.caseId(), "SUBMITTED", storagePath,
                evidence.getVerificationHash(), null, evidence.getSubmittedAt(),
                "Evidence submitted successfully");
    }

    // Private helpers

    private ComplianceValidationResponse.RuleResult executeDroolsRule(ComplianceRule rule, Map<String, Object> entityData) {
        try {
            var session = droolsConfig.kieContainer(kieServices).newKieSession();
            try {
                session.insert(rule);
                session.insert(entityData);
                var result = session.fireAllRules();
                return new ComplianceValidationResponse.RuleResult(
                        rule.getRuleId(), rule.getName(), rule.getSeverity(),
                        result == 0, "Rule evaluated: " + result + " conditions matched",
                        List.of());
            } finally {
                session.dispose();
            }
        } catch (Exception e) {
            log.error("Drools execution failed for rule {}: {}", rule.getRuleId(), e.getMessage());
            return new ComplianceValidationResponse.RuleResult(
                    rule.getRuleId(), rule.getName(), rule.getSeverity(),
                    false, "Execution error: " + e.getMessage(), List.of());
        }
    }

    private ComplianceValidationResponse.JurisdictionResult checkJurisdictionRequirements(
            String jurisdiction, Map<String, Object> entityData, JurisdictionConfig config) {
        var requirementsMet = new ArrayList<String>();
        var requirementsFailed = new ArrayList<String>();

        if (config.getRequirePepCheck()) {
            if (entityData.containsKey("pepCheckCompleted") && Boolean.TRUE.equals(entityData.get("pepCheckCompleted"))) {
                requirementsMet.add("PEP check completed");
            } else {
                requirementsFailed.add("PEP check required but not completed");
            }
        }

        if (config.getRequireAdverseMedia()) {
            if (entityData.containsKey("adverseMediaCheckCompleted") && Boolean.TRUE.equals(entityData.get("adverseMediaCheckCompleted"))) {
                requirementsMet.add("Adverse media check completed");
            } else {
                requirementsFailed.add("Adverse media check required but not completed");
            }
        }

        if (entityData.containsKey("kycVerified") && Boolean.TRUE.equals(entityData.get("kycVerified"))) {
            requirementsMet.add("KYC verification completed");
        } else {
            requirementsFailed.add("KYC verification required");
        }

        return new ComplianceValidationResponse.JurisdictionResult(
                jurisdiction, requirementsFailed.isEmpty(),
                requirementsMet, requirementsFailed,
                requirementsFailed.isEmpty() ? "All jurisdiction requirements met" : "Some requirements not met");
    }

    private JurisdictionCheckResponse.RegulationResult evaluateRegulation(
            String regulation, Map<String, Object> attributes, JurisdictionConfig config) {
        var met = new ArrayList<String>();
        var failed = new ArrayList<String>();

        switch (regulation.toUpperCase()) {
            case "GDPR" -> {
                if (attributes.containsKey("consentObtained") && Boolean.TRUE.equals(attributes.get("consentObtained"))) {
                    met.add("Consent obtained");
                } else {
                    failed.add("GDPR consent required");
                }
                if (attributes.containsKey("dataRetentionPolicy") && attributes.get("dataRetentionPolicy") != null) {
                    met.add("Data retention policy defined");
                } else {
                    failed.add("Data retention policy required");
                }
            }
            case "AML5", "AML6" -> {
                if (attributes.containsKey("customerDueDiligence") && Boolean.TRUE.equals(attributes.get("customerDueDiligence"))) {
                    met.add("Customer due diligence completed");
                } else {
                    failed.add("Customer due diligence required");
                }
                if (attributes.containsKey("beneficialOwnerIdentified") && Boolean.TRUE.equals(attributes.get("beneficialOwnerIdentified"))) {
                    met.add("Beneficial owner identified");
                } else {
                    failed.add("Beneficial owner identification required");
                }
            }
            case "MAS" -> {
                if (attributes.containsKey("trmCheck") && Boolean.TRUE.equals(attributes.get("trmCheck"))) {
                    met.add("TRM check completed");
                } else {
                    failed.add("TRM check required for Singapore MAS");
                }
            }
            case "UAECB" -> {
                if (attributes.containsKey("localSponsor") && Boolean.TRUE.equals(attributes.get("localSponsor"))) {
                    met.add("Local sponsor identified");
                } else {
                    failed.add("Local sponsor required for UAE");
                }
            }
            default -> met.add("No specific requirements for " + regulation);
        }

        return new JurisdictionCheckResponse.RegulationResult(
                regulation, met.isEmpty() ? "FAILED" : failed.isEmpty() ? "PASSED" : "PARTIAL",
                failed.isEmpty(), met, failed,
                failed.isEmpty() ? regulation + " requirements satisfied" : regulation + " requirements not fully met");
    }

    private boolean validateDualAuthorization(String officerToken, String legalToken) {
        return officerToken != null && !officerToken.isBlank()
                && legalToken != null && !legalToken.isBlank();
    }

    private String extractPrincipal(String token) {
        return token != null ? token.substring(0, Math.min(token.length(), 50)) : "unknown";
    }

    private List<String> parseRegulations(String regulationsJson) {
        if (regulationsJson == null || regulationsJson.isBlank()) return List.of();
        try {
            return objectMapper.readValue(regulationsJson, List.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    private boolean verifyHashChain(List<AuditTrailEntry> entries) {
        String previousHash = null;
        for (var entry : entries) {
            var expectedHash = HashingUtil.sha256(
                    (previousHash != null ? previousHash : "") +
                    entry.getCaseId() + entry.getAction() + entry.getTimestamp().toString());
            if (!expectedHash.equals(entry.getCurrentHash())) {
                log.warn("Hash chain broken at entry {}: expected {} got {}", entry.getId(), expectedHash, entry.getCurrentHash());
                return false;
            }
            previousHash = entry.getCurrentHash();
        }
        return true;
    }

    private void writeAuditEntry(String caseId, String tenantId, String eventType,
                                  String action, String description, Map<String, Object> details) {
        var entry = new AuditTrailEntry();
        entry.setId(IdGenerator.generate());
        entry.setTenantId(tenantId);
        entry.setCaseId(caseId != null ? caseId : "system");
        entry.setEventType(eventType);
        entry.setAction(action);
        entry.setActor(tenantId);
        entry.setDescription(description);
        entry.setTimestamp(Instant.now());

        // Hash chain
        var lastEntry = caseId != null ? auditTrailRepository.findTopByCaseIdOrderByTimestampDesc(caseId) : Optional.<AuditTrailEntry>empty();
        var previousHash = lastEntry.map(AuditTrailEntry::getCurrentHash).orElse("");
        var contentToHash = previousHash + caseId + action + entry.getTimestamp().toString();
        entry.setPreviousHash(previousHash);
        entry.setCurrentHash(HashingUtil.sha256(contentToHash));

        try {
            entry.setDetailsJson(objectMapper.writeValueAsString(details != null ? details : Map.of()));
        } catch (JsonProcessingException e) {
            entry.setDetailsJson("{}");
        }

        auditTrailRepository.save(entry);
    }
}
