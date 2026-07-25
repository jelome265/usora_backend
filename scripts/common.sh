#!/usr/bin/env bash
#
# USORA Common Functions
# Shared utilities for all USORA scripts
#

set -euo pipefail

# --- Color Output ---
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'

log_info()    { echo -e "${BLUE}[INFO]${NC}  $*"; }
log_success() { echo -e "${GREEN}[OK]${NC}   $*"; }
log_warn()    { echo -e "${YELLOW}[WARN]${NC} $*"; }
log_error()   { echo -e "${RED}[ERROR]${NC} $*"; }
log_step()    { echo -e "\n${CYAN}═══ $* ═══${NC}"; }

# --- Prerequisite Checking ---
check_cmd() {
    if ! command -v "$1" &>/dev/null; then
        log_error "$1 is required but not installed."
        return 1
    fi
}

check_rust() {
    local required_version="${1:-1.82}"
    if ! command -v rustc &>/dev/null; then
        log_error "Rust is not installed. Install from https://rustup.rs"
        return 1
    fi
    local version
    version=$(rustc --version | grep -oP '\d+\.\d+' | head -1)
    if ! printf '%s\n' "$required_version" "$version" | sort -V -C; then
        log_error "Rust $required_version+ required, found $version"
        return 1
    fi
    log_success "Rust $version found"
}

check_java() {
    local required_version="${1:-21}"
    if ! command -v java &>/dev/null; then
        log_error "Java is not installed. Install JDK $required_version+ from https://adoptium.net"
        return 1
    fi
    local version
    version=$(java -version 2>&1 | grep -oP 'version "\K[^"']+' | cut -d. -f1)
    if [ "$version" -lt "$required_version" ] 2>/dev/null; then
        log_error "Java $required_version+ required, found $version"
        return 1
    fi
    log_success "Java $version found"
}

check_docker() {
    if ! command -v docker &>/dev/null; then
        log_error "Docker is not installed. Install from https://docs.docker.com/get-docker/"
        return 1
    fi
    if ! docker info &>/dev/null; then
        log_warn "Docker daemon is not running"
        return 1
    fi
    log_success "Docker found"
}

check_maven() {
    if ! command -v mvn &>/dev/null; then
        log_error "Maven is not installed. Install from https://maven.apache.org/install.html"
        return 1
    fi
    local version
    version=$(mvn --version 2>&1 | grep -oP 'Apache Maven \K[0-9.]+' | head -1)
    log_success "Maven $version found"
}

# --- Service Health Check ---
wait_for_service() {
    local host="$1"
    local port="$2"
    local service_name="${3:-$host}"
    local timeout="${4:-60}"
    local interval="${5:-2}"

    log_info "Waiting for $service_name ($host:$port) ..."
    local elapsed=0
    while [ $elapsed -lt $timeout ]; do
        if timeout 2 bash -c "echo > /dev/tcp/$host/$port" 2>/dev/null; then
            log_success "$service_name is ready"
            return 0
        fi
        sleep "$interval"
        elapsed=$((elapsed + interval))
    done
    log_error "$service_name did not become ready within ${timeout}s"
    return 1
}

wait_for_http() {
    local url="$1"
    local service_name="${2:-$url}"
    local timeout="${3:-60}"
    local interval="${4:-3}"

    log_info "Waiting for HTTP $service_name ..."
    local elapsed=0
    while [ $elapsed -lt $timeout ]; do
        if curl -sf -o /dev/null "$url" 2>/dev/null; then
            log_success "$service_name is ready"
            return 0
        fi
        sleep "$interval"
        elapsed=$((elapsed + interval))
    done
    log_error "$service_name did not become ready within ${timeout}s"
    return 1
}

# --- Docker Compose Utilities ---
docker_compose_running() {
    docker compose ps --services --filter "status=running" 2>/dev/null | grep -q . || return 1
}

docker_compose_start() {
    local compose_file="${1:-docker-compose.yml}"
    log_step "Starting infrastructure services"
    docker compose -f "$compose_file" up -d --wait --wait-timeout 120
    log_success "Infrastructure services started"
}

docker_compose_stop() {
    local compose_file="${1:-docker-compose.yml}"
    log_step "Stopping infrastructure services"
    docker compose -f "$compose_file" down
    log_success "Infrastructure services stopped"
}

# --- Timestamp ---
timestamp() {
    date -u '+%Y-%m-%dT%H:%M:%SZ'
}

elapsed_time() {
    local start="$1"
    local end
    end=$(date +%s)
    echo $((end - start))
}

# --- Error Handling ---
handle_error() {
    local line="$1"
    local command="$2"
    log_error "Error on line $line: $command"
    exit 1
}

if [ -z "${BASH_SOURCE:-}" ]; then
    trap 'handle_error $LINENO "$BASH_COMMAND"' ERR
else
    trap 'handle_error $LINENO "$BASH_COMMAND"' ERR
fi
