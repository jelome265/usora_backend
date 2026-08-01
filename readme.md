# USORA KYC Platform

Enterprise-grade, multi-tenant Know Your Customer (KYC) platform for identity verification.

## Architecture

- **API Gateway**: Rust + Axum + Tokio
- **Orchestration**: Java 21 + Spring Boot 4.1 + Camunda
- **Compute**: Rust + Tokio (Document, Biometric, Risk Scoring)
- **Data**: PostgreSQL 16, Redis 7, Kafka 3.x, MinIO/S3

## Services

### Rust Services
- `usora-api-gateway` - API gateway with auth, rate limiting, routing
- `usora-document-processor` - OCR, MRZ, barcode, NFC extraction
- `usora-face-matching-engine` - Face detection, matching, liveness
- `usora-risk-scoring-engine` - ML-based risk scoring, rules engine

### Java Services
- `usora-tenant-service` - Multi-tenant management
- `usora-core-service` - Core business orchestration
- `usora-identity-service` - Identity verification workflows
- `usora-audit-service` - Immutable audit logging
- `usora-compliance-service` - AML/KYC compliance screening
- `usora-integration-service` - Third-party integrations
- `usora-notification-service` - Multi-channel notifications

## Prerequisites

- Rust 1.82+
- JDK 21 LTS
- Docker & Docker Compose
- Maven 3.9+

## Quick Start

1. Start infrastructure: `make run-dev`
2. Build all services: `make build`
3. Run tests: `make test`



### with love, made by USORA.

building the Malawi. 
