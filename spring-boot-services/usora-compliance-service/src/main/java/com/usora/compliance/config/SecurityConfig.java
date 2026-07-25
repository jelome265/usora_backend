package com.usora.compliance.config;

import com.usora.compliance.security.JwtTokenProvider;
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
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtTokenProvider jwtTokenProvider;

    public SecurityConfig(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

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
                .addFilterBefore(new JwtTokenFilter(jwtTokenProvider), UsernamePasswordAuthenticationFilter.class)
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
