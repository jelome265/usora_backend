package com.usora.audit.config;

import com.usora.audit.security.TenantInterceptor;
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
 * (audit.security.jwt.secret / JwtTokenProvider) -- a parallel credential
 * system entirely distinct from usora-identity-service's actual
 * RS256/JWKS-issued tokens, with its own independent secret and no
 * relationship to the platform's real token issuer. A token genuinely
 * issued by identity-service could not previously be validated here at
 * all (wrong algorithm, wrong key material).
 *
 * Now standardized on the same JWKS-backed oauth2ResourceServer contract
 * core-service uses (see spring.security.oauth2.resourceserver.jwt.
 * jwk-set-uri in application.yml). The custom JwtTokenProvider
 * (HMAC-based) is removed entirely -- see git history if it's ever
 * needed for reference.
 *
 * Authority mapping is unchanged: identity-service issues a "permissions"
 * claim (a plain list of scope strings), and this service's existing
 * requestMatchers() checks expect "SCOPE_"-prefixed authorities (e.g.
 * hasAuthority("SCOPE_audit:read")), so authorityPrefix("SCOPE_") +
 * authoritiesClaimName("permissions") are preserved exactly as before --
 * only the JwtDecoder underneath changes.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig implements WebMvcConfigurer {

    private final TenantInterceptor tenantInterceptor;

    public SecurityConfig(TenantInterceptor tenantInterceptor) {
        this.tenantInterceptor = tenantInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tenantInterceptor)
                .addPathPatterns("/api/**");
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/prometheus").hasAuthority("SCOPE_audit:read")
                        .requestMatchers(HttpMethod.POST, "/api/v1/audit/events").hasAuthority("SCOPE_audit:write")
                        .requestMatchers(HttpMethod.GET, "/api/v1/audit/trail/**").hasAuthority("SCOPE_audit:read")
                        .requestMatchers(HttpMethod.POST, "/api/v1/audit/verify").hasAuthority("SCOPE_audit:read")
                        .requestMatchers(HttpMethod.POST, "/api/v1/audit/search").hasAuthority("SCOPE_audit:read")
                        .requestMatchers(HttpMethod.POST, "/api/v1/audit/reports/compliance").hasAuthority("SCOPE_audit:admin")
                        .requestMatchers(HttpMethod.GET, "/api/v1/audit/tamper-alerts").hasAuthority("SCOPE_audit:admin")
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
        JwtGrantedAuthoritiesConverter grantedAuthoritiesConverter = new JwtGrantedAuthoritiesConverter();
        grantedAuthoritiesConverter.setAuthorityPrefix("SCOPE_");
        grantedAuthoritiesConverter.setAuthoritiesClaimName("permissions");

        JwtAuthenticationConverter jwtAuthenticationConverter = new JwtAuthenticationConverter();
        jwtAuthenticationConverter.setJwtGrantedAuthoritiesConverter(grantedAuthoritiesConverter);
        return jwtAuthenticationConverter;
    }
}
