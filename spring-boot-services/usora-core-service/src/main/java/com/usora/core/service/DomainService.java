package com.usora.core.service;

import com.usora.core.dto.RequestDto.CaseStatusUpdateRequest;
import com.usora.core.dto.RequestDto.KYCSubmissionRequest;
import com.usora.core.dto.RequestDto.TenantConfigUpdateRequest;
import com.usora.core.dto.ResponseDto.CaseResponse;
import com.usora.core.dto.ResponseDto.KYCStatusResponse;
import com.usora.core.dto.ResponseDto.KYCSubmissionResponse;
import com.usora.core.dto.ResponseDto.TenantConfigResponse;
import com.usora.core.entity.TenantEntity;
import com.usora.core.event.DomainEventPublisher;
import com.usora.core.exception.BusinessException;
import com.usora.core.mapper.EntityMapper;
import com.usora.core.repository.TenantRepository;
import com.usora.core.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional
public class DomainService {

    private static final Logger log = LoggerFactory.getLogger(DomainService.class);

    private final JdbcTemplate jdbcTemplate;
    private final TenantRepository tenantRepository;
    private final EntityMapper entityMapper;
    private final DomainEventPublisher eventPublisher;

    public DomainService(JdbcTemplate jdbcTemplate, TenantRepository tenantRepository,
                         EntityMapper entityMapper, DomainEventPublisher eventPublisher) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantRepository = tenantRepository;
        this.entityMapper = entityMapper;
        this.eventPublisher = eventPublisher;
    }

    public KYCSubmissionResponse submitKYC(KYCSubmissionRequest request) {
        var caseId = UUID.randomUUID();
        var tenantId = TenantContext.getCurrentTenantId();
        log.info("Submitting KYC case: caseId={}, tenantId={}, customerId={}", caseId, tenantId, request.customerId());

        jdbcTemplate.update(
                "INSERT INTO cases (id, tenant_id, customer_id, status, stage, metadata, created_at, updated_at) " +
                "VALUES (?, ?, ?, 'PENDING', 'DOCUMENT_COLLECTION', ?, NOW(), NOW())",
                caseId, tenantId, request.customerId(), request.metadata() != null ? request.metadata().toString() : "{}"
        );

        jdbcTemplate.update(
                "INSERT INTO verifications (case_id, tenant_id, document_type, document_number, " +
                "issuing_country, status, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, 'PENDING', NOW(), NOW())",
                caseId, tenantId, request.document().type(), request.document().number(),
                request.document().issuingCountry()
        );

        var eventPayload = String.format(
                "{\"caseId\":\"%s\",\"tenantId\":\"%s\",\"customerId\":\"%s\",\"eventType\":\"KYC_SUBMITTED\"}",
                caseId, tenantId, request.customerId()
        );
        eventPublisher.publishKYCEvent(caseId.toString(), "KYC_SUBMITTED", eventPayload);

        return new KYCSubmissionResponse(caseId, "PENDING", "KYC case submitted successfully", Instant.now());
    }

    @Transactional(readOnly = true)
    public KYCStatusResponse getKYCStatus(UUID caseId) {
        log.info("Getting KYC status: caseId={}", caseId);
        var rows = jdbcTemplate.queryForList(
                "SELECT id, status, stage, created_at, updated_at FROM cases WHERE id = ?",
                caseId
        );
        if (rows.isEmpty()) {
            throw BusinessException.notFound("Case", caseId);
        }
        var row = rows.getFirst();
        return new KYCStatusResponse(
                caseId,
                (String) row.get("status"),
                (String) row.get("stage"),
                ((java.sql.Timestamp) row.get("created_at")).toInstant(),
                ((java.sql.Timestamp) row.get("updated_at")).toInstant(),
                Map.of()
        );
    }

    public void cancelKYC(UUID caseId) {
        log.info("Cancelling KYC case: caseId={}", caseId);
        var updated = jdbcTemplate.update(
                "UPDATE cases SET status = 'CANCELLED', updated_at = NOW() WHERE id = ? AND status IN ('PENDING', 'IN_PROGRESS')",
                caseId
        );
        if (updated == 0) {
            throw BusinessException.notFound("Case", caseId);
        }

        var eventPayload = String.format("{\"caseId\":\"%s\",\"eventType\":\"KYC_CANCELLED\"}", caseId);
        eventPublisher.publishKYCEvent(caseId.toString(), "KYC_CANCELLED", eventPayload);
    }

    @Transactional(readOnly = true)
    public Page<CaseResponse> listCases(Pageable pageable) {
        var tenantId = TenantContext.getCurrentTenantId();
        log.info("Listing cases: tenantId={}, page={}", tenantId, pageable.getPageNumber());

        var total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cases WHERE tenant_id = ?",
                Long.class, tenantId
        );

        var rows = jdbcTemplate.queryForList(
                "SELECT id, tenant_id, customer_id, status, stage, created_at, updated_at, metadata " +
                "FROM cases WHERE tenant_id = ? ORDER BY created_at DESC LIMIT ? OFFSET ?",
                tenantId, pageable.getPageSize(), pageable.getOffset()
        );

        var cases = rows.stream().map(row -> new CaseResponse(
                (UUID) row.get("id"),
                (String) row.get("tenant_id"),
                (String) row.get("customer_id"),
                (String) row.get("status"),
                (String) row.get("stage"),
                ((java.sql.Timestamp) row.get("created_at")).toInstant(),
                ((java.sql.Timestamp) row.get("updated_at")).toInstant(),
                Map.of()
        )).toList();

        return new PageImpl<>(cases, pageable, total != null ? total : 0);
    }

    @Transactional(readOnly = true)
    public CaseResponse getCaseDetails(UUID caseId) {
        log.info("Getting case details: caseId={}", caseId);
        var rows = jdbcTemplate.queryForList(
                "SELECT id, tenant_id, customer_id, status, stage, created_at, updated_at, metadata FROM cases WHERE id = ?",
                caseId
        );
        if (rows.isEmpty()) {
            throw BusinessException.notFound("Case", caseId);
        }
        var row = rows.getFirst();
        return new CaseResponse(
                (UUID) row.get("id"),
                (String) row.get("tenant_id"),
                (String) row.get("customer_id"),
                (String) row.get("status"),
                (String) row.get("stage"),
                ((java.sql.Timestamp) row.get("created_at")).toInstant(),
                ((java.sql.Timestamp) row.get("updated_at")).toInstant(),
                Map.of()
        );
    }

    public CaseResponse updateCaseStatus(UUID caseId, CaseStatusUpdateRequest request) {
        log.info("Updating case status: caseId={}, newStatus={}", caseId, request.status());
        var updated = jdbcTemplate.update(
                "UPDATE cases SET status = ?, updated_at = NOW() WHERE id = ?",
                request.status(), caseId
        );
        if (updated == 0) {
            throw BusinessException.notFound("Case", caseId);
        }
        return getCaseDetails(caseId);
    }

    @Transactional(readOnly = true)
    public TenantConfigResponse getTenantConfig(String tenantId) {
        log.info("Getting tenant config: tenantId={}", tenantId);
        var entity = tenantRepository.findByTenantId(tenantId)
                .orElseThrow(() -> BusinessException.notFound("Tenant", tenantId));
        return entityMapper.toTenantConfigResponse(entity);
    }

    public TenantConfigResponse updateTenantConfig(String tenantId, TenantConfigUpdateRequest request) {
        log.info("Updating tenant config: tenantId={}", tenantId);
        var entity = tenantRepository.findByTenantId(tenantId)
                .orElseGet(() -> new TenantEntity(tenantId, request.config()));
        entity.setConfig(request.config().toString());
        entity.setUpdatedAt(Instant.now());
        tenantRepository.save(entity);
        return entityMapper.toTenantConfigResponse(entity);
    }
}
