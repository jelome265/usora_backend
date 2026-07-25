package com.usora.compliance.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "evidence_records", schema = "compliance",
       indexes = {
           @Index(name = "idx_evidence_case", columnList = "caseId"),
           @Index(name = "idx_evidence_tenant", columnList = "tenantId"),
           @Index(name = "idx_evidence_hash", columnList = "contentHash")
       })
public class EvidenceRecord extends TenantEntity {

    @Column(name = "case_id", nullable = false, length = 100)
    private String caseId;

    @Column(name = "evidence_type", nullable = false, length = 50)
    private String evidenceType;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Lob
    @Column(name = "content", columnDefinition = "BYTEA")
    private byte[] content;

    @Column(name = "mime_type", length = 100)
    private String mimeType;

    @Column(name = "storage_path", length = 500)
    private String storagePath;

    @Column(name = "verification_hash", length = 64)
    private String verificationHash;

    @Column(name = "blockchain_tx_id", length = 100)
    private String blockchainTransactionId;

    @Column(name = "notarization_status", length = 30)
    private String notarizationStatus = "pending";

    @Column(name = "metadata_json", columnDefinition = "TEXT")
    private String metadataJson;

    @Column(name = "tags", columnDefinition = "TEXT")
    private String tags;

    @Column(name = "submitted_by", length = 100)
    private String submittedBy;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "archived", nullable = false)
    private Boolean archived = false;
}
