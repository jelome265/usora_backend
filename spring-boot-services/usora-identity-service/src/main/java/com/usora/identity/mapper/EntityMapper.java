package com.usora.identity.mapper;

import com.usora.identity.dto.RequestDto;
import com.usora.identity.dto.ResponseDto;
import com.usora.identity.entity.TenantEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Mapper(componentModel = "spring", imports = {UUID.class, Instant.class})
public interface EntityMapper {

    @Mapping(target = "id", expression = "java(UUID.randomUUID())")
    @Mapping(target = "username", source = "request.username")
    @Mapping(target = "email", source = "request.email")
    @Mapping(target = "displayName", source = "request.displayName")
    @Mapping(target = "enabled", constant = "true")
    @Mapping(target = "roles", source = "request.roles")
    @Mapping(target = "tenant", source = "tenant")
    @Mapping(target = "passwordHash", ignore = true)
    @Mapping(target = "mfaEnabled", constant = "false")
    @Mapping(target = "mfaType", ignore = true)
    @Mapping(target = "lastLoginAt", ignore = true)
    @Mapping(target = "attributes", expression = "java(serializeAttributes(request.getAttributes()))")
    @Mapping(target = "createdAt", expression = "java(Instant.now())")
    @Mapping(target = "updatedAt", expression = "java(Instant.now())")
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    TenantEntity.UserEntity toUserEntity(RequestDto.UserCreateRequest request, TenantEntity tenant);

    @Mapping(target = "id", expression = "java(entity.getId().toString())")
    @Mapping(target = "tenantId", expression = "java(entity.getTenant().getId().toString())")
    @Mapping(target = "attributes", expression = "java(deserializeAttributes(entity.getAttributes()))")
    ResponseDto.UserResponse toUserResponse(TenantEntity.UserEntity entity);

    @Named("serializeAttributes")
    default String serializeAttributes(Map<String, Object> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return "{}";
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(attributes);
        } catch (Exception e) {
            return "{}";
        }
    }

    @Named("deserializeAttributes")
    default Map<String, Object> deserializeAttributes(String attributes) {
        if (attributes == null || attributes.isBlank()) {
            return Map.of();
        }
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var type = mapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class);
            return mapper.readValue(attributes, type);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
