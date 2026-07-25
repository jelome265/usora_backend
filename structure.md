usora-backend
├── .github
│   └── workflows
│       ├── ci-cd.yml
│       └── security-scan.yml
├── docs
│   ├── api
│   │   └── openapi-spec.yml
│   ├── architecture
│   │   ├── system-overview.md
│   │   └── tenant-isolation.md
│   └── runbooks
│       └── incident-response.md
├── infrastructure
│   ├── docker
│   │   ├── Dockerfile.rust
│   │   └── Dockerfile.spring-boot
│   ├── helm
│   │   ├── usora-core
│   │   │   ├── Chart.yaml
│   │   │   └── values.yaml
│   │   ├── usora-document-processor
│   │   │   ├── Chart.yaml
│   │   │   └── values.yaml
│   │   └── usora-gateway
│   │       ├── Chart.yaml
│   │       └── values.yaml
│   ├── k8s
│   │   ├── base
│   │   │   ├── namespace.yml
│   │   │   └── network-policies.yml
│   │   └── overlays
│   │       ├── dev
│   │       │   └── kustomization.yml
│   │       ├── prod
│   │       │   └── kustomization.yml
│   │       └── staging
│   │           └── kustomization.yml
│   └── terraform
│       ├── environments
│       │   ├── dev
│       │   │   └── main.tf
│       │   ├── prod
│       │   │   └── main.tf
│       │   └── staging
│       │       └── main.tf
│       └── modules
│           ├── eks
│           │   └── main.tf
│           ├── elasticache
│           │   └── main.tf
│           ├── msk
│           │   └── main.tf
│           ├── rds
│           │   └── main.tf
│           └── vpc
│               └── main.tf
├── rust-services
│   ├── usora-api-gateway
│   │   ├── benches
│   │   │   └── throughput.rs
│   │   ├── build.rs
│   │   ├── Cargo.toml
│   │   ├── .dockerignore
│   │   ├── src
│   │   │   ├── auth
│   │   │   │   ├── jwt.rs
│   │   │   │   ├── mod.rs
│   │   │   │   ├── mtls.rs
│   │   │   │   └── oauth.rs
│   │   │   ├── config
│   │   │   │   └── mod.rs
│   │   │   ├── grpc
│   │   │   │   └── mod.rs
│   │   │   ├── handlers
│   │   │   │   ├── identity_handler.rs
│   │   │   │   ├── kyc_handler.rs
│   │   │   │   ├── mod.rs
│   │   │   │   └── tenant_handler.rs
│   │   │   ├── lib.rs
│   │   │   ├── main.rs
│   │   │   ├── middleware
│   │   │   │   ├── auth.rs
│   │   │   │   ├── cors.rs
│   │   │   │   ├── mod.rs
│   │   │   │   ├── rate_limit.rs
│   │   │   │   └── tenant.rs
│   │   │   ├── models
│   │   │   │   └── mod.rs
│   │   │   ├── rate_limit
│   │   │   │   ├── mod.rs
│   │   │   │   ├── sliding_window.rs
│   │   │   │   └── token_bucket.rs
│   │   │   ├── routes
│   │   │   │   ├── api_v1.rs
│   │   │   │   ├── health.rs
│   │   │   │   └── mod.rs
│   │   │   └── utils
│   │   │       └── mod.rs
│   │   └── tests
│   │       └── integration_test.rs
│   ├── usora-document-processor
│   │   ├── benches
│   │   │   └── throughput.rs
│   │   ├── build.rs
│   │   ├── Cargo.toml
│   │   ├── .dockerignore
│   │   ├── src
│   │   │   ├── config
│   │   │   │   └── mod.rs
│   │   │   ├── extraction
│   │   │   │   ├── barcode.rs
│   │   │   │   ├── mod.rs
│   │   │   │   ├── mrz.rs
│   │   │   │   └── nfc.rs
│   │   │   ├── grpc
│   │   │   │   └── mod.rs
│   │   │   ├── lib.rs
│   │   │   ├── main.rs
│   │   │   ├── models
│   │   │   │   └── mod.rs
│   │   │   ├── ocr
│   │   │   │   ├── ml_ocr.rs
│   │   │   │   ├── mod.rs
│   │   │   │   └── tesseract.rs
│   │   │   ├── pipeline
│   │   │   │   ├── ingestion.rs
│   │   │   │   ├── mod.rs
│   │   │   │   ├── postprocessing.rs
│   │   │   │   └── preprocessing.rs
│   │   │   ├── utils
│   │   │   │   └── mod.rs
│   │   │   └── validation
│   │   │       ├── authenticity.rs
│   │   │       ├── mod.rs
│   │   │       └── tamper_detection.rs
│   │   └── tests
│   │       └── integration_test.rs
│   ├── usora-face-matching-engine
│   │   ├── benches
│   │   │   └── throughput.rs
│   │   ├── build.rs
│   │   ├── Cargo.toml
│   │   ├── .dockerignore
│   │   ├── src
│   │   │   ├── config
│   │   │   │   └── mod.rs
│   │   │   ├── detection
│   │   │   │   ├── face_detector.rs
│   │   │   │   ├── mod.rs
│   │   │   │   └── quality_check.rs
│   │   │   ├── embedding
│   │   │   │   ├── inference.rs
│   │   │   │   ├── model.rs
│   │   │   │   └── mod.rs
│   │   │   ├── grpc
│   │   │   │   └── mod.rs
│   │   │   ├── lib.rs
│   │   │   ├── liveness
│   │   │   │   ├── active.rs
│   │   │   │   ├── mod.rs
│   │   │   │   └── passive.rs
│   │   │   ├── main.rs
│   │   │   ├── matching
│   │   │   │   ├── mod.rs
│   │   │   │   ├── one_to_many.rs
│   │   │   │   └── one_to_one.rs
│   │   │   ├── models
│   │   │   │   └── mod.rs
│   │   │   └── utils
│   │   │       └── mod.rs
│   │   └── tests
│   │       └── integration_test.rs
│   └── usora-risk-scoring-engine
│       ├── benches
│       │   └── throughput.rs
│       ├── build.rs
│       ├── Cargo.toml
│       ├── .dockerignore
│       ├── src
│       │   ├── config
│       │   │   └── mod.rs
│       │   ├── engine
│       │   │   ├── cache.rs
│       │   │   ├── mod.rs
│       │   │   └── orchestrator.rs
│       │   ├── grpc
│       │   │   └── mod.rs
│       │   ├── lib.rs
│       │   ├── main.rs
│       │   ├── ml
│       │   │   ├── feature_store.rs
│       │   │   ├── inference.rs
│       │   │   ├── model.rs
│       │   │   └── mod.rs
│       │   ├── models
│       │   │   └── mod.rs
│       │   ├── rules
│       │   │   ├── dsl.rs
│       │   │   ├── evaluator.rs
│       │   │   ├── mod.rs
│       │   │   └── registry.rs
│       │   ├── scoring
│       │   │   ├── calculator.rs
│       │   │   ├── engine.rs
│       │   │   └── mod.rs
│       │   └── utils
│       │       └── mod.rs
│       └── tests
│           └── integration_test.rs
├── scripts
│   ├── db-migrate.sh
│   ├── deploy-staging.sh
│   ├── generate-proto.sh
│   ├── run-tests.sh
│   └── setup-local.sh
├── shared
│   ├── contracts
│   │   └── api-contract.yml
│   ├── events
│   │   ├── compliance-checked.avsc
│   │   ├── identity-verified.avsc
│   │   ├── kyc-submitted.avsc
│   │   └── tenant-provisioned.avsc
│   └── proto
│       ├── audit.proto
│       ├── compliance.proto
│       ├── document.proto
│       ├── identity.proto
│       ├── notification.proto
│       └── tenant.proto
├── spring-boot-services
│   ├── usora-audit-service
│   │   ├── pom.xml
│   │   └── src
│   │       ├── main
│   │       │   ├── java
│   │       │   │   └── com
│   │       │   │       └── usora
│   │       │   │           └── audit
│   │       │   │               ├── aspect
│   │       │   │               │   ├── LoggingAspect.java
│   │       │   │               │   ├── PerformanceAspect.java
│   │       │   │               │   └── TenantAuditAspect.java
│   │       │   │               ├── client
│   │       │   │               │   ├── GrpcClient.java
│   │       │   │               │   └── RestClient.java
│   │       │   │               ├── config
│   │       │   │               │   ├── AsyncConfig.java
│   │       │   │               │   ├── CacheConfig.java
│   │       │   │               │   ├── GrpcConfig.java
│   │       │   │               │   ├── MetricsConfig.java
│   │       │   │               │   ├── SecurityConfig.java
│   │       │   │               │   └── TenantConfig.java
│   │       │   │               ├── controller
│   │       │   │               │   ├── HealthController.java
│   │       │   │               │   └── v1
│   │       │   │               │       └── ApiController.java
│   │       │   │               ├── dto
│   │       │   │               │   ├── RequestDto.java
│   │       │   │               │   └── ResponseDto.java
│   │       │   │               ├── entity
│   │       │   │               │   ├── BaseEntity.java
│   │       │   │               │   └── TenantEntity.java
│   │       │   │               ├── event
│   │       │   │               │   ├── DomainEventListener.java
│   │       │   │               │   └── DomainEventPublisher.java
│   │       │   │               ├── exception
│   │       │   │               │   ├── BusinessException.java
│   │       │   │               │   └── GlobalExceptionHandler.java
│   │       │   │               ├── job
│   │       │   │               │   └── ScheduledCleanupJob.java
│   │       │   │               ├── mapper
│   │       │   │               │   └── EntityMapper.java
│   │       │   │               ├── repository
│   │       │   │               │   └── TenantRepository.java
│   │       │   │               ├── security
│   │       │   │               │   ├── JwtTokenProvider.java
│   │       │   │               │   ├── PermissionEvaluator.java
│   │       │   │               │   ├── TenantContext.java
│   │       │   │               │   └── TenantInterceptor.java
│   │       │   │               ├── service
│   │       │   │               │   ├── DomainService.java
│   │       │   │               │   └── TenantAwareService.java
│   │       │   │               ├── util
│   │       │   │               │   ├── EncryptionUtil.java
│   │       │   │               │   ├── HashingUtil.java
│   │       │   │               │   ├── IdGenerator.java
│   │       │   │               │   └── ValidationUtil.java
│   │       │   │               └── Application.java
│   │       │   └── resources
│   │       │       ├── application-dev.yml
│   │       │       ├── application-prod.yml
│   │       │       ├── application.yml
│   │       │       ├── db
│   │       │       │   └── migration
│   │       │       │       ├── V1__init.sql
│   │       │       │       └── V2__tenant_schema.sql
│   │       │       └── logback-spring.xml
│   │       └── test
│   │           ├── java
│   │           │   └── com
│   │           │       └── usora
│   │           │           └── audit
│   │           │               ├── integration
│   │           │               │   ├── ApiIntegrationTest.java
│   │           │               │   └── GrpcIntegrationTest.java
│   │           │               └── unit
│   │           │                   ├── MapperUnitTest.java
│   │           │                   ├── RepositoryUnitTest.java
│   │           │                   └── ServiceUnitTest.java
│   │           └── resources
│   │               └── application-test.yml
│   ├── usora-compliance-service
│   │   ├── pom.xml
│   │   └── src
│   │       ├── main
│   │       │   ├── java
│   │       │   │   └── com
│   │       │   │       └── usora
│   │       │   │           └── compliance
│   │       │   │               ├── aspect
│   │       │   │               │   ├── LoggingAspect.java
│   │       │   │               │   ├── PerformanceAspect.java
│   │       │   │               │   └── TenantAuditAspect.java
│   │       │   │               ├── client
│   │       │   │               │   ├── GrpcClient.java
│   │       │   │               │   └── RestClient.java
│   │       │   │               ├── config
│   │       │   │               │   ├── AsyncConfig.java
│   │       │   │               │   ├── CacheConfig.java
│   │       │   │               │   ├── GrpcConfig.java
│   │       │   │               │   ├── MetricsConfig.java
│   │       │   │               │   ├── SecurityConfig.java
│   │       │   │               │   └── TenantConfig.java
│   │       │   │               ├── controller
│   │       │   │               │   ├── HealthController.java
│   │       │   │               │   └── v1
│   │       │   │               │       └── ApiController.java
│   │       │   │               ├── dto
│   │       │   │               │   ├── RequestDto.java
│   │       │   │               │   └── ResponseDto.java
│   │       │   │               ├── entity
│   │       │   │               │   ├── BaseEntity.java
│   │       │   │               │   └── TenantEntity.java
│   │       │   │               ├── event
│   │       │   │               │   ├── DomainEventListener.java
│   │       │   │               │   └── DomainEventPublisher.java
│   │       │   │               ├── exception
│   │       │   │               │   ├── BusinessException.java
│   │       │   │               │   └── GlobalExceptionHandler.java
│   │       │   │               ├── job
│   │       │   │               │   └── ScheduledCleanupJob.java
│   │       │   │               ├── mapper
│   │       │   │               │   └── EntityMapper.java
│   │       │   │               ├── repository
│   │       │   │               │   └── TenantRepository.java
│   │       │   │               ├── security
│   │       │   │               │   ├── JwtTokenProvider.java
│   │       │   │               │   ├── PermissionEvaluator.java
│   │       │   │               │   ├── TenantContext.java
│   │       │   │               │   └── TenantInterceptor.java
│   │       │   │               ├── service
│   │       │   │               │   ├── DomainService.java
│   │       │   │               │   └── TenantAwareService.java
│   │       │   │               ├── util
│   │       │   │               │   ├── EncryptionUtil.java
│   │       │   │               │   ├── HashingUtil.java
│   │       │   │               │   ├── IdGenerator.java
│   │       │   │               │   └── ValidationUtil.java
│   │       │   │               └── Application.java
│   │       │   └── resources
│   │       │       ├── application-dev.yml
│   │       │       ├── application-prod.yml
│   │       │       ├── application.yml
│   │       │       ├── db
│   │       │       │   └── migration
│   │       │       │       ├── V1__init.sql
│   │       │       │       └── V2__tenant_schema.sql
│   │       │       └── logback-spring.xml
│   │       └── test
│   │           ├── java
│   │           │   └── com
│   │           │       └── usora
│   │           │           └── compliance
│   │           │               ├── integration
│   │           │               │   ├── ApiIntegrationTest.java
│   │           │               │   └── GrpcIntegrationTest.java
│   │           │               └── unit
│   │           │                   ├── MapperUnitTest.java
│   │           │                   ├── RepositoryUnitTest.java
│   │           │                   └── ServiceUnitTest.java
│   │           └── resources
│   │               └── application-test.yml
│   ├── usora-core-service
│   │   ├── pom.xml
│   │   └── src
│   │       ├── main
│   │       │   ├── java
│   │       │   │   └── com
│   │       │   │       └── usora
│   │       │   │           └── core
│   │       │   │               ├── aspect
│   │       │   │               │   ├── LoggingAspect.java
│   │       │   │               │   ├── PerformanceAspect.java
│   │       │   │               │   └── TenantAuditAspect.java
│   │       │   │               ├── client
│   │       │   │               │   ├── GrpcClient.java
│   │       │   │               │   └── RestClient.java
│   │       │   │               ├── config
│   │       │   │               │   ├── AsyncConfig.java
│   │       │   │               │   ├── CacheConfig.java
│   │       │   │               │   ├── GrpcConfig.java
│   │       │   │               │   ├── MetricsConfig.java
│   │       │   │               │   ├── SecurityConfig.java
│   │       │   │               │   └── TenantConfig.java
│   │       │   │               ├── controller
│   │       │   │               │   ├── HealthController.java
│   │       │   │               │   └── v1
│   │       │   │               │       └── ApiController.java
│   │       │   │               ├── dto
│   │       │   │               │   ├── RequestDto.java
│   │       │   │               │   └── ResponseDto.java
│   │       │   │               ├── entity
│   │       │   │               │   ├── BaseEntity.java
│   │       │   │               │   └── TenantEntity.java
│   │       │   │               ├── event
│   │       │   │               │   ├── DomainEventListener.java
│   │       │   │               │   └── DomainEventPublisher.java
│   │       │   │               ├── exception
│   │       │   │               │   ├── BusinessException.java
│   │       │   │               │   └── GlobalExceptionHandler.java
│   │       │   │               ├── job
│   │       │   │               │   └── ScheduledCleanupJob.java
│   │       │   │               ├── mapper
│   │       │   │               │   └── EntityMapper.java
│   │       │   │               ├── repository
│   │       │   │               │   └── TenantRepository.java
│   │       │   │               ├── security
│   │       │   │               │   ├── JwtTokenProvider.java
│   │       │   │               │   ├── PermissionEvaluator.java
│   │       │   │               │   ├── TenantContext.java
│   │       │   │               │   └── TenantInterceptor.java
│   │       │   │               ├── service
│   │       │   │               │   ├── DomainService.java
│   │       │   │               │   └── TenantAwareService.java
│   │       │   │               ├── util
│   │       │   │               │   ├── EncryptionUtil.java
│   │       │   │               │   ├── HashingUtil.java
│   │       │   │               │   ├── IdGenerator.java
│   │       │   │               │   └── ValidationUtil.java
│   │       │   │               └── Application.java
│   │       │   └── resources
│   │       │       ├── application-dev.yml
│   │       │       ├── application-prod.yml
│   │       │       ├── application.yml
│   │       │       ├── db
│   │       │       │   └── migration
│   │       │       │       ├── V1__init.sql
│   │       │       │       └── V2__tenant_schema.sql
│   │       │       └── logback-spring.xml
│   │       └── test
│   │           ├── java
│   │           │   └── com
│   │           │       └── usora
│   │           │           └── core
│   │           │               ├── integration
│   │           │               │   ├── ApiIntegrationTest.java
│   │           │               │   └── GrpcIntegrationTest.java
│   │           │               └── unit
│   │           │                   ├── MapperUnitTest.java
│   │           │                   ├── RepositoryUnitTest.java
│   │           │                   └── ServiceUnitTest.java
│   │           └── resources
│   │               └── application-test.yml
│   ├── usora-identity-service
│   │   ├── pom.xml
│   │   └── src
│   │       ├── main
│   │       │   ├── java
│   │       │   │   └── com
│   │       │   │       └── usora
│   │       │   │           └── identity
│   │       │   │               ├── aspect
│   │       │   │               │   ├── LoggingAspect.java
│   │       │   │               │   ├── PerformanceAspect.java
│   │       │   │               │   └── TenantAuditAspect.java
│   │       │   │               ├── client
│   │       │   │               │   ├── GrpcClient.java
│   │       │   │               │   └── RestClient.java
│   │       │   │               ├── config
│   │       │   │               │   ├── AsyncConfig.java
│   │       │   │               │   ├── CacheConfig.java
│   │       │   │               │   ├── GrpcConfig.java
│   │       │   │               │   ├── MetricsConfig.java
│   │       │   │               │   ├── SecurityConfig.java
│   │       │   │               │   └── TenantConfig.java
│   │       │   │               ├── controller
│   │       │   │               │   ├── HealthController.java
│   │       │   │               │   └── v1
│   │       │   │               │       └── ApiController.java
│   │       │   │               ├── dto
│   │       │   │               │   ├── RequestDto.java
│   │       │   │               │   └── ResponseDto.java
│   │       │   │               ├── entity
│   │       │   │               │   ├── BaseEntity.java
│   │       │   │               │   └── TenantEntity.java
│   │       │   │               ├── event
│   │       │   │               │   ├── DomainEventListener.java
│   │       │   │               │   └── DomainEventPublisher.java
│   │       │   │               ├── exception
│   │       │   │               │   ├── BusinessException.java
│   │       │   │               │   └── GlobalExceptionHandler.java
│   │       │   │               ├── job
│   │       │   │               │   └── ScheduledCleanupJob.java
│   │       │   │               ├── mapper
│   │       │   │               │   └── EntityMapper.java
│   │       │   │               ├── repository
│   │       │   │               │   └── TenantRepository.java
│   │       │   │               ├── security
│   │       │   │               │   ├── JwtTokenProvider.java
│   │       │   │               │   ├── PermissionEvaluator.java
│   │       │   │               │   ├── TenantContext.java
│   │       │   │               │   └── TenantInterceptor.java
│   │       │   │               ├── service
│   │       │   │               │   ├── DomainService.java
│   │       │   │               │   └── TenantAwareService.java
│   │       │   │               ├── util
│   │       │   │               │   ├── EncryptionUtil.java
│   │       │   │               │   ├── HashingUtil.java
│   │       │   │               │   ├── IdGenerator.java
│   │       │   │               │   └── ValidationUtil.java
│   │       │   │               └── Application.java
│   │       │   └── resources
│   │       │       ├── application-dev.yml
│   │       │       ├── application-prod.yml
│   │       │       ├── application.yml
│   │       │       ├── db
│   │       │       │   └── migration
│   │       │       │       ├── V1__init.sql
│   │       │       │       └── V2__tenant_schema.sql
│   │       │       └── logback-spring.xml
│   │       └── test
│   │           ├── java
│   │           │   └── com
│   │           │       └── usora
│   │           │           └── identity
│   │           │               ├── integration
│   │           │               │   ├── ApiIntegrationTest.java
│   │           │               │   └── GrpcIntegrationTest.java
│   │           │               └── unit
│   │           │                   ├── MapperUnitTest.java
│   │           │                   ├── RepositoryUnitTest.java
│   │           │                   └── ServiceUnitTest.java
│   │           └── resources
│   │               └── application-test.yml
│   ├── usora-integration-service
│   │   ├── pom.xml
│   │   └── src
│   │       ├── main
│   │       │   ├── java
│   │       │   │   └── com
│   │       │   │       └── usora
│   │       │   │           └── integration
│   │       │   │               ├── aspect
│   │       │   │               │   ├── LoggingAspect.java
│   │       │   │               │   ├── PerformanceAspect.java
│   │       │   │               │   └── TenantAuditAspect.java
│   │       │   │               ├── client
│   │       │   │               │   ├── GrpcClient.java
│   │       │   │               │   └── RestClient.java
│   │       │   │               ├── config
│   │       │   │               │   ├── AsyncConfig.java
│   │       │   │               │   ├── CacheConfig.java
│   │       │   │               │   ├── GrpcConfig.java
│   │       │   │               │   ├── MetricsConfig.java
│   │       │   │               │   ├── SecurityConfig.java
│   │       │   │               │   └── TenantConfig.java
│   │       │   │               ├── controller
│   │       │   │               │   ├── HealthController.java
│   │       │   │               │   └── v1
│   │       │   │               │       └── ApiController.java
│   │       │   │               ├── dto
│   │       │   │               │   ├── RequestDto.java
│   │       │   │               │   └── ResponseDto.java
│   │       │   │               ├── entity
│   │       │   │               │   ├── BaseEntity.java
│   │       │   │               │   └── TenantEntity.java
│   │       │   │               ├── event
│   │       │   │               │   ├── DomainEventListener.java
│   │       │   │               │   └── DomainEventPublisher.java
│   │       │   │               ├── exception
│   │       │   │               │   ├── BusinessException.java
│   │       │   │               │   └── GlobalExceptionHandler.java
│   │       │   │               ├── job
│   │       │   │               │   └── ScheduledCleanupJob.java
│   │       │   │               ├── mapper
│   │       │   │               │   └── EntityMapper.java
│   │       │   │               ├── repository
│   │       │   │               │   └── TenantRepository.java
│   │       │   │               ├── security
│   │       │   │               │   ├── JwtTokenProvider.java
│   │       │   │               │   ├── PermissionEvaluator.java
│   │       │   │               │   ├── TenantContext.java
│   │       │   │               │   └── TenantInterceptor.java
│   │       │   │               ├── service
│   │       │   │               │   ├── DomainService.java
│   │       │   │               │   └── TenantAwareService.java
│   │       │   │               ├── util
│   │       │   │               │   ├── EncryptionUtil.java
│   │       │   │               │   ├── HashingUtil.java
│   │       │   │               │   ├── IdGenerator.java
│   │       │   │               │   └── ValidationUtil.java
│   │       │   │               └── Application.java
│   │       │   └── resources
│   │       │       ├── application-dev.yml
│   │       │       ├── application-prod.yml
│   │       │       ├── application.yml
│   │       │       ├── db
│   │       │       │   └── migration
│   │       │       │       ├── V1__init.sql
│   │       │       │       └── V2__tenant_schema.sql
│   │       │       └── logback-spring.xml
│   │       └── test
│   │           ├── java
│   │           │   └── com
│   │           │       └── usora
│   │           │           └── integration
│   │           │               ├── integration
│   │           │               │   ├── ApiIntegrationTest.java
│   │           │               │   └── GrpcIntegrationTest.java
│   │           │               └── unit
│   │           │                   ├── MapperUnitTest.java
│   │           │                   ├── RepositoryUnitTest.java
│   │           │                   └── ServiceUnitTest.java
│   │           └── resources
│   │               └── application-test.yml
│   ├── usora-notification-service
│   │   ├── pom.xml
│   │   └── src
│   │       ├── main
│   │       │   ├── java
│   │       │   │   └── com
│   │       │   │       └── usora
│   │       │   │           └── notification
│   │       │   │               ├── aspect
│   │       │   │               │   ├── LoggingAspect.java
│   │       │   │               │   ├── PerformanceAspect.java
│   │       │   │               │   └── TenantAuditAspect.java
│   │       │   │               ├── client
│   │       │   │               │   ├── GrpcClient.java
│   │       │   │               │   └── RestClient.java
│   │       │   │               ├── config
│   │       │   │               │   ├── AsyncConfig.java
│   │       │   │               │   ├── CacheConfig.java
│   │       │   │               │   ├── GrpcConfig.java
│   │       │   │               │   ├── MetricsConfig.java
│   │       │   │               │   ├── SecurityConfig.java
│   │       │   │               │   └── TenantConfig.java
│   │       │   │               ├── controller
│   │       │   │               │   ├── HealthController.java
│   │       │   │               │   └── v1
│   │       │   │               │       └── ApiController.java
│   │       │   │               ├── dto
│   │       │   │               │   ├── RequestDto.java
│   │       │   │               │   └── ResponseDto.java
│   │       │   │               ├── entity
│   │       │   │               │   ├── BaseEntity.java
│   │       │   │               │   └── TenantEntity.java
│   │       │   │               ├── event
│   │       │   │               │   ├── DomainEventListener.java
│   │       │   │               │   └── DomainEventPublisher.java
│   │       │   │               ├── exception
│   │       │   │               │   ├── BusinessException.java
│   │       │   │               │   └── GlobalExceptionHandler.java
│   │       │   │               ├── job
│   │       │   │               │   └── ScheduledCleanupJob.java
│   │       │   │               ├── mapper
│   │       │   │               │   └── EntityMapper.java
│   │       │   │               ├── repository
│   │       │   │               │   └── TenantRepository.java
│   │       │   │               ├── security
│   │       │   │               │   ├── JwtTokenProvider.java
│   │       │   │               │   ├── PermissionEvaluator.java
│   │       │   │               │   ├── TenantContext.java
│   │       │   │               │   └── TenantInterceptor.java
│   │       │   │               ├── service
│   │       │   │               │   ├── DomainService.java
│   │       │   │               │   └── TenantAwareService.java
│   │       │   │               ├── util
│   │       │   │               │   ├── EncryptionUtil.java
│   │       │   │               │   ├── HashingUtil.java
│   │       │   │               │   ├── IdGenerator.java
│   │       │   │               │   └── ValidationUtil.java
│   │       │   │               └── Application.java
│   │       │   └── resources
│   │       │       ├── application-dev.yml
│   │       │       ├── application-prod.yml
│   │       │       ├── application.yml
│   │       │       ├── db
│   │       │       │   └── migration
│   │       │       │       ├── V1__init.sql
│   │       │       │       └── V2__tenant_schema.sql
│   │       │       └── logback-spring.xml
│   │       └── test
│   │           ├── java
│   │           │   └── com
│   │           │       └── usora
│   │           │           └── notification
│   │           │               ├── integration
│   │           │               │   ├── ApiIntegrationTest.java
│   │           │               │   └── GrpcIntegrationTest.java
│   │           │               └── unit
│   │           │                   ├── MapperUnitTest.java
│   │           │                   ├── RepositoryUnitTest.java
│   │           │                   └── ServiceUnitTest.java
│   │           └── resources
│   │               └── application-test.yml
│   └── usora-tenant-service
│       ├── pom.xml
│       └── src
│           ├── main
│           │   ├── java
│           │   │   └── com
│           │   │       └── usora
│           │   │           └── tenant
│           │   │               ├── aspect
│           │   │               │   ├── LoggingAspect.java
│           │   │               │   ├── PerformanceAspect.java
│           │   │               │   └── TenantAuditAspect.java
│           │   │               ├── client
│           │   │               │   ├── GrpcClient.java
│           │   │               │   └── RestClient.java
│           │   │               ├── config
│           │   │               │   ├── AsyncConfig.java
│           │   │               │   ├── CacheConfig.java
│           │   │               │   ├── GrpcConfig.java
│           │   │               │   ├── MetricsConfig.java
│           │   │               │   ├── SecurityConfig.java
│           │   │               │   └── TenantConfig.java
│           │   │               ├── controller
│           │   │               │   ├── HealthController.java
│           │   │               │   └── v1
│           │   │               │       └── ApiController.java
│           │   │               ├── dto
│           │   │               │   ├── RequestDto.java
│           │   │               │   └── ResponseDto.java
│           │   │               ├── entity
│           │   │               │   ├── BaseEntity.java
│           │   │               │   └── TenantEntity.java
│           │   │               ├── event
│           │   │               │   ├── DomainEventListener.java
│           │   │               │   └── DomainEventPublisher.java
│           │   │               ├── exception
│           │   │               │   ├── BusinessException.java
│           │   │               │   └── GlobalExceptionHandler.java
│           │   │               ├── job
│           │   │               ├── mapper
│           │   │               │   └── EntityMapper.java
│           │   │               ├── repository
│           │   │               │   └── TenantRepository.java
│           │   │               ├── security
│           │   │               │   ├── JwtTokenProvider.java
│           │   │               │   ├── PermissionEvaluator.java
│           │   │               │   ├── TenantContext.java
│           │   │               │   └── TenantInterceptor.java
│           │   │               ├── service
│           │   │               │   ├── DomainService.java
│           │   │               │   └── TenantAwareService.java
│           │   │               ├── util
│           │   │               │   ├── EncryptionUtil.java
│           │   │               │   ├── HashingUtil.java
│           │   │               │   ├── IdGenerator.java
│           │   │               │   └── ValidationUtil.java
│           │   │               └── Application.java
│           │   └── resources
│           │       ├── application-dev.yml
│           │       ├── application-prod.yml
│           │       ├── application.yml
│           │       ├── db
│           │       │   └── migration
│           │       │       ├── V1__init.sql
│           │       │       └── V2__tenant_schema.sql
│           │       └── logback-spring.xml
│           └── test
│               ├── java
│               │   └── com
│               │       └── usora
│               │           └── tenant
│               │               ├── integration
│               │               └── unit
│               └── resources
├── .gitignore
├── Makefile
├── docker-compose.yml
├── pom.xml
└── README.md