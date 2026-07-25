package com.usora.identity.controller.v1;

import com.usora.identity.dto.RequestDto;
import com.usora.identity.dto.ResponseDto;
import com.usora.identity.service.DomainService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class ApiController {

    private final DomainService domainService;

    @PostMapping(value = "/oauth2/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<ResponseDto.TokenResponse> token(@Valid RequestDto.TokenRequest request) {
        var response = domainService.authenticate(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/oauth2/introspect")
    public ResponseEntity<ResponseDto.IntrospectResponse> introspect(@RequestBody Map<String, String> body) {
        var token = body.get("token");
        var response = domainService.introspect(token);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/oauth2/revoke")
    public ResponseEntity<Void> revoke(@RequestBody Map<String, String> body) {
        var token = body.get("token");
        domainService.revoke(token);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/oidc/.well-known/openid-configuration")
    public ResponseEntity<Map<String, Object>> openidConfiguration() {
        var config = domainService.getOpenIdConfiguration();
        return ResponseEntity.ok(config);
    }

    @GetMapping("/oidc/userinfo")
    public ResponseEntity<Map<String, Object>> userinfo(@AuthenticationPrincipal Jwt jwt) {
        var userinfo = domainService.getUserinfo(jwt);
        return ResponseEntity.ok(userinfo);
    }

    @PostMapping("/api/v1/users")
    public ResponseEntity<ResponseDto.UserResponse> createUser(
            @Valid @RequestBody RequestDto.UserCreateRequest request) {
        var response = domainService.createUser(request);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/api/v1/users/{id}/roles")
    public ResponseEntity<ResponseDto.UserResponse> updateUserRoles(
            @PathVariable String id,
            @Valid @RequestBody RequestDto.RoleUpdateRequest request) {
        var response = domainService.updateUserRoles(id, request);
        return ResponseEntity.ok(response);
    }
}
