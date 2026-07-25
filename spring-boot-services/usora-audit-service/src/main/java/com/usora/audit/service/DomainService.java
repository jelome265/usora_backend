package com.usora.audit.service;

import com.usora.audit.client.RestClient;
import com.usora.audit.config.TenantConfig;
import com.usora.audit.dto.RequestDto.AuditEventRequest;
import com.usora.audit.dto.RequestDto.AuditSearchRequest;
import com.usora.audit.dto.RequestDto.ComplianceReportRequest;
import com.usora.audit.dto.RequestDto.IntegrityVerificationRequest;
import com.usora.audit.dto.ResponseDto.*;
import com.usora.audit.entity.AuditEvent;
import com.usora.audit.entity.TamperAlert;
import com.usora.audit.entity.TenantEntity;
import com.usora.audit.event.DomainEventPublisher;
import com.usora.audit.mapper.EntityMapper;
import com.usora.audit.repository.AuditEventRepository;
import com.usora.audit.repository.TamperAlertRepository;
import com.usora.audit.repository.TenantRepository;
import com.usora.audit.security.TenantContext;
import com.usora.audit.util.EncryptionUtil;
import com.usora.audit.util.HashingUtil;
import com.usora.audit.util.IdGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class DomainService {

    private static final Logger log = LoggerFactory.getLogger(DomainService.class);

    private final AuditEventRepository auditEventRepository;
    private final TamperAlertRepository tamperAlertRepository;
    private final TenantRepository tenantRepository;
    private final EntityMapper entityMapper;
    private final HashingUtil hashingUtil;
    private final EncryptionUtil encryptionUtil;
    private final IdGenerator idGenerator;
    private final DomainEventPublisher eventPublisher;
    private final TenantConfig tenantConfig;
    private final S3Client s3Client;
    private final RestClient restClient;
    private final MeterRegistry meterRegistry;

    public DomainService(AuditEventRepository auditEventRepository,
                         TamperAlertRepository tamperAlertRepository,
                         TenantRepository tenantRepository,
                         EntityMapper entityMapper,
                         HashingUtil hashingUtil,
                         EncryptionUtil encryptionUtil,
                         IdGenerator idGenerator,
                         DomainEventPublisher eventPublisher,
                         TenantConfig tenantConfig,
                         S3Client s3Client,
                         @Qualifier("siemRestClient") RestClient restClient,
                         MeterRegistry meterRegistry) {
        this.auditEventRepository = auditEventRepository;
        this.tamperAlertRepository = tamperAlertRepository;
        this.tenantRepository = tenantRepository;
        this.entityMapper = entityMapper;
        this.hashingUtil = hashingUtil;
        this.encryptionUtil = encryptionUtil;
        this.idGenerator = idGenerator;
        this.eventPublisher = eventPublisher;
        this.tenantConfig = tenantConfig;
        this.s3Client = s3Client;
        this.restClient = restClient;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public AuditEventResponse logEvent(AuditEventRequest request) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            String tenantId = resolveTenantId(request.getTenantId());

            AuditEvent event = entityMapper.toEntity(request);
            event.setId(idGenerator.generateUuid());
            event.setTenantId(tenantId);

            if (event.getEventType() == null) {
                event.setEventType(request.getAction().toUpperCase().replace(' ', '_'));
            }

            String previousHash = auditEventRepository
                    .findTopByTenantIdAndAnchoredOrderByEventTimestampDesc(tenantId, false)
                    .map(AuditEvent::getCurrentHash)
                    .orElse("0".repeat(64));

            event.setPreviousHash(previousHash);

            String rawData = buildRawData(event);
            String currentHash = hashingUtil.sha256(rawData);
            event.setCurrentHash(currentHash);

            String hmacKey = resolveHmacKey(tenantId);
            String signature = hashingUtil.hmacSha256(currentHash, hmacKey);
            event.setSignature(signature);

            if (request.getBeforeState() != null) {
                event.setBeforeState(encryptionUtil.encryptSensitiveData(request.getBeforeState()));
            }
            if (request.getAfterState() != null) {
                event.setAfterState(encryptionUtil.encryptSensitiveData(request.getAfterState()));
            }
            if (request.getMetadata() != null) {
                event.setMetadata(encryptionUtil.encryptSensitiveData(request.getMetadata()));
            }

            if (request.getSeverity() != null && isSeverityHigh(request.getSeverity())) {
                event.setForensicFlag(true);
            }

            AuditEvent saved = auditEventRepository.save(event);

            eventPublisher.publishAuditEvent(saved);

            meterRegistry.counter("audit_events_logged_total",
                    "tenant", tenantId,
                    "action", event.getAction(),
                    "outcome", event.getOutcome()).increment();

            sample.stop(Timer.builder("audit_log_latency_seconds")
                    .tag("tenant", tenantId)
                    .register(meterRegistry));

            return entityMapper.toResponse(saved);
        } catch (Exception e) {
            meterRegistry.counter("audit_events_failed_total",
                    "tenant", request.getTenantId(),
                    "error", e.getClass().getSimpleName()).increment();
            log.error("Failed to log audit event for tenant {}: {}", request.getTenantId(), e.getMessage(), e);
            throw new RuntimeException("Failed to log audit event: " + e.getMessage(), e);
        }
    }

    @Cacheable(value = "auditTrails", key = "#tenantId + ':' + #entityType + ':' + #entityId", unless = "#result == null")
    public AuditTrailResponse getAuditTrail(String tenantId, String entityType, String entityId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "eventTimestamp"));
        List<AuditEvent> events = auditEventRepository
                .findByTenantIdAndResourceTypeAndResourceId(tenantId, entityType, entityId, pageable);

        return AuditTrailResponse.builder()
                .entityType(entityType)
                .entityId(entityId)
                .events(events.stream().map(entityMapper::toResponse).toList())
                .totalEvents(events.size())
                .page(page)
                .size(size)
                .build();
    }

    public IntegrityResponse verifyIntegrity(IntegrityVerificationRequest request) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            String tenantId = resolveTenantId(request.getTenantId());

            Instant from = request.getFromTimestamp() != null ? request.getFromTimestamp() : Instant.EPOCH;
            Instant to = request.getToTimestamp() != null ? request.getToTimestamp() : Instant.now();

            List<AuditEvent> events = auditEventRepository
                    .findByTenantIdAndTimestampBetweenOrdered(tenantId, from, to);

            if (events.isEmpty()) {
                return IntegrityResponse.builder()
                        .valid(true)
                        .rootHash("EMPTY")
                        .verifiedAt(Instant.now())
                        .details(IntegrityDetails.builder()
                                .totalEventsVerified(0)
                                .intervalStart(from)
                                .intervalEnd(to)
                                .build())
                        .build();
            }

            List<String> mismatchedHashes = new ArrayList<>();
            String expectedPrevHash = "0".repeat(64);

            for (AuditEvent event : events) {
                String rawData = buildRawData(event);
                String expectedHash = hashingUtil.sha256(rawData);

                if (!expectedHash.equals(event.getCurrentHash())) {
                    mismatchedHashes.add(event.getCurrentHash());
                }

                if (!event.getPreviousHash().equals(expectedPrevHash)) {
                    mismatchedHashes.add("CHAIN_BREAK_AT:" + event.getId());
                }

                String hmacKey = resolveHmacKey(tenantId);
                String expectedSignature = hashingUtil.hmacSha256(event.getCurrentHash(), hmacKey);
                if (!expectedSignature.equals(event.getSignature())) {
                    mismatchedHashes.add("SIGNATURE_MISMATCH:" + event.getId());
                }

                expectedPrevHash = event.getCurrentHash();
            }

            String merkleRoot = hashingUtil.buildMerkleRoot(events.stream()
                    .map(AuditEvent::getCurrentHash)
                    .toList());

            boolean isValid = mismatchedHashes.isEmpty();
            meterRegistry.counter("audit_integrity_verification_total",
                    "tenant", tenantId,
                    "result", isValid ? "valid" : "invalid").increment();

            sample.stop(Timer.builder("audit_integrity_verification_latency_seconds")
                    .tag("tenant", tenantId)
                    .register(meterRegistry));

            if (!isValid) {
                createTamperAlert(tenantId, "INTEGRITY_MISMATCH", "CRITICAL",
                        "Integrity verification failed with " + mismatchedHashes.size() + " mismatches",
                        mismatchedHashes.isEmpty() ? null : mismatchedHashes.getFirst());
            }

            return IntegrityResponse.builder()
                    .valid(isValid)
                    .rootHash(merkleRoot)
                    .verifiedAt(Instant.now())
                    .details(IntegrityDetails.builder()
                            .totalEventsVerified(events.size())
                            .merkleRoot(merkleRoot)
                            .intervalStart(from)
                            .intervalEnd(to)
                            .mismatchedHashes(mismatchedHashes)
                            .build())
                    .build();
        } catch (Exception e) {
            log.error("Integrity verification failed: {}", e.getMessage(), e);
            return IntegrityResponse.builder()
                    .valid(false)
                    .verifiedAt(Instant.now())
                    .details(IntegrityDetails.builder()
                            .totalEventsVerified(0)
                            .build())
                    .build();
        }
    }

    public SearchResponse searchEvents(AuditSearchRequest request) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            String tenantId = resolveTenantId(request.getTenantId());

            Sort sort = Sort.by(
                    "DESC".equalsIgnoreCase(request.getPagination().getSortDirection())
                            ? Sort.Direction.DESC : Sort.Direction.ASC,
                    request.getPagination().getSortBy() != null ? request.getPagination().getSortBy() : "eventTimestamp");

            Pageable pageable = PageRequest.of(
                    request.getPagination().getPage(),
                    request.getPagination().getSize(),
                    sort);

            Page<AuditEvent> eventPage;

            if (request.getActorId() != null) {
                eventPage = auditEventRepository.findByTenantIdAndActorId(tenantId, request.getActorId(), pageable);
            } else if (request.getCategory() != null) {
                eventPage = auditEventRepository.findByTenantIdAndCategory(tenantId, request.getCategory(), pageable);
            } else if (request.getFromTimestamp() != null && request.getToTimestamp() != null) {
                eventPage = auditEventRepository.findByTenantIdAndTimestampBetweenPaged(
                        tenantId, request.getFromTimestamp(), request.getToTimestamp(), pageable);
            } else {
                eventPage = auditEventRepository.findByTenantId(tenantId, pageable);
            }

            sample.stop(Timer.builder("audit_search_latency_seconds")
                    .tag("tenant", tenantId)
                    .register(meterRegistry));

            return SearchResponse.builder()
                    .events(eventPage.getContent().stream().map(entityMapper::toResponse).toList())
                    .totalHits(eventPage.getTotalElements())
                    .page(request.getPagination().getPage())
                    .size(request.getPagination().getSize())
                    .build();
        } catch (Exception e) {
            log.error("Search failed: {}", e.getMessage(), e);
            throw new RuntimeException("Search failed: " + e.getMessage(), e);
        }
    }

    @Async("complianceReportExecutor")
    public ComplianceReportResponse generateComplianceReport(ComplianceReportRequest request) {
        String tenantId = resolveTenantId(request.getTenantId());
        String reportId = idGenerator.generateUuid().toString();

        try {
            List<AuditEvent> events;
            if (request.getCategories() != null && !request.getCategories().isEmpty()) {
                events = new ArrayList<>();
                for (String category : request.getCategories()) {
                    Pageable pageable = PageRequest.of(0, 10000, Sort.by(Sort.Direction.ASC, "eventTimestamp"));
                    events.addAll(auditEventRepository.findByTenantIdAndCategory(tenantId, category, pageable)
                            .getContent());
                }
            } else {
                events = auditEventRepository.findByTenantIdAndTimestampBetweenOrdered(
                        tenantId, request.getFromTimestamp(), request.getToTimestamp());
            }

            if (request.isIncludeEvidence()) {
                for (AuditEvent event : events) {
                    if (event.getBeforeState() != null) {
                        event.setBeforeState(encryptionUtil.decryptSensitiveData(event.getBeforeState()));
                    }
                    if (event.getAfterState() != null) {
                        event.setAfterState(encryptionUtil.decryptSensitiveData(event.getAfterState()));
                    }
                }
            }

            String reportContent = buildComplianceReport(events, request);
            String bucketName = "usora-audit-compliance-reports";
            String key = String.format("%s/%s/%s.json", tenantId, reportId, Instant.now().toString());

            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucketName)
                            .key(key)
                            .contentType("application/json")
                            .build(),
                    RequestBody.fromString(reportContent));

            String downloadUrl = String.format("s3://%s/%s", bucketName, key);

            meterRegistry.counter("audit_compliance_reports_total",
                    "tenant", tenantId).increment();

            return ComplianceReportResponse.builder()
                    .reportId(reportId)
                    .tenantId(tenantId)
                    .status("COMPLETED")
                    .requestedAt(Instant.now())
                    .downloadUrl(downloadUrl)
                    .expiresAt(Instant.now().plus(30, ChronoUnit.DAYS))
                    .totalEvents(events.size())
                    .build();
        } catch (Exception e) {
            log.error("Compliance report generation failed for tenant {}: {}", tenantId, e.getMessage(), e);
            return ComplianceReportResponse.builder()
                    .reportId(reportId)
                    .tenantId(tenantId)
                    .status("FAILED")
                    .requestedAt(Instant.now())
                    .totalEvents(0)
                    .build();
        }
    }

    public List<TamperAlertResponse> getTamperAlerts(String tenantId, boolean resolved, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "detectedAt"));
        Page<TamperAlert> alerts;

        if (tenantId != null) {
            alerts = tamperAlertRepository.findByTenantIdOrderByDetectedAtDesc(tenantId, pageable);
        } else {
            alerts = tamperAlertRepository.findAll(pageable);
        }

        return alerts.getContent().stream()
                .map(entityMapper::toTamperAlertResponse)
                .toList();
    }

    @CacheEvict(value = "auditTrails", allEntries = true)
    public void clearCache() {
        log.info("Audit trail cache cleared");
    }

    public void computeAndAnchorMerkleRoot(String tenantId) {
        try {
            Instant hourStart = Instant.now().truncatedTo(ChronoUnit.HOURS).minus(1, ChronoUnit.HOURS);
            Instant hourEnd = hourStart.plus(1, ChronoUnit.HOURS);

            List<AuditEvent> events = auditEventRepository
                    .findByTenantIdAndTimestampBetweenOrdered(tenantId, hourStart, hourEnd);

            if (events.isEmpty()) {
                log.info("No events to anchor for tenant {} in hour {}", tenantId, hourStart);
                return;
            }

            List<String> hashes = events.stream().map(AuditEvent::getCurrentHash).toList();
            String merkleRoot = hashingUtil.buildMerkleRoot(hashes);

            String hmacKey = resolveHmacKey(tenantId);
            String anchoredSignature = hashingUtil.hmacSha256(merkleRoot + hourStart.toString(), hmacKey);

            eventPublisher.publishAnchorEvent(tenantId, merkleRoot, hourStart, hourEnd, anchoredSignature);

            for (AuditEvent event : events) {
                event.setAnchored(true);
            }
            auditEventRepository.saveAll(events);

            log.info("Anchored {} events for tenant {} with Merkle root {}", events.size(), tenantId, merkleRoot);
        } catch (Exception e) {
            log.error("Failed to compute and anchor Merkle root for tenant {}: {}", tenantId, e.getMessage(), e);
            meterRegistry.counter("audit_merkle_root_failed_total",
                    "tenant", tenantId).increment();
        }
    }

    @Transactional
    public void archiveOldEvents(String tenantId) {
        try {
            TenantConfig.TenantProperties props = tenantConfig.getTenant(tenantId);
            Instant cutoff = Instant.now().minus(props.getHotRetentionDays(), ChronoUnit.DAYS);
            List<AuditEvent> oldEvents = auditEventRepository
                    .findUnarchivedEventsOlderThan(tenantId, cutoff);

            if (oldEvents.isEmpty()) return;

            String bucketName = "usora-audit-cold-storage";
            for (AuditEvent event : oldEvents) {
                String key = String.format("%s/%s/%s.json",
                        tenantId, event.getId().toString(), event.getEventTimestamp().toString());
                s3Client.putObject(
                        PutObjectRequest.builder()
                                .bucket(bucketName)
                                .key(key)
                                .contentType("application/json")
                                .build(),
                        RequestBody.fromString(buildRawData(event)));
                event.setArchived(true);
            }
            auditEventRepository.saveAll(oldEvents);
            log.info("Archived {} events for tenant {} to cold storage", oldEvents.size(), tenantId);
        } catch (Exception e) {
            log.error("Failed to archive old events for tenant {}: {}", tenantId, e.getMessage(), e);
        }
    }

    private String resolveTenantId(String tenantId) {
        String contextTenant = TenantContext.getTenantId();
        if (tenantId != null) return tenantId;
        if (contextTenant != null) return contextTenant;
        return "default";
    }

    private String resolveHmacKey(String tenantId) {
        TenantConfig.TenantProperties props = tenantConfig.getTenant(tenantId);
        if (props.getHmacKey() != null) return props.getHmacKey();
        return tenantRepository.findByTenantIdAndActiveTrue(tenantId)
                .map(TenantEntity::getHmacKey)
                .orElse("default-hmac-key");
    }

    private String buildRawData(AuditEvent event) {
        return String.join("|",
                event.getPreviousHash(),
                event.getTenantId(),
                event.getActorId(),
                event.getAction(),
                event.getResourceType(),
                event.getResourceId(),
                event.getOutcome(),
                event.getEventTimestamp().toString(),
                event.getIpAddress() != null ? event.getIpAddress() : "",
                event.getUserAgent() != null ? event.getUserAgent() : ""
        );
    }

    private String buildComplianceReport(List<AuditEvent> events, ComplianceReportRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"reportType\":\"").append(request.getReportType() != null ? request.getReportType() : "STANDARD").append("\",");
        sb.append("\"tenantId\":\"").append(request.getTenantId()).append("\",");
        sb.append("\"fromTimestamp\":\"").append(request.getFromTimestamp()).append("\",");
        sb.append("\"toTimestamp\":\"").append(request.getToTimestamp()).append("\",");
        sb.append("\"generatedAt\":\"").append(Instant.now()).append("\",");
        sb.append("\"totalEvents\":").append(events.size()).append(",");
        sb.append("\"events\":[");
        for (int i = 0; i < events.size(); i++) {
            AuditEvent e = events.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"id\":\"").append(e.getId()).append("\"");
            sb.append(",\"actorId\":\"").append(e.getActorId()).append("\"");
            sb.append(",\"action\":\"").append(e.getAction()).append("\"");
            sb.append(",\"resourceType\":\"").append(e.getResourceType()).append("\"");
            sb.append(",\"resourceId\":\"").append(e.getResourceId()).append("\"");
            sb.append(",\"outcome\":\"").append(e.getOutcome()).append("\"");
            sb.append(",\"timestamp\":\"").append(e.getEventTimestamp()).append("\"");
            sb.append(",\"currentHash\":\"").append(e.getCurrentHash()).append("\"");
            sb.append(",\"signature\":\"").append(e.getSignature()).append("\"");
            sb.append("}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private void createTamperAlert(String tenantId, String alertType, String severity,
                                   String description, String affectedHash) {
        TamperAlert alert = new TamperAlert();
        alert.setId(idGenerator.generateUuid());
        alert.setTenantId(tenantId);
        alert.setAlertType(alertType);
        alert.setSeverity(severity);
        alert.setDescription(description);
        alert.setAffectedHash(affectedHash);
        alert.setDetectedAt(Instant.now());
        tamperAlertRepository.save(alert);

        meterRegistry.counter("audit_tamper_alerts_total",
                "tenant", tenantId,
                "type", alertType).increment();

        eventPublisher.publishTamperAlert(alert);
    }

    private boolean isSeverityHigh(String severity) {
        return List.of("CRITICAL", "HIGH", "SEVERE").contains(severity.toUpperCase());
    }
}
