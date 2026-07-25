.PHONY: help build test clean proto rust-services java-services docker-setup

help: ## Display this help
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-20s\033[0m %s\n", $$1, $$2}'

build: proto rust-services java-services ## Build all services

test: ## Run all tests
	cd rust-services/usora-api-gateway && cargo test
	cd rust-services/usora-document-processor && cargo test
	cd rust-services/usora-face-matching-engine && cargo test
	cd rust-services/usora-risk-scoring-engine && cargo test
	cd spring-boot-services && mvn test

clean: ## Clean all build artifacts
	cd rust-services/usora-api-gateway && cargo clean
	cd rust-services/usora-document-processor && cargo clean
	cd rust-services/usora-face-matching-engine && cargo clean
	cd rust-services/usora-risk-scoring-engine && cargo clean
	cd spring-boot-services && mvn clean

proto: ## Generate protobuf code
	@echo "Generating protobuf code..."
	@for proto in shared/proto/*.proto; do \
		echo "  Generating $$proto..."; \
	done

rust-services: ## Build all Rust services
	cd rust-services/usora-api-gateway && cargo build --release
	cd rust-services/usora-document-processor && cargo build --release
	cd rust-services/usora-face-matching-engine && cargo build --release
	cd rust-services/usora-risk-scoring-engine && cargo build --release

java-services: ## Build all Java services
	cd spring-boot-services && mvn package -DskipTests

docker-setup: ## Build Docker images
	docker compose build

run-dev: ## Run development environment
	docker compose up -d
