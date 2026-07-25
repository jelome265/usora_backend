package com.usora.audit.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "merkle_roots", schema = "audit")
@Getter
@Setter
@NoArgsConstructor
public class MerkleRoot extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, length = 50)
    private String tenantId;

    @Column(name = "merkle_root", nullable = false, length = 64)
    private String merkleRoot;

    @Column(name = "interval_start", nullable = false)
    private Instant intervalStart;

    @Column(name = "interval_end", nullable = false)
    private Instant intervalEnd;

    @Column(name = "event_count", nullable = false)
    private int eventCount;

    @Column(name = "signature", nullable = false, length = 128)
    private String signature;

    @Column(name = "blockchain_tx_id", length = 128)
    private String blockchainTxId;

    @Column(name = "blockchain_anchored", nullable = false)
    private boolean blockchainAnchored = false;

    @Column(name = "anchored_at")
    private Instant anchoredAt;
}
