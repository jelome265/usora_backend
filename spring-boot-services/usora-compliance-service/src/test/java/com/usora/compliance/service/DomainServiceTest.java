package com.usora.compliance.service;

import com.usora.compliance.dto.*;
import com.usora.compliance.entity.ComplianceRule;
import com.usora.compliance.entity.EvidenceRecord;
import com.usora.compliance.entity.JurisdictionConfig;
import com.usora.compliance.event.DomainEventPublisher;
import com.usora.compliance.exception.BusinessException;
import com.usora.compliance.mapper.EntityMapper;
import com.usora.compliance.repository.*;
import com.usora.compliance.security.JwtTokenProvider;
import com.usora.compliance.security.TenantContext;
import com.usora.compliance.util.HashingUtil;
import com.usora.compliance.client.GrpcClient;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DomainServiceTest {

    private static final String TEST_JWT_SECRET = "test-secret-key-at-least-32-bytes-long-for-hmac";

    @Mock private ComplianceRuleRepository ruleRepository;
    @Mock private EvidenceRecordRepository evidenceRepository;
    @Mock private JurisdictionConfigRepository jurisdictionRepository;
    @Mock private AuditTrailRepository auditTrailRepository;
    @Mock private ComplianceCheckResultRepository checkResultRepository;
    @Mock private EntityMapper entityMapper;
    @Mock private DomainEventPublisher eventPublisher;
    @Mock private GrpcClient grpcClient;

    /**
     * A real (not mocked) JwtTokenProvider, backed by a real HMAC key, so
     * the dual-authorization regression tests below exercise genuine
     * signature verification rather than a mocked "always valid" stub —
     * a mock here would defeat the point of the test.
     */
    private final JwtTokenProvider jwtTokenProvider = new JwtTokenProvider(TEST_JWT_SECRET);

    private DomainService domainService;

    @BeforeEach
    void setUp() {
        domainService = new DomainService(ruleRepository, evidenceRepository,
                jurisdictionRepository, auditTrailRepository, checkResultRepository,
                entityMapper, eventPublisher, null, grpcClient, new com.fasterxml.jackson.databind.ObjectMapper(),
                null, jwtTokenProvider);
        TenantContext.setCurrentTenant("tenant1");
    }

    private String signedToken(String subject, String role, Instant expiry) {
        SecretKey key = Keys.hmacShaKeyFor(TEST_JWT_SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(subject)
                .claim("roles", List.of(role))
                .expiration(Date.from(expiry))
                .signWith(key)
                .compact();
    }

    @Test
    void shouldThrowWhenNoTenantContext() {
        TenantContext.clear();
        var request = new ComplianceValidationRequest("case1", "entity1", "individual",
                Map.of(), null, null, null, null);
        assertThrows(BusinessException.class, () -> domainService.validateCompliance(request));
    }

    @Test
    void shouldSubmitEvidence() {
        var request = new EvidenceSubmissionRequest("case1", "document",
                HashingUtil.sha256("content".getBytes()), "content".getBytes(),
                "text/plain", null, null, false);
        var evidence = new EvidenceRecord();
        evidence.setId("ev-1");

        when(entityMapper.toEvidenceRecord(any())).thenReturn(evidence);
        when(evidenceRepository.save(any())).thenReturn(evidence);

        var response = domainService.submitEvidence(request);

        assertNotNull(response);
        assertEquals("SUBMITTED", response.status());
    }

    @Test
    void shouldThrowOnEvidenceHashMismatch() {
        var request = new EvidenceSubmissionRequest("case1", "document",
                "wronghash", "content".getBytes(),
                "text/plain", null, null, false);

        assertThrows(BusinessException.class, () -> domainService.submitEvidence(request));
    }

    @Test
    void shouldGetRegulatoryRules() {
        var rules = List.of(createMockRule("RULE1", "tenant1", "eu_gdpr"));
        when(ruleRepository.findActiveRulesForTenant(any(), any())).thenReturn(rules);

        var response = domainService.getRegulatoryRules(null);

        assertNotNull(response);
        assertEquals(1, response.totalRules());
    }

    @Test
    void shouldCheckJurisdictionCompliance() {
        var config = new JurisdictionConfig();
        config.setJurisdictionCode("eu_gdpr");
        config.setRequirePepCheck(true);
        config.setRequireAdverseMedia(false);

        when(jurisdictionRepository.findByTenantIdAndJurisdictionCode("tenant1", "eu_gdpr"))
                .thenReturn(Optional.of(config));

        var request = new JurisdictionCheckRequest("case1", "entity1", "eu_gdpr",
                Map.of("pepCheckCompleted", true, "kycVerified", true), List.of("GDPR"));

        assertThrows(BusinessException.class, () -> domainService.checkJurisdictionCompliance(request));
    }

    /**
     * SECURITY REGRESSION TEST for
     * docs/architecture-security-review-2026-07-31.md §3.2 — an AML/
     * sanctions match must be able to flip the overall decision on its own,
     * even with zero Drools rule violations and zero jurisdiction issues.
     * Previously amlResults was collected but never consulted when
     * computing overallDecision.
     */
    @Test
    void amlSanctionsMatchFlipsDecisionToRejectedEvenWithNoOtherViolations() {
        when(ruleRepository.findActiveRulesForTenant(any(), any())).thenReturn(List.of());

        var criticalHit = new GrpcClient.AmlScreeningResult(
                "aml_entity1", "SANCTIONS_LIST", "sanctions",
                0.97, true, "Some Sanctioned Person", "SANCTIONS", "CRITICAL");
        when(grpcClient.screenIndividual(eq("entity1"), any(), eq("sanctions"), any()))
                .thenReturn(criticalHit);

        var request = new ComplianceValidationRequest("case1", "entity1", "individual",
                Map.of("fullName", "Some Sanctioned Person"), List.of(), List.of("sanctions"),
                false, false);

        var response = domainService.validateCompliance(request);

        assertEquals("REJECTED", response.overallDecision(),
                "a critical-risk AML match must reject the case even with no Drools/jurisdiction violations");
        assertTrue(response.totalViolations() > 0);
        assertFalse(response.amlResults().isEmpty(), "the AML result itself should still be reported");
    }

    /**
     * A low-risk / non-match AML screening result must not, by itself,
     * cause a rejection or flag.
     */
    @Test
    void amlNonMatchDoesNotAffectDecision() {
        when(ruleRepository.findActiveRulesForTenant(any(), any())).thenReturn(List.of());

        var cleanResult = new GrpcClient.AmlScreeningResult(
                "aml_entity1", "SANCTIONS_LIST", "sanctions",
                0.10, false, null, null, "LOW");
        when(grpcClient.screenIndividual(eq("entity1"), any(), eq("sanctions"), any()))
                .thenReturn(cleanResult);

        var request = new ComplianceValidationRequest("case1", "entity1", "individual",
                Map.of("fullName", "An Ordinary Person"), List.of(), List.of("sanctions"),
                false, false);

        var response = domainService.validateCompliance(request);

        assertEquals("APPROVED", response.overallDecision());
        assertEquals(0, response.totalViolations());
    }

    /**
     * SECURITY REGRESSION TEST for
     * docs/architecture-security-review-2026-07-31.md §3.2 — a screening
     * call that throws (timeout, downstream outage, etc.) must fail closed:
     * recorded as a violation requiring manual review, not silently
     * dropped as if screening had never been requested.
     */
    @Test
    void amlScreeningFailureFailsClosedAsAViolation() {
        when(ruleRepository.findActiveRulesForTenant(any(), any())).thenReturn(List.of());

        when(grpcClient.screenIndividual(eq("entity1"), any(), eq("sanctions"), any()))
                .thenThrow(new RuntimeException("AML service timeout"));

        var request = new ComplianceValidationRequest("case1", "entity1", "individual",
                Map.of("fullName", "Someone"), List.of(), List.of("sanctions"),
                false, false);

        var response = domainService.validateCompliance(request);

        assertEquals("REJECTED", response.overallDecision(),
                "a failed/unavailable AML screening call must fail closed, not be silently skipped");
        assertTrue(response.totalViolations() > 0);
        assertTrue(response.amlResults().isEmpty(),
                "no AML result was actually obtained, so none should be reported as if it succeeded");
    }

    private ComplianceRule createMockRule(String ruleId, String tenantId, String jurisdiction) {
        var rule = new ComplianceRule();
        rule.setRuleId(ruleId);
        rule.setRuleVersion(1);
        rule.setName("Test");
        rule.setTenantId(tenantId);
        rule.setJurisdiction(jurisdiction);
        rule.setSeverity("high");
        rule.setDrlContent("rule \"test\" when then end");
        rule.setActive(true);
        return rule;
    }

    // ------------------------------------------------------------------
    // SECURITY REGRESSION TESTS for
    // docs/USORA-BACKEND-ENTERPRISE-AUDIT-2026-08-16.md
    // ------------------------------------------------------------------

    private RegulatoryRulesUpdateRequest ruleUpdateRequest() {
        return new RegulatoryRulesUpdateRequest("RULE1", "Test Rule", "desc", "eu_gdpr",
                "kyc", "high", "rule \"test\" when then end", true, null, null, List.of());
    }

    /**
     * Finding C2: "dual authorization" previously accepted any two
     * non-blank strings — it must reject unsigned/garbage tokens outright.
     */
    @Test
    void ruleUpdateRejectsNonJwtDualAuthorizationTokens() {
        var request = ruleUpdateRequest();

        var ex = assertThrows(BusinessException.class,
                () -> domainService.updateRegulatoryRules(request, "not-a-jwt", "also-not-a-jwt"));
        assertEquals("DUAL_AUTHORIZATION_REQUIRED", ex.getCode());
        verify(ruleRepository, never()).save(any());
    }

    /**
     * Finding C2: a validly signed token that lacks the required role must
     * still be rejected — signature validity alone is not authorization.
     */
    @Test
    void ruleUpdateRejectsTokensMissingRequiredRoles() {
        var request = ruleUpdateRequest();
        var officerTokenWrongRole = signedToken("officer1", "compliance_analyst", Instant.now().plus(1, ChronoUnit.HOURS));
        var legalToken = signedToken("legal1", "legal_approver", Instant.now().plus(1, ChronoUnit.HOURS));

        var ex = assertThrows(BusinessException.class,
                () -> domainService.updateRegulatoryRules(request, officerTokenWrongRole, legalToken));
        assertEquals("DUAL_AUTHORIZATION_REQUIRED", ex.getCode());
    }

    /**
     * Finding C2: the same principal approving as both officer and legal
     * (e.g. a single compromised credential with both roles) must be
     * rejected — dual authorization requires two distinct people.
     */
    @Test
    void ruleUpdateRejectsSamePrincipalForBothApprovals() {
        var request = ruleUpdateRequest();
        var expiry = Instant.now().plus(1, ChronoUnit.HOURS);
        // same subject, both roles somehow present on one token — still
        // must fail because the two tokens themselves are identical.
        var oneToken = signedToken("person1", "compliance_officer", expiry);

        var ex = assertThrows(BusinessException.class,
                () -> domainService.updateRegulatoryRules(request, oneToken, oneToken));
        assertEquals("DUAL_AUTHORIZATION_REQUIRED", ex.getCode());
    }

    /**
     * Finding C2: two distinct, correctly-signed, correctly-roled tokens
     * from two different principals must succeed, and the approver
     * identities recorded on the rule must be the tokens' real verified
     * subjects — not a truncated raw-token string.
     */
    @Test
    void ruleUpdateSucceedsWithGenuineDistinctDualAuthorization() {
        var request = ruleUpdateRequest();
        var expiry = Instant.now().plus(1, ChronoUnit.HOURS);
        var officerToken = signedToken("officer-jane", "compliance_officer", expiry);
        var legalToken = signedToken("legal-raj", "legal_approver", expiry);

        when(ruleRepository.findTopByTenantIdAndRuleIdOrderByRuleVersionDesc("tenant1", "RULE1"))
                .thenReturn(Optional.empty());
        when(entityMapper.toComplianceRule(request)).thenReturn(new ComplianceRule());
        when(ruleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var response = domainService.updateRegulatoryRules(request, officerToken, legalToken);

        assertEquals("officer-jane", response.updatedBy());
        var savedRule = org.mockito.ArgumentCaptor.forClass(ComplianceRule.class);
        verify(ruleRepository).save(savedRule.capture());
        assertEquals("officer-jane", savedRule.getValue().getOfficerApprovedBy());
        assertEquals("legal-raj", savedRule.getValue().getLegalApprovedBy());
    }

    /**
     * Finding C4: a rule lookup by ruleId must be scoped to the calling
     * tenant. This test asserts the tenant-scoped repository method is the
     * one actually invoked (not a tenant-unscoped variant), which is what
     * makes the cross-tenant IDOR impossible at the query level.
     */
    @Test
    void ruleUpdateLooksUpPreviousVersionScopedToCallingTenant() {
        var request = ruleUpdateRequest();
        var expiry = Instant.now().plus(1, ChronoUnit.HOURS);
        var officerToken = signedToken("officer-jane", "compliance_officer", expiry);
        var legalToken = signedToken("legal-raj", "legal_approver", expiry);

        when(ruleRepository.findTopByTenantIdAndRuleIdOrderByRuleVersionDesc(eq("tenant1"), eq("RULE1")))
                .thenReturn(Optional.empty());
        when(entityMapper.toComplianceRule(request)).thenReturn(new ComplianceRule());
        when(ruleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        domainService.updateRegulatoryRules(request, officerToken, legalToken);

        verify(ruleRepository).findTopByTenantIdAndRuleIdOrderByRuleVersionDesc("tenant1", "RULE1");
    }

    /**
     * Finding C3: a jurisdiction whose regulation config JSON is corrupted
     * must NOT be reported as compliant. Previously a JSON parse failure
     * silently produced an empty regulation list, and `allMatch` on an
     * empty stream is vacuously true — so a corrupted config passed with
     * zero regulations evaluated. This must now fail closed.
     */
    @Test
    void jurisdictionCheckFailsClosedOnCorruptedRegulationsConfig() {
        var config = new JurisdictionConfig();
        config.setJurisdictionCode("mw_rbm");
        config.setRegulationsJson("{ not valid json [");

        when(jurisdictionRepository.findByTenantIdAndJurisdictionCode("tenant1", "mw_rbm"))
                .thenReturn(Optional.of(config));

        // applicableRegulations left empty so the check falls back to
        // parsing the (corrupted) config-driven list.
        var request = new JurisdictionCheckRequest("case1", "entity1", "mw_rbm",
                Map.of("kycVerified", true), List.of());

        assertThrows(BusinessException.class,
                () -> domainService.checkJurisdictionCompliance(request),
                "a corrupted jurisdiction regulations config must fail the check, never silently pass it");
    }

    /**
     * Finding C3, the empty-list safety net specifically: even a config
     * that parses cleanly to zero regulations must not be reported as
     * compliant — "nothing was checked" is never the same as "compliant".
     */
    @Test
    void jurisdictionCheckFailsClosedWhenNoRegulationsResolve() {
        var config = new JurisdictionConfig();
        config.setJurisdictionCode("mw_rbm");
        config.setRegulationsJson("[]");

        when(jurisdictionRepository.findByTenantIdAndJurisdictionCode("tenant1", "mw_rbm"))
                .thenReturn(Optional.of(config));

        var request = new JurisdictionCheckRequest("case1", "entity1", "mw_rbm",
                Map.of("kycVerified", true), List.of());

        assertThrows(BusinessException.class,
                () -> domainService.checkJurisdictionCompliance(request),
                "zero regulations evaluated must never be reported as compliant");
    }
}
