package com.usora.compliance.repository;

import com.usora.compliance.entity.EvidenceRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface EvidenceRecordRepository extends JpaRepository<EvidenceRecord, String> {

    List<EvidenceRecord> findByCaseId(String caseId);

    List<EvidenceRecord> findByTenantIdAndCaseId(String tenantId, String caseId);

    Optional<EvidenceRecord> findByContentHash(String contentHash);

    List<EvidenceRecord> findByTenantIdAndArchivedFalse(String tenantId);

    List<EvidenceRecord> findByCreatedAtBeforeAndArchivedFalse(Instant cutoff);

    List<EvidenceRecord> findByTenantIdAndEvidenceType(String tenantId, String evidenceType);
}
