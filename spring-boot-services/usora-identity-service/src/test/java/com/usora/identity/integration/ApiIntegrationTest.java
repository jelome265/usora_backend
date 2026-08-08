package com.usora.identity.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.usora.identity.entity.TenantEntity;
import com.usora.identity.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TenantRepository tenantRepository;

    private TenantEntity tenant;

    @BeforeEach
    void setUp() {
        tenantRepository.deleteAll();
        tenant = TenantEntity.builder()
                .id(UUID.randomUUID())
                .tenantName("integration-test")
                .domain("integration.usora.io")
                .enabled(true)
                .keyAlgorithm("RS256")
                .build();
        tenant = tenantRepository.save(tenant);
    }

    @Test
    void healthEndpointShouldReturnOk() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("healthy"));
    }

    @Test
    void openIdConfigurationShouldReturnValidConfig() throws Exception {
        mockMvc.perform(get("/oidc/.well-known/openid-configuration"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issuer").value("http://localhost:8081"))
                .andExpect(jsonPath("$.grant_types_supported").isArray())
                .andExpect(jsonPath("$.scopes_supported").isArray());
    }

    @Test
    void tokenIntrospectionShouldReturnInactiveForInvalidToken() throws Exception {
        mockMvc.perform(post("/oauth2/introspect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\": \"invalid-token\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void userinfoShouldReturnAuthenticatedUserInfo() throws Exception {
        var jwt = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .claim("sub", "user-123")
                .claim("tid", tenant.getId().toString())
                .claim("username", "testuser")
                .claim("email", "test@example.com")
                .claim("roles", new String[]{"user"})
                .build();

        mockMvc.perform(get("/oidc/userinfo")
                        .with(jwt().jwt(jwt)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sub").value("user-123"))
                .andExpect(jsonPath("$.preferred_username").value("testuser"));
    }
}
