.PHONY: help build test clean proto rust-services java-services docker-setup verify fmt-check clippy helm-lint helm-template

RUST_SERVICES := usora-api-gateway usora-document-processor usora-face-matching-engine usora-risk-scoring-engine

help: ## Display this help
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-20s\033[0m %s\n", $$1, $$2}'

# F-013: this Makefile previously implemented its own build logic
# independent of what CI (.github/workflows/ci-cd.yml) actually runs --
# `java-services`/`test`/`clean` ran `cd spring-boot-services && mvn ...`,
# but spring-boot-services/ has no pom.xml of its own; the real reactor
# root is this repo's own top-level pom.xml, which is what CI's
# `mvn verify -B -f pom.xml` (line ~316 of ci-cd.yml) actually targets.
# `cd spring-boot-services && mvn package` was not merely inconsistent
# with CI, it would fail outright ("no POM in this directory") if run.
# Every target below now wraps the exact commands CI runs, rather than
# an independently-maintained approximation of them, per remediation
# item 2 ("Make Makefile targets wrappers around the canonical commands,
# not independent build logic").

build: proto rust-services java-services ## Build all services (mirrors CI's build jobs)

test: ## Run all tests (same commands as CI's test-rust job matrix + Java verify)
	@for dir in $(RUST_SERVICES); do \
		echo "==> cargo test: rust-services/$$dir"; \
		(cd rust-services/$$dir && cargo test --verbose) || exit 1; \
	done
	mvn test -f pom.xml

clean: ## Clean all build artifacts
	@for dir in $(RUST_SERVICES); do \
		(cd rust-services/$$dir && cargo clean); \
	done
	mvn clean -f pom.xml

# F-013: this target previously only echoed each shared/proto/*.proto
# filename and generated nothing -- "creating the appearance of proto
# generation without producing artifacts" (the audit's own words).
#
# The honest fix is not to invent a new, separate codegen step: both
# ecosystems already generate their own protobuf code as a normal part
# of their own build tooling --
#   - Rust: each service's build.rs runs tonic-build/prost-build during
#     `cargo build`/`cargo test` itself. Nothing extra is needed as long
#     as `protoc` is installed (see ci-cd.yml's "Install protoc" step);
#     there is no separate artifact to check in or drift out of sync.
#   - Java: usora-compliance-service and usora-notification-service (the
#     two services with an actual gRPC server -- see
#     docs/F-012-grpc-service-matrix.md) already bind
#     protoc-jar-maven-plugin to Maven's own generate-sources phase, so
#     `mvn generate-sources` (or any later phase) already regenerates
#     their stubs from that module's own src/main/proto(buf)/*.proto.
#
# So `make proto` now actually triggers both of those real mechanisms
# (rather than replacing them with a third, redundant one), and reports
# generated-file counts so a genuinely broken codegen step is visible
# instead of silently doing nothing. It does NOT try to unify each
# module's local proto copy with shared/proto/ -- that divergence is a
# separate, documented, unsolved gap (see docs/F-012-grpc-service-matrix.md,
# "Proto source divergence"), not something a Makefile target alone can
# safely paper over without knowing which copy is authoritative.
proto: ## Generate protobuf code (real generation, not a placeholder)
	@echo "Rust: triggering build.rs codegen via cargo build (requires protoc on PATH)..."
	@for dir in $(RUST_SERVICES); do \
		echo "==> rust-services/$$dir"; \
		(cd rust-services/$$dir && cargo build --release 2>&1 | tail -5) || exit 1; \
	done
	@echo "Java: triggering protoc-jar-maven-plugin via generate-sources..."
	@mvn generate-sources -f pom.xml -pl spring-boot-services/usora-compliance-service,spring-boot-services/usora-notification-service -am -q
	@echo "Generated Java sources:"
	@find spring-boot-services/*/target/generated-sources/protobuf -name '*.java' 2>/dev/null | wc -l

rust-services: ## Build all Rust services (release)
	@for dir in $(RUST_SERVICES); do \
		echo "==> cargo build --release: rust-services/$$dir"; \
		(cd rust-services/$$dir && cargo build --release) || exit 1; \
	done

java-services: ## Build all Java services via the root reactor pom (same as CI)
	mvn package -DskipTests -f pom.xml

fmt-check: ## Check Rust formatting (same as CI's "Check Rust formatting" step)
	@for dir in $(RUST_SERVICES); do \
		echo "==> cargo fmt --check: rust-services/$$dir"; \
		(cd rust-services/$$dir && cargo fmt --check) || exit 1; \
	done

clippy: ## Run clippy on all Rust services (same as CI's test-rust job)
	@for dir in $(RUST_SERVICES); do \
		echo "==> cargo clippy: rust-services/$$dir"; \
		(cd rust-services/$$dir && cargo clippy -- -D warnings) || exit 1; \
	done

helm-lint: ## Lint every Helm chart (same as CI's "Helm Lint" step)
	@status=0; \
	for chart_dir in infrastructure/helm/*/; do \
		chart_name=$$(basename "$$chart_dir"); \
		echo "==> helm lint $$chart_name"; \
		helm lint --strict "$$chart_dir" || status=1; \
	done; \
	exit $$status

helm-template: ## Render every Helm chart with placeholder secrets (same as CI's template/validate step)
	@status=0; \
	for chart_dir in infrastructure/helm/*/; do \
		chart_name=$$(basename "$$chart_dir"); \
		echo "==> helm template $$chart_name"; \
		helm template "$$chart_name" "$$chart_dir" \
			--namespace usora \
			--set redis.existingSecret=placeholder \
			--set postgresql.existingSecret=placeholder \
			--set postgresql.runtimeRole.existingSecret=placeholder \
			--set postgresql.migrationRole.existingSecret=placeholder \
			--set security.existingSecret=placeholder \
			--set secrets.existingSecret=placeholder \
			--set smtp.existingSecret=placeholder \
			> "/tmp/$${chart_name}-rendered.yaml" || status=1; \
	done; \
	exit $$status

# F-013 remediation item 4: one target that runs the same checks CI runs
# (proto generation, Java verify, Rust fmt/clippy/test, Helm lint +
# template validation) so "does this pass make verify" and "does this
# pass CI" are the same question. This intentionally mirrors CI's job
# list rather than replacing it -- CI's parallel job matrix is faster for
# CI's purposes; this is the single-command equivalent for a local clean
# checkout (the acceptance criterion: "a clean checkout succeeds with one
# documented command"). Docker image builds are deliberately NOT included
# here (unlike the remediation plan's literal wording) -- a full multi-
# service `docker compose build` is slow enough locally that making it
# part of the default verification loop would get this target skipped in
# practice; `make docker-setup` remains separate and explicit.
verify: proto fmt-check clippy ## Full local verification: proto, Rust fmt/clippy/test, Java verify, Helm lint+template
	@for dir in $(RUST_SERVICES); do \
		echo "==> cargo test: rust-services/$$dir"; \
		(cd rust-services/$$dir && cargo test --verbose) || exit 1; \
	done
	mvn verify -f pom.xml
	$(MAKE) helm-lint
	$(MAKE) helm-template

docker-setup: ## Build Docker images
	docker compose build

run-dev: ## Run development environment
	docker compose up -d
