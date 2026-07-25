package com.usora.compliance.config;

import org.kie.api.KieServices;
import org.kie.api.builder.KieBuilder;
import org.kie.api.builder.KieFileSystem;
import org.kie.api.builder.KieRepository;
import org.kie.api.builder.ReleaseId;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;
import org.kie.internal.io.ResourceFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;

@Configuration
public class DroolsConfig {

    private static final String RULES_PATH = "drools/rules/";

    @Bean
    public KieServices kieServices() {
        return KieServices.Factory.get();
    }

    @Bean
    public KieContainer kieContainer(KieServices kieServices) throws IOException {
        var fileSystem = kieServices.newKieFileSystem();
        var resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath*:" + RULES_PATH + "**/*.drl");

        for (var resource : resources) {
            fileSystem.write(ResourceFactory.newClassPathResource(RULES_PATH + resource.getFilename(), "UTF-8"));
        }

        var builder = kieServices.newKieBuilder(fileSystem);
        builder.buildAll();

        if (builder.getResults().hasMessages(org.kie.api.builder.Message.Level.ERROR)) {
            throw new RuntimeException("Drools build errors: " + builder.getResults().toString());
        }

        var repository = kieServices.getRepository();
        var defaultReleaseId = repository.getDefaultReleaseId();

        return kieServices.newKieContainer(defaultReleaseId != null
                ? defaultReleaseId
                : kieServices.newReleaseId("com.usora", "usora-compliance-service", "1.0.0"));
    }

    @Bean
    public KieSession kieSession(KieContainer kieContainer) {
        return kieContainer.newKieSession();
    }

    public KieFileSystem createTenantRuleFileSystem(KieServices kieServices, String tenantId, String rulesDrl) {
        var fileSystem = kieServices.newKieFileSystem();
        fileSystem.write(ResourceFactory.newByteArrayResource(rulesDrl.getBytes())
                .setSourcePath("compliance/rules/tenant_" + tenantId + ".drl"));
        return fileSystem;
    }

    public KieContainer buildTenantContainer(KieServices kieServices, KieFileSystem fileSystem) {
        var builder = kieServices.newKieBuilder(fileSystem);
        builder.buildAll();
        if (builder.getResults().hasMessages(org.kie.api.builder.Message.Level.ERROR)) {
            throw new RuntimeException("Tenant rule build error: " + builder.getResults().toString());
        }
        return kieServices.newKieContainer(kieServices.getRepository().getDefaultReleaseId());
    }
}
