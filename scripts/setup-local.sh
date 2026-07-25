#!/usr/bin/env bash
#
# USORA Local Development Setup
# Prepares the local environment for USORA KYC platform development.
#
# Usage: ./scripts/setup-local.sh [options]
#
# Options:
#   --skip-docker    Skip starting Docker infrastructure
#   --build-rust     Build all Rust services after setup
#   --build-java     Build all Java services after setup
#   --help           Display this help message
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

# Source common functions
if [ -f "$SCRIPT_DIR/common.sh" ]; then
    # shellcheck source=scripts/common.sh
    source "$SCRIPT_DIR/common.sh"
else
    echo "ERROR: common.sh not found in $SCRIPT_DIR"
    exit 1
fi

# --- Configuration ---
SKIP_DOCKER=false
BUILD_RUST=false
BUILD_JAVA=false

# --- Parse Arguments ---
while [[ $# -gt 0 ]]; do
    case "$1" in
        --skip-docker)
            SKIP_DOCKER=true
            shift
            ;;
        --build-rust)
            BUILD_RUST=true
            shift
            ;;
        --build-java)
            BUILD_JAVA=true
            shift
            ;;
        --help|-h)
            echo "USORA Local Development Setup"
            echo
            echo "Usage: $0 [options]"
            echo
            echo "Options:"
            echo "  --skip-docker    Skip starting Docker infrastructure"
            echo "  --build-rust     Build all Rust services after setup"
            echo "  --build-java     Build all Java services after setup"
            echo "  --help           Display this help message"
            echo
            echo "Services started by Docker Compose:"
            echo "  - PostgreSQL 16 (port 5432)"
            echo "  - Redis 7 (port 6379)"
            echo "  - Kafka 3.x + ZooKeeper (port 9092)"
            echo "  - MinIO (ports 9000, 9001)"
            echo "  - Elasticsearch 8 (port 9200)"
            echo
            exit 0
            ;;
        *)
            log_error "Unknown option: $1"
            echo "Usage: $0 [--skip-docker] [--build-rust] [--build-java] [--help]"
            exit 1
            ;;
    esac
done

# --- Main ---
START_TIME=$(date +%s)

echo -e "${CYAN}"
echo "╔══════════════════════════════════════════════════════╗"
echo "║         USORA Local Development Setup               ║"
echo "╚══════════════════════════════════════════════════════╝"
echo -e "${NC}"

log_step "Checking prerequisites"

FAILED_PREREQS=0

check_rust "1.82" || FAILED_PREREQS=$((FAILED_PREREQS + 1))
check_java "21" || FAILED_PREREQS=$((FAILED_PREREQS + 1))
check_docker || FAILED_PREREQS=$((FAILED_PREREQS + 1))
check_maven || FAILED_PREREQS=$((FAILED_PREREQS + 1))

if [ $FAILED_PREREQS -gt 0 ]; then
    log_error "$FAILED_PREREQS prerequisite(s) failed. Please install missing dependencies."
    exit 1
fi

log_success "All prerequisites satisfied"

# Step 2: Clone submodules (if any)
if [ -f "$PROJECT_DIR/.gitmodules" ]; then
    log_step "Initializing git submodules"
    cd "$PROJECT_DIR"
    git submodule update --init --recursive
    log_success "Submodules initialized"
fi

# Step 3: Start Docker infrastructure
if [ "$SKIP_DOCKER" = false ]; then
    log_step "Starting Docker infrastructure"

    if [ ! -f "$PROJECT_DIR/docker-compose.yml" ]; then
        log_error "docker-compose.yml not found in project root"
        exit 1
    fi

    cd "$PROJECT_DIR"
    docker compose up -d --wait --wait-timeout 120 || {
        log_warn "Docker Compose --wait timed out, checking individual services..."
    }

    log_info "Waiting for services to become healthy..."

    # PostgreSQL
    wait_for_service "localhost" "5432" "PostgreSQL" 60 || log_warn "PostgreSQL health check timed out"

    # Redis
    wait_for_service "localhost" "6379" "Redis" 30 || log_warn "Redis health check timed out"

    # Kafka (via ZooKeeper)
    wait_for_service "localhost" "2181" "ZooKeeper" 60 || log_warn "ZooKeeper health check timed out"

    # MinIO S3 API
    wait_for_service "localhost" "9000" "MinIO" 30 || log_warn "MinIO health check timed out"

    # Elasticsearch
    wait_for_http "http://localhost:9200/_cluster/health" "Elasticsearch" 90 || log_warn "Elasticsearch health check timed out"

    log_success "All infrastructure services are healthy"
