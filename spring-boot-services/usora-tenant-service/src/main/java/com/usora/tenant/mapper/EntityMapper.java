package com.usora.tenant.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.usora.tenant.dto.OnboardRequest;
import com.usora.tenant.dto.TenantResponse;
import com.usora.tenant.entity.TenantEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.mapstruct.factory.Mappers;

import java.util.Collections;
import java.util.Map;

@Mapper(componentModel = "spring", uses = ObjectMapper.class)
public interface EntityMapper {

    EntityMapper INSTANCE = Mappers.getMapper(EntityMapper.class);

    @Mapping(target = "id", source = "id", qualifiedByName = "uuidToString")
    @Mapping(target = "features", source = "features", qualifiedByName = "jsonToMap")
    @Mapping(target = "config", source = "config", qualifiedByName = "jsonToMap")
    @Mapping(target = "status", source = "status", qualifiedByName = "enumToString")
    TenantResponse toResponse(TenantEntity entity);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "version", ignore = true)
    @Mapping(target = "status", constant = "PROVISIONING")
    @Mapping(target = "features", source = "features", qualifiedByName = "mapToJson")
    @Mapping(target = "config", ignore = true)
    @Mapping(target = "stripeCustomerId", ignore = true)
    @Mapping(target = "provisioningStatus", constant = "PENDING")
    @Mapping(target = "maxUsers", constant = "100")
    @Mapping(target = "storageQuotaBytes", constant = "107374182400L")
    TenantEntity toEntity(OnboardRequest request);

    @Named("uuidToString")
    default String uuidToString(java.util.UUID uuid) {
        return uuid != null ? uuid.toString() : null;
    }

    @Named("jsonToMap")
    default Map<String, Object> jsonToMap(String json) {
        if (json == null || json.isBlank()) return Collections.emptyMap();
        try {
            return new ObjectMapper().readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            return Collections.emptyMap();
        }
    }

    @Named("mapToJson")
    default String mapToJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) return null;
        try {
            return new ObjectMapper().writeValueAsString(map);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    @Named("enumToString")
    default String enumToString(Enum<?> e) {
        return e != null ? e.name() : null;
    }
}
