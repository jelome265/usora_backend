package com.usora.compliance.config;

import com.usora.compliance.security.PermissionEvaluator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * F-011: this service previously ran TWO separate, competing
 * authentication mechanisms in the same filter chain: a correctly
 * JWKS-backed oauth2ResourceServer (auto-configured from
 * spring.security.oauth2.resourceserver.jwt.jwk-set-uri, already present)
 * AND a legacy JwtTokenFilter registered via addFilterBefore(...,
 * UsernamePasswordAuthenticationFilter.class), which independently
 * verified the same bearer token against its own HMAC secret
 * (compliance.security.jwt-secret) via JwtTokenProvider and set the
 * SecurityContext manually before the OAuth2 filter even ran. Whichever
 * filter's Authentication ended up in the context first governed the
 * request; a token that failed one check could still succeed via the
 * other, and a token genuinely issued by identity-service (RS256/JWKS)
 * would never have satisfied the HMAC filter, while a token forged
 * against the HMAC secret would never satisfy real JWKS-based
 * verification -- but there was no guarantee only one of these paths
 * ever ran for a given request. The JwtTokenFilter/HMAC path is removed
 * entirely; JwtTokenProvider itself is not deleted (see its own javadoc)
 * since DomainService still needs it for verifying dual-authorization
 * approval tokens, now against the same real JWKS source.
 *
 * The jwk-set-uri value itself was also fixed (see application.yml /
 * application-dev.yml / application-prod.yml) -- it pointed at a stale,
 * Keycloak-style ".../protocol/openid-connect/certs" path rather than
 * identity-service's actual "/oauth2/jwks" endpoint used by every other
 * service in this repo.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/**").hasRole("compliance:admin")
                        .requestMatchers(HttpMethod.GET, "/api/v1/compliance/audit/**").hasAnyRole("compliance:read", "compliance:manage", "compliance:admin")
                        .requestMatchers(HttpMethod.GET, "/api/v1/compliance/rules").hasAnyRole("compliance:read", "compliance:manage", "compliance:admin")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/compliance/rules").hasRole("compliance:admin")
                        .requestMatchers(HttpMethod.POST, "/api/v1/compliance/reports").hasAnyRole("compliance:read", "compliance:manage")
                        .requestMatchers(HttpMethod.POST, "/api/v1/compliance/validate").hasAnyRole("compliance:manage", "compliance:admin")
                        .requestMatchers(HttpMethod.POST, "/api/v1/compliance/jurisdiction-check").hasAnyRole("compliance:manage", "compliance:admin")
                        .requestMatchers(HttpMethod.POST, "/api/v1/compliance/evidence").hasRole("compliance:manage")
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt
                                .jwtAuthenticationConverter(jwtAuthenticationConverter())
                        )
                )
                .build();
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        var grantedAuthoritiesConverter = new JwtGrantedAuthoritiesConverter();
        grantedAuthoritiesConverter.setAuthorityPrefix("ROLE_");
        grantedAuthoritiesConverter.setAuthoritiesClaimName("roles");

        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(grantedAuthoritiesConverter);
        return converter;
    }

    @Bean
    public PermissionEvaluator permissionEvaluator() {
        return new PermissionEvaluator();
    }
}