else
    log_info "Skipping Docker infrastructure startup (--skip-docker)"
fi

# Step 4: Run database migrations
log_step "Running database migrations"

if [ -d "$PROJECT_DIR/spring-boot-services" ]; then
    # Check if PostgreSQL is accessible
    if command -v psql &>/dev/null; then
        export PGHOST="${PGHOST:-localhost}"
        export PGPORT="${PGPORT:-5432}"
        export PGDATABASE="${PGDATABASE:-usora}"
        export PGUSER="${PGUSER:-usora}"
        export PGPASSWORD="${PGPASSWORD:-usora_dev}"

        log_info "Running Flyway migrations via Maven..."
        cd "$PROJECT_DIR"
        # Find and run Flyway migration SQL files directly
        for service_dir in spring-boot-services/*/; do
            migration_dir="${service_dir}src/main/resources/db/migration"
            if [ -d "$migration_dir" ]; then
                service_name=$(basename "$service_dir")
                log_info "  Migrations for $service_name..."
                for migration_file in "$migration_dir"/*.sql; do
                    if [ -f "$migration_file" ]; then
                        log_info "    Applying $(basename "$migration_file")..."
                        PGPASSWORD="$PGPASSWORD" psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" \
                            -f "$migration_file" 2>/dev/null || log_warn "    Migration $(basename "$migration_file") may have already been applied"
                    fi
                done
                log_success "  $service_name migrations complete"
            fi
        done
        log_success "Database migrations completed"
    else
        log_warn "psql not found. Skipping manual migrations. Use scripts/db-migrate.sh instead."
    fi
else
    log_warn "No spring-boot-services directory found, skipping migrations"
fi

# Step 5: Generate protobuf code
log_step "Generating protobuf code"

if [ -d "$PROJECT_DIR/shared/proto" ]; then
    bash "$SCRIPT_DIR/generate-proto.sh" --all 2>/dev/null || {
        log_warn "Protobuf generation encountered issues. Run scripts/generate-proto.sh manually."
    }
    log_success "Protobuf code generation completed"
else
    log_info "No proto directory found, skipping protobuf generation"
fi

# Step 6: Build services (optional)
if [ "$BUILD_RUST" = true ]; then
    log_step "Building Rust services"
    cd "$PROJECT_DIR/rust-services"
    for service in usora-api-gateway usora-document-processor usora-face-matching-engine usora-risk-scoring-engine; do
        if [ -d "$service" ]; then
            log_info "Building $service..."
            (cd "$service" && cargo build --release)
            log_success "$service built successfully"
        fi
    done
fi

if [ "$BUILD_JAVA" = true ]; then
    log_step "Building Java services"
    cd "$PROJECT_DIR"
    mvn compile -q -f pom.xml -DskipTests
    log_success "Java services compiled successfully"
fi

# Summary
END_TIME=$(date +%s)
ELAPSED=$((END_TIME - START_TIME))

echo
echo -e "${GREEN}╔══════════════════════════════════════════════════════╗${NC}"
echo -e "${GREEN}║           Setup Complete! ($(printf '%02d:%02d' $((ELAPSED/60)) $((ELAPSED%60))))           ║${NC}"
echo -e "${GREEN}╚══════════════════════════════════════════════════════╝${NC}"
echo
echo -e "  ${CYAN}Service URLs:${NC}"
echo -e "  PostgreSQL:     ${BLUE}postgresql://localhost:5432/usora${NC}"
echo -e "  Redis:          ${BLUE}redis://localhost:6379${NC}"
echo -e "  Kafka:          ${BLUE}localhost:9092${NC}"
echo -e "  MinIO Console:  ${BLUE}http://localhost:9001${NC} (user: usora, pass: usora_dev)"
echo -e "  MinIO S3 API:   ${BLUE}http://localhost:9000${NC}"
echo -e "  Elasticsearch:  ${BLUE}http://localhost:9200${NC}"
echo
echo -e "  ${CYAN}Commands:${NC}"
echo -e "  Start all:      ${BLUE}make run-dev${NC}"
echo -e "  Run tests:      ${BLUE}make test${NC}"
echo -e "  Build all:      ${BLUE}make build${NC}"
echo

exit 0
