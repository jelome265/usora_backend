package com.usora.compliance.grpc;

import com.usora.compliance.exception.BusinessException;
import com.usora.compliance.service.DomainService;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * gRPC facade for {@link DomainService}, implementing the ComplianceService
 * contract declared in compliance.proto.
 *
 * SECURITY/FINDING 3.4: this server-side implementation did not exist prior
 * to this change -- ComplianceService was declared in this module's own
 * compliance.proto (with Maven codegen already wired up via
 * protoc-jar-maven-plugin in pom.xml), and the API gateway already has a
 * fully-built typed client for it (rust-services/usora-api-gateway's
 * GrpcClients.compliance), but nothing on this side ever implemented the
 * server, so any call the gateway made to this service would fail with
 * UNIMPLEMENTED. This class is a thin mapping layer only: every RPC
 * delegates to the exact same DomainService methods the REST controller
 * already uses, so no new business logic is introduced here.
 *
 * This class deliberately lives in the same package as the proto-generated
 * message/service classes (see compliance.proto's java_package option) so
 * those types can be referenced unqualified; DTO types from
 * com.usora.compliance.dto are referenced fully-qualified inline instead,
 * since several of them share a simple name with a generated proto message
 * in this package (e.g. ComplianceValidationRequest exists in both
 * packages) and importing both under the same simple name is not possible.
 */
@GrpcService
@RequiredArgsConstructor
@Slf4j
public class ComplianceGrpcService extends ComplianceServiceGrpc.ComplianceServiceImplBase {

    private final DomainService domainService;

    @Override
    public void validateCompliance(ComplianceValidationRequest request,
                                    StreamObserver<ComplianceValidationResponse> responseObserver) {
        try {
            var dtoRequest = new com.usora.compliance.dto.ComplianceValidationRequest(
                    request.getCaseId(),
                    request.getEntityId(),
                    request.getEntityType(),
                    stringMapToObjectMap(request.getEntityDataMap()),
                    request.getJurisdictionsList(),
                    request.getWatchlistTypesList(),
                    request.getIncludeAdverseMedia(),
                    request.getIncludeTransactionScreening());

            var result = domainService.validateCompliance(dtoRequest);

            var response = ComplianceValidationResponse.newBuilder()
                    .setValidationId(nullToEmpty(result.validationId()))
                    .setCaseId(nullToEmpty(result.caseId()))
                    .setStatus(nullToEmpty(result.status()))
                    .setOverallDecision(nullToEmpty(result.overallDecision()))
                    .addAllRuleResults(result.ruleResults().stream().map(this::toProtoRuleResult).toList())
                    .addAllAmlResults(result.amlResults().stream().map(this::toProtoAmlResult).toList())
                    .addAllJurisdictionResults(result.jurisdictionResults().stream().map(this::toProtoJurisdictionResult).toList())
                    .setTotalViolations(orZero(result.totalViolations()))
                    .setTotalWarnings(orZero(result.totalWarnings()))
                    .setValidatedAt(instantToString(result.validatedAt()))
                    .setValidatedBy(nullToEmpty(result.validatedBy()))
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            handleException(e, responseObserver);
        }
    }

    @Override
    public void getRegulatoryRules(RegulatoryRulesRequest request,
                                    StreamObserver<RegulatoryRulesResponse> responseObserver) {
        try {
            var result = domainService.getRegulatoryRules(request.getJurisdiction());

            var response = RegulatoryRulesResponse.newBuilder()
                    .addAllRules(result.rules().stream().map(this::toProtoRuleDefinition).toList())
                    .setTotalRules(orZero(result.totalRules()))
                    .setLastUpdated(instantToString(result.lastUpdated()))
                    .setVersion(nullToEmpty(result.version()))
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            handleException(e, responseObserver);
        }
    }

    @Override
    public void updateRegulatoryRules(RegulatoryRulesUpdateRequest request,
                                       StreamObserver<RegulatoryRulesUpdateResponse> responseObserver) {
        try {
            var dtoRequest = new com.usora.compliance.dto.RegulatoryRulesUpdateRequest(
                    request.getRuleId(),
                    request.getName(),
                    request.getDescription(),
                    request.getJurisdiction(),
                    request.getCategory(),
                    request.getSeverity(),
                    request.getDrlContent(),
                    request.getActive(),
                    request.getEffectiveFrom(),
                    request.getExpiresAt(),
                    request.getReplaceRuleIdsList());

            // officer_token/legal_token are separate top-level fields on the
            // proto request (dual-authorization tokens), not part of the DTO
            // record -- DomainService.updateRegulatoryRules takes them as
            // separate method parameters. See BusinessException.dualAuthorizationRequired()
            // for what happens if either is missing/invalid.
            var result = domainService.updateRegulatoryRules(dtoRequest, request.getOfficerToken(), request.getLegalToken());

            var response = RegulatoryRulesUpdateResponse.newBuilder()
                    .setRuleId(nullToEmpty(result.ruleId()))
                    .setNewVersion(orZero(result.newVersion()))
                    .setStatus(nullToEmpty(result.status()))
                    .setSignatureHash(nullToEmpty(result.signatureHash()))
                    .setUpdatedAt(instantToString(result.updatedAt()))
                    .setUpdatedBy(nullToEmpty(result.updatedBy()))
                    .setMessage(nullToEmpty(result.message()))
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            handleException(e, responseObserver);
        }
    }

    @Override
    public void generateReport(ReportGenerationRequest request,
                                StreamObserver<ReportGenerationResponse> responseObserver) {
        try {
            var dtoRequest = new com.usora.compliance.dto.ReportGenerationRequest(
                    request.getReportType(),
                    request.getFormat(),
                    request.getCaseId(),
                    request.getJurisdiction(),
                    parseInstantOrNull(request.getStartDate()),
                    parseInstantOrNull(request.getEndDate()),
                    request.getIncludeSectionsList(),
                    request.getFiltersMap(),
                    request.getIncludeEvidence(),
                    request.getIncludeAuditTrail());

            var result = domainService.generateReport(dtoRequest);

            var response = ReportGenerationResponse.newBuilder()
                    .setReportId(nullToEmpty(result.reportId()))
                    .setStatus(nullToEmpty(result.status()))
                    .setFormat(nullToEmpty(result.format()))
                    .setDownloadUrl(nullToEmpty(result.downloadUrl()))
                    .setRequestedAt(instantToString(result.requestedAt()))
                    .setCompletedAt(instantToString(result.completedAt()))
                    .setMessage(nullToEmpty(result.message()))
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            handleException(e, responseObserver);
        }
    }

    @Override
    public void getAuditTrail(AuditTrailRequest request,
                               StreamObserver<AuditTrailResponse> responseObserver) {
        try {
            var result = domainService.getAuditTrail(request.getCaseId());

            var response = AuditTrailResponse.newBuilder()
                    .setCaseId(nullToEmpty(result.caseId()))
                    .setTenantId(nullToEmpty(result.tenantId()))
                    .addAllEntries(result.entries().stream().map(this::toProtoAuditEntry).toList())
                    .setTotalEntries(orZero(result.totalEntries()))
                    .setHashChainRoot(nullToEmpty(result.hashChainRoot()))
                    .setIntegrityVerified(Boolean.TRUE.equals(result.integrityVerified()))
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            handleException(e, responseObserver);
        }
    }

    @Override
    public void checkJurisdictionCompliance(JurisdictionCheckRequest request,
                                             StreamObserver<JurisdictionCheckResponse> responseObserver) {
        try {
            var dtoRequest = new com.usora.compliance.dto.JurisdictionCheckRequest(
                    request.getCaseId(),
                    request.getEntityId(),
                    request.getJurisdiction(),
                    stringMapToObjectMap(request.getEntityAttributesMap()),
                    request.getApplicableRegulationsList());

            var result = domainService.checkJurisdictionCompliance(dtoRequest);

            var response = JurisdictionCheckResponse.newBuilder()
                    .setCheckId(nullToEmpty(result.checkId()))
                    .setJurisdiction(nullToEmpty(result.jurisdiction()))
                    .setOverallCompliant(Boolean.TRUE.equals(result.overallCompliant()))
                    .addAllRegulationResults(result.regulationResults().stream().map(this::toProtoRegulationResult).toList())
                    .addAllRequiredActions(result.requiredActions() != null ? result.requiredActions() : List.of())
                    .addAllRecommendations(result.recommendations() != null ? result.recommendations() : List.of())
                    .setCheckedAt(instantToString(result.checkedAt()))
                    .setCheckedBy(nullToEmpty(result.checkedBy()))
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            handleException(e, responseObserver);
        }
    }

    @Override
    public void submitEvidence(EvidenceSubmissionRequest request,
                                StreamObserver<EvidenceSubmissionResponse> responseObserver) {
        try {
            var dtoRequest = new com.usora.compliance.dto.EvidenceSubmissionRequest(
                    request.getCaseId(),
                    request.getEvidenceType(),
                    request.getContentHash(),
                    request.getContent().toByteArray(),
                    request.getMimeType(),
                    request.getMetadataMap(),
                    request.getTagsList(),
                    request.getRequireNotarization());

            var result = domainService.submitEvidence(dtoRequest);

            var response = EvidenceSubmissionResponse.newBuilder()
                    .setEvidenceId(nullToEmpty(result.evidenceId()))
                    .setCaseId(nullToEmpty(result.caseId()))
                    .setStatus(nullToEmpty(result.status()))
                    .setStoragePath(nullToEmpty(result.storagePath()))
                    .setVerificationHash(nullToEmpty(result.verificationHash()))
                    .setBlockchainTransactionId(nullToEmpty(result.blockchainTransactionId()))
                    .setSubmittedAt(instantToString(result.submittedAt()))
                    .setMessage(nullToEmpty(result.message()))
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            handleException(e, responseObserver);
        }
    }

    // ---- message mapping helpers ----

    private RuleResult toProtoRuleResult(com.usora.compliance.dto.ComplianceValidationResponse.RuleResult r) {
        var builder = RuleResult.newBuilder()
                .setRuleId(nullToEmpty(r.ruleId()))
                .setRuleName(nullToEmpty(r.ruleName()))
                .setSeverity(nullToEmpty(r.severity()))
                .setPassed(Boolean.TRUE.equals(r.passed()))
                .setMessage(nullToEmpty(r.message()));
        if (r.triggeredConditions() != null) {
            builder.addAllTriggeredConditions(r.triggeredConditions());
        }
        return builder.build();
    }

    private AmlScreeningResult toProtoAmlResult(com.usora.compliance.dto.ComplianceValidationResponse.AmlScreeningResult a) {
        return AmlScreeningResult.newBuilder()
                .setScreeningId(nullToEmpty(a.screeningId()))
                .setListName(nullToEmpty(a.listName()))
                .setListType(nullToEmpty(a.listType()))
                .setMatchScore(a.matchScore() != null ? a.matchScore() : 0.0)
                .setIsMatch(Boolean.TRUE.equals(a.isMatch()))
                .setMatchedName(nullToEmpty(a.matchedName()))
                .setCategory(nullToEmpty(a.category()))
                .setRiskLevel(nullToEmpty(a.riskLevel()))
                .build();
    }

    private JurisdictionResult toProtoJurisdictionResult(com.usora.compliance.dto.ComplianceValidationResponse.JurisdictionResult j) {
        var builder = JurisdictionResult.newBuilder()
                .setJurisdiction(nullToEmpty(j.jurisdiction()))
                .setCompliant(Boolean.TRUE.equals(j.compliant()))
                .setMessage(nullToEmpty(j.message()));
        if (j.requirementsMet() != null) {
            builder.addAllRequirementsMet(j.requirementsMet());
        }
        if (j.requirementsFailed() != null) {
            builder.addAllRequirementsFailed(j.requirementsFailed());
        }
        return builder.build();
    }

    private RuleDefinition toProtoRuleDefinition(com.usora.compliance.dto.RegulatoryRulesResponse.RuleDefinition r) {
        return RuleDefinition.newBuilder()
                .setRuleId(nullToEmpty(r.ruleId()))
                .setName(nullToEmpty(r.name()))
                .setDescription(nullToEmpty(r.description()))
                .setJurisdiction(nullToEmpty(r.jurisdiction()))
                .setCategory(nullToEmpty(r.category()))
                .setSeverity(nullToEmpty(r.severity()))
                .setDrlContent(nullToEmpty(r.drlContent()))
                .setActive(Boolean.TRUE.equals(r.active()))
                .setVersion(orZero(r.version()))
                .setSignedBy(nullToEmpty(r.signedBy()))
                .setEffectiveFrom(instantToString(r.effectiveFrom()))
                .setExpiresAt(instantToString(r.expiresAt()))
                .build();
    }

    private AuditEntry toProtoAuditEntry(com.usora.compliance.dto.AuditTrailResponse.AuditEntry e) {
        var builder = AuditEntry.newBuilder()
                .setEntryId(nullToEmpty(e.entryId()))
                .setEventType(nullToEmpty(e.eventType()))
                .setAction(nullToEmpty(e.action()))
                .setActor(nullToEmpty(e.actor()))
                .setTenantId(nullToEmpty(e.tenantId()))
                .setDescription(nullToEmpty(e.description()))
                .setPreviousHash(nullToEmpty(e.previousHash()))
                .setCurrentHash(nullToEmpty(e.currentHash()))
                .setTimestamp(instantToString(e.timestamp()));
        if (e.details() != null) {
            builder.putAllDetails(objectMapToStringMap(e.details()));
        }
        return builder.build();
    }

    private RegulationResult toProtoRegulationResult(com.usora.compliance.dto.JurisdictionCheckResponse.RegulationResult r) {
        var builder = RegulationResult.newBuilder()
                .setRegulation(nullToEmpty(r.regulation()))
                .setStatus(nullToEmpty(r.status()))
                .setCompliant(Boolean.TRUE.equals(r.compliant()))
                .setDescription(nullToEmpty(r.description()));
        if (r.requirementsMet() != null) {
            builder.addAllRequirementsMet(r.requirementsMet());
        }
        if (r.requirementsFailed() != null) {
            builder.addAllRequirementsFailed(r.requirementsFailed());
        }
        return builder.build();
    }

    // ---- scalar/type helpers ----

    private static String nullToEmpty(String s) {
        return s != null ? s : "";
    }

    private static int orZero(Integer i) {
        return i != null ? i : 0;
    }

    private static String instantToString(Instant instant) {
        return instant != null ? instant.toString() : "";
    }

    private static Instant parseInstantOrNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return Instant.parse(s);
    }

    /**
     * Converts a proto {@code map<string, string>} into the
     * {@code Map<String, Object>} shape DomainService's rule-evaluation
     * logic expects. That logic (see DomainService.checkJurisdictionRequirements
     * / evaluateRegulation) checks entries like
     * {@code entityData.get("pepCheckCompleted")} against {@code Boolean.TRUE}
     * -- a naive String {@code "true"} would never {@code .equals()} a real
     * {@code Boolean}, silently breaking every one of those checks. Values
     * that look like booleans are coerced to real Booleans here; everything
     * else is passed through as a String.
     */
    private static Map<String, Object> stringMapToObjectMap(Map<String, String> in) {
        Map<String, Object> out = new HashMap<>();
        if (in == null) {
            return out;
        }
        for (var entry : in.entrySet()) {
            var value = entry.getValue();
            if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
                out.put(entry.getKey(), Boolean.parseBoolean(value));
            } else {
                out.put(entry.getKey(), value);
            }
        }
        return out;
    }

    private static Map<String, String> objectMapToStringMap(Map<String, Object> in) {
        Map<String, String> out = new HashMap<>();
        for (var entry : in.entrySet()) {
            out.put(entry.getKey(), entry.getValue() != null ? String.valueOf(entry.getValue()) : "");
        }
        return out;
    }

    // ---- error handling ----

    private void handleException(Exception e, StreamObserver<?> responseObserver) {
        if (e instanceof BusinessException be) {
            log.warn("Compliance gRPC call failed: {} ({})", be.getMessage(), be.getCode());
            responseObserver.onError(toGrpcStatus(be).withDescription(be.getMessage()).asRuntimeException());
        } else {
            log.error("Unexpected error in compliance gRPC service", e);
            responseObserver.onError(Status.INTERNAL.withDescription("Internal error").asRuntimeException());
        }
    }

    private static Status toGrpcStatus(BusinessException be) {
        return switch (be.getHttpStatus()) {
            case 400 -> Status.INVALID_ARGUMENT;
            case 403 -> Status.PERMISSION_DENIED;
            case 404 -> Status.NOT_FOUND;
            case 409 -> Status.FAILED_PRECONDITION;
            default -> Status.INTERNAL;
        };
    }
}
