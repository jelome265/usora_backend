#!/usr/bin/env bash
#
# USORA Protobuf Code Generator
# Generates gRPC/protobuf stubs for Rust, Java, and Python services.
#
# Usage: ./scripts/generate-proto.sh [options]
#
# Options:
#   --rust           Generate Rust protobuf code only
#   --java           Generate Java gRPC stubs only
#   --python         Generate Python stubs only
#   --all            Generate code for all languages (default)
#   --clean          Clean all generated code before regenerating
#   --help           Display this help message
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

# Source common functions
if [ -f "$SCRIPT_DIR/common.sh" ]; then
    source "$SCRIPT_DIR/common.sh"
else
    echo "ERROR: common.sh not found"
    exit 1
fi

# --- Configuration ---
PROTO_DIR="$PROJECT_DIR/shared/proto"
RUST_SERVICES_DIR="$PROJECT_DIR/rust-services"
JAVA_SERVICES_DIR="$PROJECT_DIR/spring-boot-services"
OUT_DIR_RUST="$PROJECT_DIR/shared/gen/rust"
OUT_DIR_JAVA="$PROJECT_DIR/shared/gen/java"
OUT_DIR_PYTHON="$PROJECT_DIR/shared/gen/python"

GENERATE_RUST=false
GENERATE_JAVA=false
GENERATE_PYTHON=false
CLEAN=false
PROTOC_VERSION="28.3"
GRPC_JAVA_VERSION="1.68.1"

