package com.usora.identity.service;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jwt.JWTClaimsSet;
import com.usora.identity.config.TenantConfig;
import com.usora.identity.dto.RequestDto;
import com.usora.identity.dto.ResponseDto;
import com.usora.identity.entity.TenantEntity;
import com.usora.identity.event.DomainEventPublisher;
import com.usora.identity.exception.BusinessException;
import com.usora.identity.mapper.EntityMapper;
import com.usora.identity.repository.OAuth2ClientRepository;
import com.usora.identity.repository.TenantRepository;
import com.usora.identity.repository.UserRepository;
import com.usora.identity.security.JwtTokenProvider;
import com.usora.identity.security.PermissionEvaluator;
import com.usora.identity.security.TenantContext;
import com.usora.identity.util.HashingUtil;
import com.usora.identity.util.IdGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DomainService {

    /**
     * F-004 remediation: previously only the client_credentials path set an
     * "aud" claim at all, and it set it to the tenant's name -- meaning
     * every tenant had its own audience value, tenant identity was
     * (incorrectly) duplicated between "tid" and "aud", and the other three
     * grant types (authorization_code, refresh_token, password) issued
     * tokens with no audience claim whatsoever. That's why the gateway's
     * JwtValidator historically had to treat audience enforcement as
     * optional (see IdentityConfig::audience in the gateway) -- there was
     * no single value it could safely require across all tenants and grant
     * types without rejecting valid tokens.
     *
     * Every token this service issues now carries the same stable service
     * audience, "usora-api" -- identifying this platform's API surface as
     * the intended consumer, the same way every token shares one issuer.
     * Tenant identity stays exactly where it already correctly lives: the
     * "tid" claim. This constant must match the gateway's expected audience
     * (IdentityConfig::audience / JWT_AUDIENCE).
     */
    private static final String SERVICE_AUDIENCE = "usora-api";

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final OAuth2ClientRepository oAuth2ClientRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final PermissionEvaluator permissionEvaluator;
    private final PasswordEncoder passwordEncoder;
    private final EntityMapper entityMapper;
    private final DomainEventPublisher eventPublisher;
    private final StringRedisTemplate redisTemplate;
    private final TenantConfig tenantConfig;
    private final MeterRegistry meterRegistry;

    // See SecurityConfig.oauth2Issuer() for why this must be configured
    // rather than left as the "http://localhost:8081" literal this class
    // used to hardcode in four separate token-issuance call sites plus the
    // well-known OIDC metadata below -- those five copies could (and, per
    // the audit that found this, effectively already had) drift out of sync
    // with the real deployment's issuer.
    @org.springframework.beans.factory.annotation.Value("${OAUTH2_ISSUER:}")
    private String configuredIssuer;

    private String issuer() {
        if (configuredIssuer == null || configuredIssuer.isBlank()) {
            throw new IllegalStateException(
                    "OAUTH2_ISSUER must be set -- refusing to issue a token or serve OIDC metadata with no " +
                    "configured issuer.");
        }
        return configuredIssuer;
    }

    public ResponseDto.TokenResponse authenticate(RequestDto.TokenRequest request) {
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            var client = oAuth2ClientRepository.findByClientId(request.getClientId())
                    .orElseThrow(() -> BusinessException.unauthorized("Invalid client"));

            validateBruteForce(client.getClientId());

            return switch (request.getGrantType()) {
                case "client_credentials" -> handleClientCredentials(request, client);
                case "authorization_code" -> handleAuthorizationCode(request, client);
                case "refresh_token" -> handleRefreshToken(request, client);
                case "password" -> handlePasswordGrant(request, client);
                default -> throw BusinessException.badRequest("Unsupported grant type: " + request.getGrantType());
            };
        } finally {
            sample.stop(meterRegistry.timer("identity_auth_duration_seconds",
                    "grant_type", request.getGrantType() != null ? request.getGrantType() : "unknown"));
        }
    }

    private ResponseDto.TokenResponse handleClientCredentials(RequestDto.TokenRequest request,
                                                                TenantEntity.OAuth2ClientEntity client) {
        if (client.getClientSecret() != null && request.getClientSecret() != null) {
            if (!passwordEncoder.matches(request.getClientSecret(), client.getClientSecret())) {
                recordFailedAttempt(client.getClientId());
                throw BusinessException.unauthorized("Invalid client credentials");
            }
        }

        if (!client.getGrantTypes().contains("client_credentials")) {
            throw BusinessException.badRequest("Client not authorized for client_credentials grant");
        }

        var tenantId = client.getTenant().getId().toString();
        var tenantContext = TenantContext.getContext();
        tenantContext.setTenantId(tenantId);
        tenantContext.setClientId(client.getClientId());

        try {
            var claims = new JWTClaimsSet.Builder()
                    .subject(client.getClientId())
                    .issuer(issuer())
                    .audience(Collections.singletonList(SERVICE_AUDIENCE))
                    .issueTime(new Date())
                    .expirationTime(Date.from(Instant.now().plus(client.getAccessTokenTtlSeconds(), ChronoUnit.SECONDS)))
                    .claim("tid", tenantId)
                    .claim("cid", client.getClientId())
                    .claim("scope", String.join(" ", client.getScopes()))
                    .claim("roles", List.of("client"))
                    .claim("grant_type", "client_credentials")
                    .build();

            var accessToken = jwtTokenProvider.signToken(claims, tenantId);

            meterRegistry.counter("identity_token_issued_total",
                    "grant_type", "client_credentials",
                    "tenant", tenantId).increment();

            eventPublisher.publishTokenEvent("token.issued", client.getClientId(), tenantId, client.getClientId());

            return ResponseDto.TokenResponse.builder()
                    .accessToken(accessToken)
                    .tokenType("Bearer")
                    .expiresIn(client.getAccessTokenTtlSeconds())
                    .scope(String.join(" ", client.getScopes()))
                    .build();
        } catch (JOSEException e) {
            log.error("Failed to sign token", e);
            throw BusinessException.badRequest("Token generation failed");
        } finally {
            TenantContext.clear();
        }
    }

    private ResponseDto.TokenResponse handleAuthorizationCode(RequestDto.TokenRequest request,
                                                                TenantEntity.OAuth2ClientEntity client) {
        if (client.isRequirePkce() && request.getCodeVerifier() == null) {
            throw BusinessException.badRequest("PKCE code_verifier is required for this client");
        }

        if (client.isRequirePkce() && request.getCodeVerifier() != null) {
            if (!verifyPkce(request.getCodeVerifier())) {
                recordFailedAttempt(client.getClientId());
                throw BusinessException.badRequest("PKCE verification failed");
            }
        }

        var tenantId = client.getTenant().getId().toString();
        var tenantContext = TenantContext.getContext();
        tenantContext.setTenantId(tenantId);
        tenantContext.setClientId(client.getClientId());

        try {
            var claims = new JWTClaimsSet.Builder()
                    .subject(request.getClientId())
                    .issuer(issuer())
                    .audience(Collections.singletonList(SERVICE_AUDIENCE))
                    .issueTime(new Date())
                    .expirationTime(Date.from(Instant.now().plus(client.getAccessTokenTtlSeconds(), ChronoUnit.SECONDS)))
                    .claim("tid", tenantId)
                    .claim("cid", client.getClientId())
                    .claim("scope", String.join(" ", client.getScopes()))
                    .claim("nonce", IdGenerator.secureToken())
                    .build();

            var accessToken = jwtTokenProvider.signToken(claims, tenantId);
            var refreshToken = generateRefreshToken(client, tenantId);

            meterRegistry.counter("identity_token_issued_total",
                    "grant_type", "authorization_code",
                    "tenant", tenantId).increment();

            return ResponseDto.TokenResponse.builder()
                    .accessToken(accessToken)
                    .tokenType("Bearer")
                    .expiresIn(client.getAccessTokenTtlSeconds())
                    .refreshToken(refreshToken)
                    .scope(String.join(" ", client.getScopes()))
                    .build();
        } catch (JOSEException e) {
            log.error("Failed to sign token", e);
            throw BusinessException.badRequest("Token generation failed");
        } finally {
            TenantContext.clear();
        }
    }

    private ResponseDto.TokenResponse handleRefreshToken(RequestDto.TokenRequest request,
                                                          TenantEntity.OAuth2ClientEntity client) {
        var storedRefreshToken = redisTemplate.opsForValue().get("token:refresh:" + request.getRefreshToken());
        if (storedRefreshToken == null) {
            throw BusinessException.unauthorized("Invalid or expired refresh token");
        }

        redisTemplate.delete("token:refresh:" + request.getRefreshToken());

        var parts = storedRefreshToken.split(":");
        if (parts.length < 2) {
            throw BusinessException.unauthorized("Invalid refresh token data");
        }

        var tenantId = parts[0];
        var tenantContext = TenantContext.getContext();
        tenantContext.setTenantId(tenantId);
        tenantContext.setClientId(client.getClientId());

        try {
            var claims = new JWTClaimsSet.Builder()
                    .subject(client.getClientId())
                    .issuer(issuer())
                    .audience(Collections.singletonList(SERVICE_AUDIENCE))
                    .issueTime(new Date())
                    .expirationTime(Date.from(Instant.now().plus(client.getAccessTokenTtlSeconds(), ChronoUnit.SECONDS)))
                    .claim("tid", tenantId)
                    .claim("cid", client.getClientId())
                    .claim("scope", String.join(" ", client.getScopes()))
                    .build();

            var accessToken = jwtTokenProvider.signToken(claims, tenantId);
            var newRefreshToken = generateRefreshToken(client, tenantId);

            meterRegistry.counter("identity_token_issued_total",
                    "grant_type", "refresh_token",
                    "tenant", tenantId).increment();

            return ResponseDto.TokenResponse.builder()
                    .accessToken(accessToken)
                    .tokenType("Bearer")
                    .expiresIn(client.getAccessTokenTtlSeconds())
                    .refreshToken(newRefreshToken)
                    .scope(String.join(" ", client.getScopes()))
                    .build();
        } catch (JOSEException e) {
            log.error("Failed to sign token", e);
            throw BusinessException.badRequest("Token generation failed");
        } finally {
            TenantContext.clear();
        }
    }

    private ResponseDto.TokenResponse handlePasswordGrant(RequestDto.TokenRequest request,
                                                           TenantEntity.OAuth2ClientEntity client) {
        if (request.getUsername() == null || request.getPassword() == null) {
            throw BusinessException.badRequest("Username and password are required");
        }

        var user = userRepository.findByUsernameAndTenantId(request.getUsername(), client.getTenant().getId())
                .orElseThrow(() -> {
                    recordFailedAttempt(client.getClientId());
                    return BusinessException.unauthorized("Invalid username or password");
                });

        if (!user.isEnabled()) {
            throw BusinessException.forbidden("Account is disabled");
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            recordFailedAttempt(client.getClientId());
            throw BusinessException.unauthorized("Invalid username or password");
        }

        clearBruteForceCounter(client.getClientId());

        var tenantId = client.getTenant().getId().toString();
        var tenantContext = TenantContext.getContext();
        tenantContext.setTenantId(tenantId);
        tenantContext.setClientId(client.getClientId());
        tenantContext.setUserId(user.getId().toString());

        try {
            var claims = new JWTClaimsSet.Builder()
                    .subject(user.getId().toString())
                    .issuer(issuer())
                    .audience(Collections.singletonList(SERVICE_AUDIENCE))
                    .issueTime(new Date())
                    .expirationTime(Date.from(Instant.now().plus(client.getAccessTokenTtlSeconds(), ChronoUnit.SECONDS)))
                    .claim("tid", tenantId)
                    .claim("cid", client.getClientId())
                    .claim("username", user.getUsername())
                    .claim("email", user.getEmail())
                    .claim("roles", user.getRoles() != null ? List.copyOf(user.getRoles()) : List.of())
                    .claim("scope", String.join(" ", client.getScopes()))
                    .build();

            var accessToken = jwtTokenProvider.signToken(claims, tenantId);
            var refreshToken = generateRefreshToken(client, tenantId);

            user.setLastLoginAt(Instant.now());
            userRepository.save(user);

            meterRegistry.counter("identity_auth_total",
                    "grant_type", "password",
                    "tenant", tenantId).increment();

            eventPublisher.publishAuthEvent("auth.login.success", Map.of(
                    "user_id", user.getId().toString(),
                    "tenant_id", tenantId,
                    "username", user.getUsername()
            ));

            return ResponseDto.TokenResponse.builder()
                    .accessToken(accessToken)
                    .tokenType("Bearer")
                    .expiresIn(client.getAccessTokenTtlSeconds())
                    .refreshToken(refreshToken)
                    .scope(String.join(" ", client.getScopes()))
                    .build();
        } catch (JOSEException e) {
            log.error("Failed to sign token", e);
            throw BusinessException.badRequest("Token generation failed");
        } finally {
            TenantContext.clear();
        }
    }

    public ResponseDto.IntrospectResponse introspect(String token) {
        if (token == null || token.isBlank()) {
            return ResponseDto.IntrospectResponse.builder().active(false).build();
        }

        try {
            var claims = jwtTokenProvider.parseToken(token);
            var expiry = claims.getExpirationTime();
            if (expiry != null && expiry.before(new Date())) {
                return ResponseDto.IntrospectResponse.builder().active(false).build();
            }

            var tid = claims.getStringClaim("tid");
            var valid = jwtTokenProvider.validateToken(token, tid);
            if (!valid) {
                return ResponseDto.IntrospectResponse.builder().active(false).build();
            }

            var rolesList = claims.getStringListClaim("roles");
            var scope = claims.getStringClaim("scope");

            return ResponseDto.IntrospectResponse.builder()
                    .active(true)
                    .sub(claims.getSubject())
                    .tid(tid)
                    .clientId(claims.getStringClaim("cid"))
                    .exp(claims.getExpirationTime() != null ? claims.getExpirationTime().toInstant() : null)
                    .iat(claims.getIssueTime() != null ? claims.getIssueTime().toInstant() : null)
                    .scope(scope)
                    .roles(rolesList != null ? Set.copyOf(rolesList) : Set.of())
                    .tokenType("Bearer")
                    .username(claims.getStringClaim("username"))
                    .build();
        } catch (Exception e) {
            log.warn("Token introspection failed: {}", e.getMessage());
            return ResponseDto.IntrospectResponse.builder().active(false).build();
        }
    }

    public void revoke(String token) {
        if (token == null || token.isBlank()) {
            return;
        }

        try {
            var claims = jwtTokenProvider.parseToken(token);
            var tid = claims.getStringClaim("tid");
            var jti = claims.getJWTID();

            if (jti != null) {
                redisTemplate.opsForValue().set(
                        "token:revoked:" + jti, "revoked",
                        tenantConfig.getJwt().getAccessTokenTtl(), TimeUnit.SECONDS);
            }

            var refreshKey = "token:refresh:" + token;
            redisTemplate.delete(refreshKey);

            eventPublisher.publishTokenEvent("token.revoked", jti, tid, claims.getStringClaim("cid"));
            log.info("Token revoked: jti={}, tid={}", jti, tid);
        } catch (Exception e) {
            log.warn("Token revocation failed: {}", e.getMessage());
        }
    }

    @Transactional
    public ResponseDto.UserResponse createUser(RequestDto.UserCreateRequest request) {
        var tenant = tenantRepository.findActiveById(UUID.fromString(request.getTenantId()))
                .orElseThrow(() -> BusinessException.notFound("Tenant not found: " + request.getTenantId()));

        // SECURITY: this endpoint is gated only by a global SCOPE_admin
        // authority (see SecurityConfig's requestMatchers for /api/v1/users/**),
        // and previously trusted request.getTenantId() from the request body
        // with no check that it matched the authenticated caller's own
        // tenant. If SCOPE_admin is ever granted per-tenant rather than
        // globally (status not determinable from source alone -- see the
        // audit that flagged this), that's a cross-tenant user-creation IDOR:
        // any tenant admin could create a user in a tenant they don't
        // administer just by putting a different tenantId in the body.
        // Reject a mismatch regardless of how admin scope actually works,
        // rather than relying on that being correctly configured elsewhere.
        var callerTenantId = TenantContext.getContext().getTenantId();
        if (callerTenantId != null) {
            if (!callerTenantId.equals(request.getTenantId())) {
                throw BusinessException.forbidden(
                        "Cannot create a user in a tenant other than the caller's own tenant");
            }
        } else {
            // F-017: the check above previously ran ONLY when
            // callerTenantId was non-null -- a caller with no tenant
            // context at all (e.g. this service's own "usora-api"
            // client_credentials client, which is granted the "admin"
            // scope but has no tid claim at token-mint time; see the
            // RISK TO VERIFY note on PR #150) fell through with NO tenant
            // check whatsoever. That is not a narrower case of the
            // tenant-mismatch bug, it is a strictly worse one: unrestricted
            // cross-tenant write access to any tenant named in the request
            // body, gated by nothing but the same global "admin" scope
            // every ordinary tenant admin also holds.
            //
            // A platform-wide admin action is legitimate (break-glass
            // support operations, initial tenant setup, etc.) but must be
            // explicit, not incidental to how a token happened to be
            // minted. Require a distinct, elevated permission separate
            // from "admin" itself, plus a mandatory justification that
            // becomes part of the audit trail.
            requirePlatformAdminAuthorization(request.getPlatformAdminReason());
        }

        if (userRepository.existsByUsernameAndTenantId(request.getUsername(), tenant.getId())) {
            throw BusinessException.conflict("Username already exists in tenant");
        }

        if (request.getEmail() != null && userRepository.existsByEmailAndTenantId(request.getEmail(), tenant.getId())) {
            throw BusinessException.conflict("Email already exists in tenant");
        }

        var userEntity = entityMapper.toUserEntity(request, tenant);
        if (request.getPassword() != null) {
            userEntity.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        }

        var saved = userRepository.save(userEntity);

        // BUG found while implementing F-017: this called
        // publishTokenEvent(String, String, String, String clientId), but
        // passed a Map<String,String> as the fourth argument -- a type
        // mismatch that could not have compiled as written. The
        // semantically correct call for a user-creation event is
        // publishUserEvent(type, userId, tenantId, changes), which both
        // matches the intended event shape (a user event with change
        // details, not a token event with a client id) and actually
        // compiles.
        var eventDetails = new java.util.HashMap<String, Object>();
        eventDetails.put("username", saved.getUsername());
        eventDetails.put("email", saved.getEmail());
        if (callerTenantId == null) {
            // F-017: mark platform-admin actions distinctly in the audit
            // trail rather than recording them identically to an ordinary
            // tenant-scoped user creation -- the acceptance criterion is
            // "platform admin actions ... generate enhanced audit events".
            eventDetails.put("platform_admin_action", true);
            eventDetails.put("platform_admin_reason", request.getPlatformAdminReason());
        }
        eventPublisher.publishUserEvent("user.created", saved.getId().toString(), tenant.getId().toString(),
                eventDetails);

        return entityMapper.toUserResponse(saved);
    }

    /**
     * F-017: shared gate for any admin operation reached with no tenant
     * context of its own (see createUser/updateUserRoles). Requires a
     * distinct "platform:admin" authority -- separate from the "admin"
     * scope that gates the endpoint itself -- plus a non-blank
     * justification, so a platform-wide action is always an explicit,
     * accountable decision rather than an incidental side effect of how a
     * particular caller's token happened to be minted.
     */
    private void requirePlatformAdminAuthorization(String reason) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean hasPlatformAdminScope = authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> "SCOPE_platform:admin".equals(a.getAuthority()));

        if (!hasPlatformAdminScope) {
            throw BusinessException.forbidden(
                    "This action targets a tenant outside the caller's own tenant context and requires the " +
                    "platform:admin scope, which the caller does not have. A caller with only the general " +
                    "'admin' scope and no tenant binding cannot perform cross-tenant actions.");
        }

        if (reason == null || reason.isBlank()) {
            throw BusinessException.badRequest(
                    "platformAdminReason is required for a platform-admin action with no tenant context");
        }
    }

    @Transactional
    public ResponseDto.UserResponse updateUserRoles(String userId, RequestDto.RoleUpdateRequest request) {
        var user = userRepository.findById(UUID.fromString(userId))
                .orElseThrow(() -> BusinessException.notFound("User not found: " + userId));

        // SECURITY: same class of bug as createUser above -- this loaded the
        // target user purely by id with no check that the user belongs to
        // the caller's own tenant, so a caller could reassign roles for a
        // user in a tenant they don't administer.
        var callerTenantId = TenantContext.getContext().getTenantId();
        if (callerTenantId != null) {
            if (!callerTenantId.equals(user.getTenant().getId().toString())) {
                throw BusinessException.forbidden(
                        "Cannot update roles for a user in a tenant other than the caller's own tenant");
            }
        } else {
            // F-017: same fail-open gap as createUser -- a caller with no
            // tenant context at all previously bypassed this check
            // entirely, rather than being subject to it. Same fix: an
            // explicit elevated permission plus a mandatory reason.
            requirePlatformAdminAuthorization(request.getPlatformAdminReason());
        }

        var roles = new HashSet<>(user.getRoles() != null ? user.getRoles() : Set.of());
        if (request.getAddRoles() != null) {
            roles.addAll(request.getAddRoles());
        }
        if (request.getRemoveRoles() != null) {
            roles.removeAll(request.getRemoveRoles());
        }

        user.setRoles(roles);
        var saved = userRepository.save(user);

        var rolesEventDetails = new java.util.HashMap<String, Object>();
        rolesEventDetails.put("roles", roles);
        if (callerTenantId == null) {
            rolesEventDetails.put("platform_admin_action", true);
            rolesEventDetails.put("platform_admin_reason", request.getPlatformAdminReason());
        }
        eventPublisher.publishUserEvent("user.roles.updated", saved.getId().toString(),
                saved.getTenant().getId().toString(), rolesEventDetails);

        return entityMapper.toUserResponse(saved);
    }

    public Map<String, Object> getOpenIdConfiguration() {
        var baseUrl = issuer();
        return Map.ofEntries(
                Map.entry("issuer", baseUrl),
                Map.entry("authorization_endpoint", baseUrl + "/oauth2/authorize"),
                Map.entry("token_endpoint", baseUrl + "/oauth2/token"),
                Map.entry("introspection_endpoint", baseUrl + "/oauth2/introspect"),
                Map.entry("revocation_endpoint", baseUrl + "/oauth2/revoke"),
                Map.entry("jwks_uri", baseUrl + "/oauth2/jwks"),
                Map.entry("userinfo_endpoint", baseUrl + "/oidc/userinfo"),
                Map.entry("registration_endpoint", baseUrl + "/oidc/register"),
                Map.entry("scopes_supported", List.of("openid", "profile", "email", "admin", "tenant:read", "tenant:write", "users:read", "users:write")),
                Map.entry("response_types_supported", List.of("code", "token")),
                Map.entry("grant_types_supported", List.of("authorization_code", "client_credentials", "refresh_token", "password")),
                Map.entry("token_endpoint_auth_methods_supported", List.of("client_secret_basic", "client_secret_post", "none")),
                Map.entry("claims_supported", List.of("sub", "tid", "cid", "roles", "permissions", "scope", "username", "email")),
                Map.entry("code_challenge_methods_supported", List.of("S256", "plain")),
                Map.entry("id_token_signing_alg_values_supported", List.of("RS256"))
        );
    }

    public Map<String, Object> getUserinfo(Jwt jwt) {
        var claims = jwt.getClaims();
        var userinfo = new HashMap<String, Object>();
        userinfo.put("sub", jwt.getSubject());
        userinfo.put("tid", claims.get("tid"));

        if (claims.containsKey("username")) userinfo.put("preferred_username", claims.get("username"));
        if (claims.containsKey("email")) userinfo.put("email", claims.get("email"));
        if (claims.containsKey("roles")) userinfo.put("roles", claims.get("roles"));

        return userinfo;
    }

    private String generateRefreshToken(TenantEntity.OAuth2ClientEntity client, String tenantId) {
        var refreshToken = IdGenerator.secureToken();
        redisTemplate.opsForValue().set(
                "token:refresh:" + refreshToken,
                tenantId + ":" + client.getClientId(),
                client.getRefreshTokenTtlSeconds(), TimeUnit.SECONDS);
        return refreshToken;
    }

    private boolean verifyPkce(String codeVerifier) {
        if (codeVerifier == null || codeVerifier.isBlank()) {
            return false;
        }
        return codeVerifier.length() >= 43 && codeVerifier.length() <= 128;
    }

    private void validateBruteForce(String clientId) {
        var counterKey = "bruteforce:" + clientId;
        var attempts = redisTemplate.opsForValue().get(counterKey);
        if (attempts != null) {
            int count = Integer.parseInt(attempts);
            if (count >= tenantConfig.getBruteForce().getMaxAttempts()) {
                var ttl = redisTemplate.getExpire(counterKey);
                if (ttl != null && ttl > 0) {
                    log.warn("Brute force lockout for client: {}", clientId);
                    throw BusinessException.rateLimited("Too many authentication attempts. Please try again later.");
                }
            }
        }
    }

    private void recordFailedAttempt(String clientId) {
        var counterKey = "bruteforce:" + clientId;
        redisTemplate.opsForValue().increment(counterKey);
        redisTemplate.expire(counterKey, tenantConfig.getBruteForce().getWindowSeconds(), TimeUnit.SECONDS);

        meterRegistry.counter("identity_auth_failure_total",
                "client_id", clientId).increment();
    }

    private void clearBruteForceCounter(String clientId) {
        redisTemplate.delete("bruteforce:" + clientId);
    }
}
