package com.usora.notification.config;

import com.usora.notification.security.TenantInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * F-011: this service previously verified tokens with its own HMAC secret
 * (security.jwt.secret / JwtTokenProvider) -- a parallel credential system
 * entirely distinct from usora-identity-service's actual RS256/JWKS-issued
 * tokens, with its own (nonexistent) rotation, and no relationship to the
 * platform's real token issuer. A token genuinely issued by
 * identity-service could not previously be validated here at all (wrong
 * algorithm, wrong key material) -- see the SEPARATE FINDING note that
 * used to be on TenantInterceptor documenting exactly this gap.
 *
 * Now standardized on the same JWKS-backed oauth2ResourceServer contract
 * core-service and audit-service already use (see
 * usora.security.jwk-set-uri / spring.security.oauth2.resourceserver.jwt.
 * jwk-set-uri in application.yml). The custom JwtTokenProvider and
 * JwtAuthenticationFilter (HMAC-based) are removed -- see git history if
 * either is ever needed for reference.
 *
 * Authority mapping intentionally differs from core-service/
 * audit-service's "ROLE_"-prefixed convention: identity-service issues a
 * plain "roles" claim (see DomainService's password-grant claims, e.g.
 * "notification:send") and this service's existing authorization rules
 * below already check hasAuthority("notification:send") with no prefix --
 * matching what the old HMAC-based JwtTokenProvider.getAuthentication()
 * produced (SimpleGrantedAuthority per role string, unprefixed). Using an
 * empty prefix here preserves those existing authorization checks exactly
 * as written, rather than requiring every requestMatchers() rule in this
 * file to be rewritten to hasRole(...) alongside the auth-model migration.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig implements WebMvcConfigurer {

    private final TenantInterceptor tenantInterceptor;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> {})
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                .requestMatchers("/actuator/prometheus").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/notifications/send").hasAuthority("notification:send")
                .requestMatchers(HttpMethod.GET, "/api/v1/notifications/**").hasAuthority("notification:read")
                .requestMatchers(HttpMethod.POST, "/api/v1/notifications/*/acknowledge").hasAuthority("notification:send")
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt
                        .jwtAuthenticationConverter(jwtAuthenticationConverter())
                )
            );

        return http.build();
    }

    @Bean
    public JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri) {
        return NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        var grantedAuthoritiesConverter = new JwtGrantedAuthoritiesConverter();
        grantedAuthoritiesConverter.setAuthorityPrefix("");
        grantedAuthoritiesConverter.setAuthoritiesClaimName("roles");

        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(grantedAuthoritiesConverter);
        return converter;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tenantInterceptor)
                .addPathPatterns("/api/v1/**");
    }
}