# --- Parse Arguments ---
if [ $# -eq 0 ]; then
    GENERATE_RUST=true
    GENERATE_JAVA=true
    GENERATE_PYTHON=true
fi

while [[ $# -gt 0 ]]; do
    case "$1" in
        --rust)
            GENERATE_RUST=true
            shift
            ;;
        --java)
            GENERATE_JAVA=true
            shift
            ;;
        --python)
            GENERATE_PYTHON=true
            shift
            ;;
        --all)
            GENERATE_RUST=true
            GENERATE_JAVA=true
            GENERATE_PYTHON=true
            shift
            ;;
        --clean)
            CLEAN=true
            shift
            ;;
        --help|-h)
            echo "USORA Protobuf Code Generator"
            echo
            echo "Usage: $0 [options]"
            echo
            echo "Options:"
            echo "  --rust           Generate Rust protobuf code using prost/tonic"
            echo "  --java           Generate Java gRPC stubs using protoc + grpc-java"
            echo "  --python         Generate Python stubs using grpcio-tools"
            echo "  --all            Generate code for all languages (default)"
            echo "  --clean          Clean all generated code before regenerating"
            echo "  --help           Display this help message"
            echo
            echo "Proto files found in: $PROTO_DIR"
            if [ -d "$PROTO_DIR" ]; then
                echo "Available proto files:"
                for f in "$PROTO_DIR"/*.proto; do
                    echo "  - $(basename "$f")"
                done
            fi
            exit 0
            ;;
        *)
            log_error "Unknown option: $1"
            echo "Usage: $0 [--rust] [--java] [--python] [--all] [--clean]"
            exit 1
            ;;
    esac
done

# --- Ensure Proto Directory Exists ---
if [ ! -d "$PROTO_DIR" ]; then
    log_error "Proto directory not found: $PROTO_DIR"
    log_error "Expected .proto files in shared/proto/"
    exit 1
fi

PROTO_FILES=("$PROTO_DIR"/*.proto)
if [ ${#PROTO_FILES[@]} -eq 0 ]; then
    log_error "No .proto files found in $PROTO_DIR"
    exit 1
fi

log_info "Found ${#PROTO_FILES[@]} proto files to process"

# --- Install protoc if missing ---
ensure_protoc() {
    if command -v protoc &>/dev/null; then
        local version
        version=$(protoc --version 2>&1 | grep -oP '\d+\.\d+\.\d+' | head -1)
        log_success "protoc $version found"
        return 0
    fi

    log_info "Installing protoc $PROTOC_VERSION..."
    local os_arch="linux-x86_64"
    if [[ "$(uname)" == "Darwin" ]]; then
        os_arch="osx-x86_64"
        if [[ "$(uname -m)" == "arm64" ]]; then
            os_arch="osx-aarch_64"
        fi
    fi

    local protoc_url="https://github.com/protocolbuffers/protobuf/releases/download/v${PROTOC_VERSION}/protoc-${PROTOC_VERSION}-${os_arch}.zip"
    local tmp_dir
    tmp_dir=$(mktemp -d)

    curl -sL "$protoc_url" -o "$tmp_dir/protoc.zip"
    unzip -q "$tmp_dir/protoc.zip" -d "$tmp_dir/protoc"
    sudo mv "$tmp_dir/protoc/bin/protoc" /usr/local/bin/protoc
    sudo mv "$tmp_dir/protoc/include/google" /usr/local/include/ 2>/dev/null || true
    rm -rf "$tmp_dir"

    if command -v protoc &>/dev/null; then
        log_success "protoc $(protoc --version | grep -oP '\d+\.\d+\.\d+') installed"
    else
        log_error "Failed to install protoc. Please install manually."
        return 1
    fi
}

# --- Install protoc-gen-grpc-java if needed ---
ensure_grpc_java_plugin() {
    if command -v protoc-gen-grpc-java &>/dev/null; then
        return 0
    fi

    log_info "Installing protoc-gen-grpc-java plugin..."
    local os_arch="linux-x86_64"
    if [[ "$(uname)" == "Darwin" ]]; then
        os_arch="osx-x86_64"
    fi

    local plugin_url="https://repo1.maven.org/maven2/io/grpc/protoc-gen-grpc-java/${GRPC_JAVA_VERSION}/protoc-gen-grpc-java-${GRPC_JAVA_VERSION}-${os_arch}.exe"
    local tmp_plugin
    tmp_plugin=$(mktemp)

    curl -sL "$plugin_url" -o "$tmp_plugin"
    chmod +x "$tmp_plugin"
    sudo mv "$tmp_plugin" /usr/local/bin/protoc-gen-grpc-java
    log_success "protoc-gen-grpc-java installed"
}

# --- Clean Generated Code ---
if [ "$CLEAN" = true ]; then
    log_step "Cleaning generated code"

    if [ -d "$OUT_DIR_RUST" ]; then
        rm -rf "$OUT_DIR_RUST"
        log_info "Cleaned Rust output: $OUT_DIR_RUST"
    fi
    if [ -d "$OUT_DIR_JAVA" ]; then
        rm -rf "$OUT_DIR_JAVA"
        log_info "Cleaned Java output: $OUT_DIR_JAVA"
    fi
    if [ -d "$OUT_DIR_PYTHON" ]; then
        rm -rf "$OUT_DIR_PYTHON"
        log_info "Cleaned Python output: $OUT_DIR_PYTHON"
    fi

    # Also clean Rust build.rs generated code in each service
    for service in usora-api-gateway usora-document-processor usora-face-matching-engine usora-risk-scoring-engine; do
        local service_dir="$RUST_SERVICES_DIR/$service"
        if [ -d "$service_dir/src" ]; then
            find "$service_dir/src" -name "*.rs" -path "*/proto/*" -delete 2>/dev/null || true
            find "$service_dir/src" -path "*/prost_*.rs" -delete 2>/dev/null || true
        fi
    done

    log_success "Clean completed"
fi

