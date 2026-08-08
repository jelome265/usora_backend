package com.usora.identity.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.usora.identity.dto.RequestDto;
import com.usora.identity.dto.ResponseDto;
import com.usora.identity.service.DomainService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.mock.bean.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(com.usora.identity.controller.v1.ApiController.class)
class ApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private DomainService domainService;

    @Test
    void shouldReturnTokenResponse() throws Exception {
        var tokenResponse = ResponseDto.TokenResponse.builder()
                .accessToken("test-token")
                .tokenType("Bearer")
                .expiresIn(900)
                .scope("openid profile")
                .build();

        when(domainService.authenticate(any(RequestDto.TokenRequest.class)))
                .thenReturn(tokenResponse);

        mockMvc.perform(post("/oauth2/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "client_credentials")
                        .param("client_id", "test-client")
                        .param("client_secret", "test-secret")
                        .param("scope", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").value("test-token"))
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.expires_in").value(900));
    }

    @Test
    void shouldReturnIntrospectionResponse() throws Exception {
        var introspectResponse = ResponseDto.IntrospectResponse.builder()
                .active(true)
                .sub("user-123")
                .tid("tenant-123")
                .scope("admin")
                .roles(Set.of("admin"))
                .build();

        when(domainService.introspect("valid-token")).thenReturn(introspectResponse);

        mockMvc.perform(post("/oauth2/introspect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\": \"valid-token\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.sub").value("user-123"));
    }

    @Test
    void shouldReturnNoContentOnRevoke() throws Exception {
        mockMvc.perform(post("/oauth2/revoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\": \"test-token\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void shouldReturnOpenIdConfiguration() throws Exception {
        when(domainService.getOpenIdConfiguration())
                .thenReturn(Map.of("issuer", "http://localhost:8081"));

        mockMvc.perform(get("/oidc/.well-known/openid-configuration"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issuer").value("http://localhost:8081"));
    }

    @Test
    @WithMockUser
    void shouldReturnUserinfo() throws Exception {
        var jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("sub", "user-123")
                .claim("tid", "tenant-123")
                .claim("username", "testuser")
                .build();

        when(domainService.getUserinfo(any(Jwt.class)))
                .thenReturn(Map.of("sub", "user-123", "tid", "tenant-123"));

        mockMvc.perform(get("/oidc/userinfo")
                        .with(jwt().jwt(jwt)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sub").value("user-123"));
    }

    @Test
    void shouldCreateUser() throws Exception {
        var userResponse = ResponseDto.UserResponse.builder()
                .id("user-123")
                .tenantId("tenant-123")
                .username("newuser")
                .email("new@example.com")
                .enabled(true)
                .roles(Set.of("user"))
                .createdAt(Instant.now())
                .build();

        when(domainService.createUser(any(RequestDto.UserCreateRequest.class)))
                .thenReturn(userResponse);

        var request = RequestDto.UserCreateRequest.builder()
                .tenantId("tenant-123")
                .username("newuser")
                .email("new@example.com")
                .password("Password1!")
                .build();

        mockMvc.perform(post("/api/v1/users")
                        .with(jwt().jwt(Jwt.withTokenValue("admin-token")
                                .header("alg", "RS256")
                                .claim("sub", "admin")
                                .claim("scope", "admin")
                                .build()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("newuser"));
    }
}
