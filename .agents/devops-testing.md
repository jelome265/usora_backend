# Agent: DevOps Testing

## Metadata
- **Agent ID**: `usora-agent-devops-testing`
- **Tier**: 7 — DevOps & Lifecycle
- **Owner**: QA Engineering
- **Status**: Draft
- **Created**: 2026-07-22

## Purpose & Scope
The DevOps Testing agent defines and executes the comprehensive testing strategy for USORA including unit tests, integration tests, contract tests, E2E tests, chaos engineering, and load testing. It ensures quality at every layer of the platform with automated test execution in CI/CD.

## Tech Stack
| Component | Technology | Version |
|-----------|-----------|---------|
| Unit (Java) | JUnit 5 + Mockito | 5.11+ |
| Unit (Rust) | cargo test + mockall | latest |
| Integration | TestContainers + Spring Boot Test | 1.20+ |
| Contract | Pact | 4.6+ |
| E2E | Playwright | 1.47+ |
| Chaos | Litmus / Chaos Mesh | 3.0+ |
| Load | k6 / Locust | 0.53+ / 2.29+ |
| Coverage | JaCoCo + cargo-tarpaulin | latest |
| Mutation | Pitest / cargo-mutants | latest |

## API Surface

### gRPC Services
```protobuf
service TestingService {
  rpc RunUnitTests(UnitTestRequest) returns (UnitTestResponse);
  rpc RunIntegrationTests(IntegrationTestRequest) returns (IntegrationTestResponse);
  rpc RunContractTests(ContractTestRequest) returns (ContractTestResponse);
  rpc RunE2ETests(E2ETestRequest) returns (E2ETestResponse);
  rpc RunChaosTests(ChaosTestRequest) returns (ChaosTestResponse);
  rpc RunLoadTests(LoadTestRequest) returns (LoadTestResponse);
  rpc GetTestCoverage(CoverageRequest) returns (CoverageResponse);
  rpc GetTestReport(TestReportRequest) returns (TestReportResponse);
}
```

### REST Endpoints
| Method | Path | Purpose |
|--------|------|---------|
| POST | `/api/v1/testing/unit` | Run unit tests |
| POST | `/api/v1/testing/integration` | Run integration tests |
| POST | `/api/v1/testing/contract` | Run contract tests |
| POST | `/api/v1/testing/e2e` | Run E2E tests |
| POST | `/api/v1/testing/chaos` | Run chaos tests |
| POST | `/api/v1/testing/load` | Run load tests |
| GET | `/api/v1/testing/coverage` | Get test coverage |
| GET | `/api/v1/testing/report/{testRunId}` | Get test report |

## Tenant Isolation Strategy
- **Test data isolation**: Per-tenant test databases and schemas
- **Contract isolation**: Per-tenant Pact contracts
- **E2E isolation**: Per-tenant test environments
- **Load test isolation**: Per-tenant load test quotas

## Security Boundaries
- No production data in test environments
- Test secrets rotated daily
- Chaos tests limited to non-production environments
- Load tests rate-limited to prevent DoS
- Contract tests validate API backward compatibility

## Observability Hooks
| Signal | Implementation |
|--------|---------------|
| Logs | Test execution logs → Loki |
| Metrics | `testing_unit_tests_total`, `testing_integration_tests_total`, `testing_coverage_percentage`, `testing_load_test_rps` |
| Traces | OpenTelemetry spans per test case |
| Alerts | Coverage drop > 5%, test failure rate > 2%, contract violation |

## Failure Modes & Recovery
| Failure | Detection | Recovery |
|---------|-----------|----------|
| Test flakiness | Repeated failure pattern | Quarantine test, alert, manual investigation |
| Contract violation | Pact verification failure | Block deployment, alert API owner |
| Chaos test failure | System unavailability | Auto-rollback, alert, incident response |
| Load test overload | Resource exhaustion | Abort test, scale infrastructure, alert |

## Configuration
```yaml
testing:
  unit:
    coverage_threshold: 80
    mutation_threshold: 70
    parallel: true
  integration:
    testcontainers:
      enabled: true
      reuse: true
      timeout: 300
  contract:
    pact_broker:
      url: "https://pact.usora.io"
      publish_verification_results: true
    consumer:
      - "frontend-portal"
      - "frontend-applicant"
      - "platform-gateway"
    provider:
      - "orchestrator-core"
      - "compute-identity-verification"
      - "compute-risk-scoring"
  e2e:
    playwright:
      browsers: ["chromium", "firefox", "webkit"]
      parallel: 4
      retries: 2
      timeout: 60000
  chaos:
    litmus:
      experiments:
        - "pod-delete"
        - "pod-cpu-hog"
        - "pod-memory-hog"
        - "network-latency"
        - "disk-fill"
      target_namespaces: ["usora-staging"]
  load:
    k6:
      scenarios:
        - name: "kyc-submission"
          vus: 100
          duration: "5m"
          target_rps: 50
        - name: "case-management"
          vus: 50
          duration: "5m"
          target_rps: 20
      thresholds:
        http_req_duration: ["p(95)<500"]
        http_req_failed: ["rate<0.01"]
```

## Dependencies
- `devops-cicd` — Test execution in pipeline
- `platform-infra` — Test environment provisioning
- `compute-ml-inference` — Model testing
- `platform-observability` — Test metrics