# ==========================================================================
# RUST CODE GENERATION (prost/tonic)
# ==========================================================================
generate_rust() {
    log_step "Generating Rust protobuf code"

    mkdir -p "$OUT_DIR_RUST"

    for proto_file in "${PROTO_FILES[@]}"; do
        local proto_name
        proto_name=$(basename "$proto_file" .proto)
        log_info "Processing $proto_name.proto for Rust..."

        # Generate using prost/tonic via build.rs approach
        # For direct generation, we use the protoc plugin approach
        local gen_dir
        gen_dir=$(mktemp -d)

        protoc \
            --proto_path="$PROTO_DIR" \
            --rust_out="$gen_dir" \
            --tonic_out="$gen_dir" \
            "$proto_file" 2>/dev/null || {
            log_warn "Direct generation failed for $proto_name (using cargo build instead)"
        }

        if [ -d "$gen_dir" ]; then
            cp -r "$gen_dir"/*.rs "$OUT_DIR_RUST/" 2>/dev/null || true
            rm -rf "$gen_dir"
        fi
    done

    # Also generate via each Rust service's build.rs
    log_info "Generating Rust code via cargo build (build.rs)..."
    for service in usora-api-gateway usora-document-processor usora-face-matching-engine usora-risk-scoring-engine; do
        local service_dir="$RUST_SERVICES_DIR/$service"
        if [ -f "$service_dir/build.rs" ]; then
            log_info "  Running build.rs for $service..."
            (cd "$service_dir" && cargo build --release 2>/dev/null) || {
                log_warn "  cargo build for $service failed. Protobuf code may need manual build."
            }
        fi
    done

    # Format generated Rust code
    if command -v rustfmt &>/dev/null; then
        log_info "Formatting generated Rust code..."
        find "$OUT_DIR_RUST" -name "*.rs" -exec rustfmt {} \; 2>/dev/null || true
    fi

    log_success "Rust protobuf generation completed"
}

# ==========================================================================
# JAVA CODE GENERATION (protoc + grpc-java)
# ==========================================================================
generate_java() {
    log_step "Generating Java gRPC stubs"

    ensure_protoc
    ensure_grpc_java_plugin

    mkdir -p "$OUT_DIR_JAVA"

    for proto_file in "${PROTO_FILES[@]}"; do
        local proto_name
        proto_name=$(basename "$proto_file" .proto)
        log_info "Processing $proto_name.proto for Java..."

        protoc \
            --proto_path="$PROTO_DIR" \
            --java_out="$OUT_DIR_JAVA" \
            --grpc-java_out="$OUT_DIR_JAVA" \
            "$proto_file"

        log_success "  Generated Java stubs for $proto_name"
    done

    # Copy generated Java sources to individual service directories
    log_info "Distributing Java stubs to services..."
    for service in usora-tenant-service usora-core-service usora-identity-service \
                   usora-audit-service usora-compliance-service usora-integration-service \
                   usora-notification-service; do
        local service_dir="$JAVA_SERVICES_DIR/$service"
        if [ -d "$service_dir" ]; then
            local target_dir="$service_dir/src/main/java"
            if [ -d "$OUT_DIR_JAVA" ]; then
                cp -r "$OUT_DIR_JAVA"/* "$target_dir/" 2>/dev/null || true
            fi
        fi
    done

    log_success "Java gRPC stub generation completed"
}

# ==========================================================================
# PYTHON CODE GENERATION (grpcio-tools)
# ==========================================================================
generate_python() {
    log_step "Generating Python protobuf stubs"

    mkdir -p "$OUT_DIR_PYTHON"

    # Ensure Python requirements
    if ! python3 -c "import grpc_tools" 2>/dev/null; then
        log_info "Installing grpcio-tools for Python code generation..."
        pip3 install grpcio-tools 2>/dev/null || {
            log_warn "Could not install grpcio-tools. Python generation will be skipped."
            return 0
        }
    fi

    for proto_file in "${PROTO_FILES[@]}"; do
        local proto_name
        proto_name=$(basename "$proto_file" .proto)
        log_info "Processing $proto_name.proto for Python..."

        python3 -m grpc_tools.protoc \
            --proto_path="$PROTO_DIR" \
            --python_out="$OUT_DIR_PYTHON" \
            --grpc_python_out="$OUT_DIR_PYTHON" \
            "$proto_file"

        log_success "  Generated Python stubs for $proto_name"
    done

    # Create __init__.py files for Python package
    touch "$OUT_DIR_PYTHON/__init__.py"

    log_success "Python protobuf generation completed"
}

# ==========================================================================
# VALIDATE GENERATED CODE
# ==========================================================================
validate_code() {
    log_step "Validating generated code"

    local errors=0

    # Validate Rust
    if [ "$GENERATE_RUST" = true ]; then
        if [ -d "$OUT_DIR_RUST" ] && [ "$(find "$OUT_DIR_RUST" -name "*.rs" 2>/dev/null | wc -l)" -gt 0 ]; then
            log_success "Rust: $(find "$OUT_DIR_RUST" -name "*.rs" | wc -l) files generated"
        else
            log_warn "Rust: No generated files found (expected if using build.rs)"
        fi

        # Check if Rust services compile
        for service in usora-api-gateway usora-document-processor usora-face-matching-engine usora-risk-scoring-engine; do
            if [ -f "$RUST_SERVICES_DIR/$service/build.rs" ]; then
                log_info "  Validating $service compilation..."
                if (cd "$RUST_SERVICES_DIR/$service" && cargo check --quiet 2>/dev/null); then
                    log_success "  $service compiles"
                else
                    log_warn "  $service has compilation warnings (may need protobuf regenerated via cargo build)"
                    errors=$((errors + 1))
                fi
            fi
        done
    fi

    # Validate Java
    if [ "$GENERATE_JAVA" = true ]; then
        local java_count
        java_count=$(find "$OUT_DIR_JAVA" -name "*.java" 2>/dev/null | wc -l)
        if [ "$java_count" -gt 0 ]; then
            log_success "Java: $java_count files generated"
        else
            log_warn "Java: No generated files found"
        fi
    fi

    # Validate Python
    if [ "$GENERATE_PYTHON" = true ]; then
        local py_count
        py_count=$(find "$OUT_DIR_PYTHON" -name "*.py" 2>/dev/null | wc -l)
        if [ "$py_count" -gt 0 ]; then
            log_success "Python: $py_count files generated"
        else
            log_warn "Python: No generated files found"
        fi
    fi

    if [ $errors -gt 0 ]; then
        log_warn "$errors service(s) have compilation issues"
    else
        log_success "All generated code validated"
    fi
}

# ==========================================================================
# MAIN EXECUTION
# ==========================================================================

echo -e "${CYAN}"
echo "╔══════════════════════════════════════════════════════╗"
echo "║          USORA Protobuf Code Generator              ║"
echo "╚══════════════════════════════════════════════════════╝"
echo -e "${NC}"

START_TIME=$(date +%s)

if [ "$GENERATE_RUST" = true ]; then
    generate_rust
fi

if [ "$GENERATE_JAVA" = true ]; then
    generate_java
fi

if [ "$GENERATE_PYTHON" = true ]; then
    generate_python
fi

validate_code

END_TIME=$(date +%s)
ELAPSED=$((END_TIME - START_TIME))

echo
echo -e "${GREEN}╔══════════════════════════════════════════════════════╗${NC}"
echo -e "${GREEN}║      Protobuf Generation Complete ($(printf '%02d:%02d' $((ELAPSED/60)) $((ELAPSED%60))))       ║${NC}"
echo -e "${GREEN}╚══════════════════════════════════════════════════════╝${NC}"
echo
echo -e "  ${CYAN}Output Directories:${NC}"
echo -e "  Rust:   ${BLUE}$OUT_DIR_RUST${NC}"
echo -e "  Java:   ${BLUE}$OUT_DIR_JAVA${NC}"
echo -e "  Python: ${BLUE}$OUT_DIR_PYTHON${NC}"
echo

exit 0
