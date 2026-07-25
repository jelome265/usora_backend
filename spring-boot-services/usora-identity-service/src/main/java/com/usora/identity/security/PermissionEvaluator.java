package com.usora.identity.security;

import com.usora.identity.client.RestClient;
import com.usora.identity.config.TenantConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class PermissionEvaluator {

    private final RestClient restClient;
    private final TenantConfig tenantConfig;

    @Cacheable(value = "policyDecision", key = "#tenantId + ':' + #subject + ':' + #action + ':' + #resource")
    public boolean evaluate(String tenantId, String subject, String action, String resource, Map<String, Object> context) {
        try {
            var opaUrl = tenantConfig.getOpa().getUrl();
            var input = Map.<String, Object>of(
                    "input", Map.of(
                            "tenant_id", tenantId,
                            "subject", subject,
                            "action", action,
                            "resource", resource,
                            "context", context != null ? context : Map.of()
                    )
            );

            var result = restClient.post(opaUrl + "/v1/data/usora/authz/allow", input);
            if (result != null && result.containsKey("result")) {
                var decision = result.get("result");
                if (decision instanceof Boolean) {
                    log.debug("OPA decision for {}:{}:{} = {}", subject, action, resource, decision);
                    return (Boolean) decision;
                }
            }
            log.warn("OPA returned unexpected result format for {}:{}:{}", subject, action, resource);
            return false;
        } catch (Exception e) {
            log.error("OPA policy evaluation failed for {}:{}:{} - denying by default", subject, action, resource, e);
            return false;
        }
    }

    public boolean hasRole(String userId, String role, String tenantId) {
        return evaluate(tenantId, userId, "access", "role:" + role, Map.of());
    }

    public boolean hasAnyRole(String userId, Set<String> roles, String tenantId) {
        return roles.stream().anyMatch(role -> hasRole(userId, role, tenantId));
    }

    public List<String> getEffectivePermissions(String userId, String tenantId) {
        try {
            var input = Map.<String, Object>of(
                    "input", Map.of(
                            "tenant_id", tenantId,
                            "subject", userId,
                            "action", "list_permissions",
                            "resource", "*"
                    )
            );

            var result = restClient.post(
                    tenantConfig.getOpa().getUrl() + "/v1/data/usora/authz/permissions", input);

            if (result != null && result.containsKey("result")) {
                @SuppressWarnings("unchecked")
                var permissions = (List<String>) result.get("result");
                return permissions;
            }
        } catch (Exception e) {
            log.error("Failed to get effective permissions for user {} in tenant {}", userId, tenantId, e);
        }
        return List.of();
    }
}
