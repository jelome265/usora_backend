package com.usora.compliance.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import com.usora.compliance.client.GrpcClient;
import com.usora.compliance.config.DroolsConfig;
import com.usora.compliance.config.TenantConfig;
import com.usora.compliance.dto.*;
import com.usora.compliance.entity.*;
import com.usora.compliance.event.DomainEventPublisher;
import com.usora.compliance.exception.BusinessException;
import com.usora.compliance.mapper.EntityMapper;
import com.usora.compliance.repository.*;
import com.usora.compliance.security.JwtTokenProvider;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
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

    @org.springframework.beans.factory.annotation.Value("${compliance.security.rule-signing-secret}")
    private String ruleSigningSecret;

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
    private final JwtTokenProvider jwtTokenProvider;

    /** Role required on the officer-approval token for a rule-change dual authorization. */
    private static final String OFFICER_ROLE = "compliance_officer";
    /** Role required on the legal-approval token for a rule-change dual authorization. */
    private static final String LEGAL_ROLE = "legal_approver";

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
                         KieServices kieServices,
                         JwtTokenProvider jwtTokenProvider) {
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
        this.jwtTokenProvider = jwtTokenProvider;
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
        //
        // SECURITY/COMPLIANCE: a sanctions/PEP/watchlist hit here MUST be
        // able to block approval on its own — it cannot be allowed to be
        // purely informational, and a screening failure/timeout MUST fail
        // closed (recorded as a violation requiring manual review), never
        // silently skipped as if screening had passed clean.
        for (var listType : request.watchlistTypes()) {
            try {
                var amlResult = grpcClient.screenIndividual(
                        request.entityId(),
                        request.entityData(),
                        listType,
                        request.includeAdverseMedia());
                amlResults.add(amlResult);

                if (Boolean.TRUE.equals(amlResult.isMatch())) {
                    var ruleResult = new ComplianceValidationResponse.RuleResult(
                            "aml-screening:" + listType,
                            "AML/Watchlist match: " + listType,
                            severityForAmlRiskLevel(amlResult.riskLevel()),
                            false,
                            "Screening match against " + listType
                                    + (amlResult.matchedName() != null ? " for '" + amlResult.matchedName() + "'" : "")
                                    + " (risk level: " + amlResult.riskLevel() + ")",
                            List.of());

                    if ("critical".equals(ruleResult.severity()) || "high".equals(ruleResult.severity())) {
                        violations.add(ruleResult);
                    } else {
                        warnings.add(ruleResult);
                    }
                }
            } catch (Exception e) {
                log.error("AML screening failed for {}: {} — failing closed", listType, e.getMessage());
                // Fail closed: a screening call we couldn't complete is a
                // violation requiring manual review, not a pass-through.
                violations.add(new ComplianceValidationResponse.RuleResult(
                        "aml-screening:" + listType,
                        "AML/Watchlist screening unavailable: " + listType,
                        "high",
                        false,
                        "Screening could not be completed for " + listType
                                + " — treated as unresolved and requires manual review: " + e.getMessage(),
                        List.of()));
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
        var currentVersion = ruleRepository.findTopByTenantIdAndRuleIdOrderByRuleVersionDesc(tenantId, request.ruleId());
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

        // Sign the rule.
        // SECURITY: this MUST be a keyed HMAC, not a bare hash of public
        // data (rule content + version + tenant ID are all knowable to
        // anyone) — a bare hash proves nothing about who approved the
        // change and can be trivially recomputed/forged. The HMAC secret
        // is held only by this service (sourced from Vault/KMS in prod).
        var contentToSign = request.drlContent() + "::" + newVersion + "::" + tenantId;
        var signatureHash = HashingUtil.hmacSha256(contentToSign, ruleSigningSecret);
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

        var regulationsToCheck = request.applicableRegulations().isEmpty()
                ? parseRegulations(jurisConfig.getRegulationsJson(), request.jurisdiction())
                : request.applicableRegulations();

        // SECURITY/COMPLIANCE: a jurisdiction check that evaluates zero
        // regulations must never be reported as compliant — an empty
        // regulation list makes `allMatch` vacuously true below, which
        // would otherwise silently pass a jurisdiction whose config has
        // nothing configured to check. Fail closed instead.
        if (regulationsToCheck.isEmpty()) {
            writeAuditEntry(request.caseId(), tenantId, "compliance.jurisdiction.check", "CHECK",
                    "Jurisdiction check for " + request.jurisdiction()
                            + ": no regulations resolved to evaluate — treated as non-compliant pending manual review",
                    Map.of("jurisdiction", request.jurisdiction(), "compliant", false));
            throw BusinessException.jurisdictionConflict(request.jurisdiction(),
                    "No applicable regulations were resolved for this jurisdiction — cannot certify compliance "
                            + "with zero regulations evaluated. Verify the jurisdiction's regulation configuration.");
        }

        var regulationResults = new ArrayList<JurisdictionCheckResponse.RegulationResult>();
        var allRequiredActions = new ArrayList<String>();

        // Check each regulation
        for (var regulation : regulationsToCheck) {
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

    /**
     * Maps an AML screening result's risk level to the severity taxonomy
     * used elsewhere in this service ("critical"/"high" -> violation,
     * anything else -> warning). Defaults to "high" (fail toward stricter
     * handling) for unrecognized/missing risk levels rather than silently
     * downgrading an unrecognized value to a warning.
     */
    private String severityForAmlRiskLevel(String riskLevel) {
        if (riskLevel == null) {
            return "high";
        }
        return switch (riskLevel.toLowerCase(Locale.ROOT)) {
            case "critical" -> "critical";
            case "high" -> "high";
            case "medium", "moderate" -> "medium";
            case "low" -> "low";
            default -> "high";
        };
    }

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

    /**
     * SECURITY: dual authorization for a regulatory rule change requires two
     * independently signature-verified tokens, each carrying the specific
     * role required of its approver, belonging to two different principals.
     * A non-empty string is not authorization — every one of these checks
     * must hold, or the change is rejected:
     *   1. officerToken is a valid, signature-verified JWT
     *   2. legalToken is a valid, signature-verified JWT
     *   3. officerToken carries the compliance-officer role
     *   4. legalToken carries the legal-approver role
     *   5. the two tokens' subjects (principals) are different people
     */
    private boolean validateDualAuthorization(String officerToken, String legalToken) {
        var officerClaims = jwtTokenProvider.parseVerifiedClaims(officerToken);
        var legalClaims = jwtTokenProvider.parseVerifiedClaims(legalToken);

        if (officerClaims.isEmpty() || legalClaims.isEmpty()) {
            return false;
        }
        if (!jwtTokenProvider.hasVerifiedRole(officerToken, OFFICER_ROLE)) {
            return false;
        }
        if (!jwtTokenProvider.hasVerifiedRole(legalToken, LEGAL_ROLE)) {
            return false;
        }

        var officerSubject = officerClaims.get().getSubject();
        var legalSubject = legalClaims.get().getSubject();

        return officerSubject != null
                && legalSubject != null
                && !officerSubject.equals(legalSubject);
    }

    /**
     * Extracts the verified principal (JWT subject) from a token that has
     * already passed {@link #validateDualAuthorization}. Never falls back to
     * an unverified value — if the token doesn't parse/verify at this point
     * (it should always be the same token already validated above), the
     * caller must not silently attribute the action to "unknown"; the
     * approval flow requires a real, verified identity for the audit trail.
     */
    private String extractPrincipal(String token) {
        return jwtTokenProvider.parseVerifiedClaims(token)
                .map(Claims::getSubject)
                .filter(subject -> subject != null && !subject.isBlank())
                .orElseThrow(() -> BusinessException.dualAuthorizationRequired());
    }

    /**
     * SECURITY/COMPLIANCE: an absent/blank config (no regulations
     * configured at all) legitimately resolves to an empty list — the
     * caller's empty-list safety net in {@link #checkJurisdictionCompliance}
     * still fails that closed. A config value that IS present but fails to
     * parse is different: that's corrupted data, not "nothing configured",
     * and must fail loudly rather than being silently downgraded to the
     * same empty list. Swallowing the parse exception here previously made
     * a corrupted config indistinguishable from "no regulations apply",
     * which let jurisdiction checks silently pass with zero regulations
     * evaluated.
     */
    private List<String> parseRegulations(String regulationsJson, String jurisdiction) {
        if (regulationsJson == null || regulationsJson.isBlank()) return List.of();
        try {
            return objectMapper.readValue(regulationsJson, List.class);
        } catch (Exception e) {
            log.error("Failed to parse regulations config for jurisdiction {}: {}", jurisdiction, e.getMessage());
            throw BusinessException.jurisdictionConfigCorrupted(jurisdiction, e.getMessage());
        }
    }

    /**
     * SECURITY: builds the exact string that {@link #writeAuditEntry} hashes
     * into an entry's currentHash. This MUST cover every field an
     * investigator would actually rely on (tenant, event type, actor,
     * description, and the details payload) — not just caseId/action/
     * timestamp — or the hash chain "verifies" while leaving the fields
     * that matter (what happened, who did it) unprotected against a direct
     * database edit. Both the writer and the verifier must build this the
     * same way; keep them in this one shared method rather than duplicating
     * the concatenation logic.
     */
    private String buildAuditHashContent(String previousHash, AuditTrailEntry entry) {
        return (previousHash != null ? previousHash : "")
                + entry.getTenantId()
                + "::" + entry.getCaseId()
                + "::" + entry.getEventType()
                + "::" + entry.getAction()
                + "::" + entry.getActor()
                + "::" + entry.getDescription()
                + "::" + entry.getDetailsJson()
                + "::" + entry.getTimestamp().toString();
    }

    private boolean verifyHashChain(List<AuditTrailEntry> entries) {
        String previousHash = null;
        for (var entry : entries) {
            var expectedHash = HashingUtil.sha256(buildAuditHashContent(previousHash, entry));
            if (!expectedHash.equals(entry.getCurrentHash())) {
                log.warn("Hash chain broken at entry {}: expected {} got {}", entry.getId(), expectedHash, entry.getCurrentHash());
                return false;
            }
            previousHash = entry.getCurrentHash();
        }
        return true;
    }

    /**
     * SECURITY: this audit trail's "actor" field previously was always set
     * to the tenant id, not the individual principal who actually performed
     * the action -- meaning every audit entry for a tenant looked like it
     * was performed by "the tenant" itself, with no way to attribute an
     * action to a specific user/service account, which is the entire point
     * of forensic attribution. Resolves the authenticated JWT subject
     * (already verified by this service's resource-server filter chain by
     * the time a request reaches this method) and falls back to "system"
     * only when there is genuinely no authenticated caller (e.g. an
     * internally-scheduled job), never to the tenant id.
     */
    private String resolveActor() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            var subject = jwt.getSubject();
            if (subject != null && !subject.isBlank()) {
                return subject;
            }
        }
        return "system";
    }

    private void writeAuditEntry(String caseId, String tenantId, String eventType,
                                  String action, String description, Map<String, Object> details) {
        var entry = new AuditTrailEntry();
        entry.setId(IdGenerator.generate());
        entry.setTenantId(tenantId);
        entry.setCaseId(caseId != null ? caseId : "system");
        entry.setEventType(eventType);
        entry.setAction(action);
        entry.setActor(resolveActor());
        entry.setDescription(description);
        entry.setTimestamp(Instant.now());

        try {
            entry.setDetailsJson(objectMapper.writeValueAsString(details != null ? details : Map.of()));
        } catch (JsonProcessingException e) {
            entry.setDetailsJson("{}");
        }

        // Hash chain — computed AFTER every hashed field (including
        // detailsJson) is set on the entry, so the persisted hash actually
        // covers the full content. See buildAuditHashContent for what's
        // included and why.
        var lastEntry = caseId != null ? auditTrailRepository.findTopByTenantIdAndCaseIdOrderByTimestampDesc(tenantId, caseId) : Optional.<AuditTrailEntry>empty();
        var previousHash = lastEntry.map(AuditTrailEntry::getCurrentHash).orElse("");
        entry.setPreviousHash(previousHash);
        entry.setCurrentHash(HashingUtil.sha256(buildAuditHashContent(previousHash, entry)));

        auditTrailRepository.save(entry);
    }
}
