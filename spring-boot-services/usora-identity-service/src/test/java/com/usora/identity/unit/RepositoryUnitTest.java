package com.usora.identity.unit;

import com.usora.identity.entity.TenantEntity;
import com.usora.identity.repository.OAuth2ClientRepository;
import com.usora.identity.repository.TenantRepository;
import com.usora.identity.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import jakarta.persistence.EntityManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.flyway.enabled=false"
})
class RepositoryUnitTest {

    @Autowired
    private EntityManager em;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private OAuth2ClientRepository clientRepository;

    @Autowired
    private UserRepository userRepository;

    private TenantEntity tenant;

    @BeforeEach
    void setUp() {
        tenant = TenantEntity.builder()
                .id(UUID.randomUUID())
                .tenantName("repo-test-tenant")
                .domain("repo-test.usora.io")
                .enabled(true)
                .keyAlgorithm("RS256")
                .build();
        em.persist(tenant);
        em.flush();
    }

    @Test
    void shouldSaveAndFindTenant() {
        var found = tenantRepository.findById(tenant.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getTenantName()).isEqualTo("repo-test-tenant");
    }

    @Test
    void shouldFindActiveTenant() {
        var found = tenantRepository.findActiveById(tenant.getId());
        assertThat(found).isPresent();
    }

    @Test
    void shouldFindByDomain() {
        var found = tenantRepository.findByDomain("repo-test.usora.io");
        assertThat(found).isPresent();
    }

    @Test
    void shouldSaveOAuth2Client() {
        var client = TenantEntity.OAuth2ClientEntity.builder()
                .id(UUID.randomUUID())
                .clientId("repo-test-client")
                .clientName("Repo Test Client")
                .tenant(tenant)
                .grantTypes(Set.of("client_credentials"))
                .scopes(Set.of("admin"))
                .accessTokenTtlSeconds(900)
                .refreshTokenTtlSeconds(604800)
                .enabled(true)
                .build();
        em.persist(client);
        em.flush();

        var found = clientRepository.findByClientId("repo-test-client");
        assertThat(found).isPresent();
        assertThat(found.get().getTenant().getId()).isEqualTo(tenant.getId());
    }

    @Test
    void shouldSaveAndFindUser() {
        var user = TenantEntity.UserEntity.builder()
                .id(UUID.randomUUID())
                .username("repo-test-user")
                .email("repo-test@example.com")
                .passwordHash("{bcrypt}hash")
                .displayName("Repo Test User")
                .enabled(true)
                .roles(Set.of("user"))
                .tenant(tenant)
                .build();
        em.persist(user);
        em.flush();

        var found = userRepository.findByUsernameAndTenantId("repo-test-user", tenant.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getEmail()).isEqualTo("repo-test@example.com");
    }

    @Test
    void shouldCheckUserExistence() {
        var user = TenantEntity.UserEntity.builder()
                .id(UUID.randomUUID())
                .username("unique-user")
                .email("unique@example.com")
                .tenant(tenant)
                .enabled(true)
                .build();
        em.persist(user);
        em.flush();

        assertThat(userRepository.existsByUsernameAndTenantId("unique-user", tenant.getId())).isTrue();
        assertThat(userRepository.existsByUsernameAndTenantId("nonexistent", tenant.getId())).isFalse();
    }
}
