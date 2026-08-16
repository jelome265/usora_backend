package com.usora.core.mapper;

import com.usora.core.dto.RequestDto.KYCSubmissionRequest;
import com.usora.core.dto.ResponseDto.CaseResponse;
import com.usora.core.dto.ResponseDto.KYCStatusResponse;
import com.usora.core.dto.ResponseDto.KYCSubmissionResponse;
import com.usora.core.dto.ResponseDto.TenantConfigResponse;
import com.usora.core.entity.TenantEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Mapper(componentModel = "spring", imports = {UUID.class, Instant.class})
public interface EntityMapper {

    @Mapping(target = "caseId", source = "caseId")
    @Mapping(target = "status", constant = "PENDING")
    @Mapping(target = "message", constant = "KYC case submitted successfully")
    @Mapping(target = "timestamp", expression = "java(Instant.now())")
    KYCSubmissionResponse toSubmissionResponse(UUID caseId);

    @Mapping(target = "caseId", source = "caseId")
    KYCStatusResponse toStatusResponse(UUID caseId, String status, String stage,
                                       Instant createdAt, Instant updatedAt, Map<String, Object> details);

    @Mapping(target = "caseId", source = "caseId")
    CaseResponse toCaseResponse(UUID caseId, String tenantId, String customerId,
                                String status, String stage, Instant createdAt,
                                Instant updatedAt, Map<String, Object> metadata);

    @Mapping(target = "tenantId", source = "tenantId")
    @Mapping(target = "config", source = "config")
    @Mapping(target = "updatedAt", source = "updatedAt")
    TenantConfigResponse toTenantConfigResponse(TenantEntity entity);

    default Map<String, Object> parseConfig(String configJson) {
        return Map.of();
    }
}
