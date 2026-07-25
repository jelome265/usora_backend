package com.usora.audit.mapper;

import com.usora.audit.dto.ResponseDto.AuditEventResponse;
import com.usora.audit.dto.ResponseDto.TamperAlertResponse;
import com.usora.audit.dto.RequestDto.AuditEventRequest;
import com.usora.audit.entity.AuditEvent;
import com.usora.audit.entity.TamperAlert;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface EntityMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "previousHash", ignore = true)
    @Mapping(target = "currentHash", ignore = true)
    @Mapping(target = "signature", ignore = true)
    @Mapping(target = "forensicFlag", ignore = true)
    @Mapping(target = "anchored", constant = "false")
    @Mapping(target = "archived", constant = "false")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "eventTimestamp", source = "timestamp")
    AuditEvent toEntity(AuditEventRequest request);

    @Mapping(target = "id", expression = "java(event.getId() != null ? event.getId().toString() : null)")
    AuditEventResponse toResponse(AuditEvent event);

    TamperAlertResponse toTamperAlertResponse(TamperAlert alert);
}
