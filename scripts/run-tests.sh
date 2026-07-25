#!/usr/bin/env bash
#
# USORA Test Execution Script
# Runs unit, integration, and coverage tests for Rust and Java services.
#
# Usage: ./scripts/run-tests.sh [options]
#
# Options:
#   --rust           Run Rust tests only
#   --java           Run Java tests only
#   --all            Run all tests (default)
#   --coverage       Generate coverage reports (tarpaulin for Rust, JaCoCo for Java)
#   --integration    Run integration tests
#   --quick          Skip slow tests (unit tests only, no coverage)
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
RUN_RUST=false
RUN_JAVA=false
COVERAGE=false
INTEGRATION=false
QUICK=false
TEST_EXIT_CODE=0

# --- Parse Arguments ---
if [ $# -eq 0 ]; then
    RUN_RUST=true
    RUN_JAVA=true
fi

while [[ $# -gt 0 ]]; do
    case "$1" in
        --rust)
            RUN_RUST=true
            shift
            ;;
        --java)
            RUN_JAVA=true
            shift
            ;;
        --all)
            RUN_RUST=true
            RUN_JAVA=true
            shift
            ;;
        --coverage)
            COVERAGE=true
            shift
            ;;
        --integration)
            INTEGRATION=true
            shift
            ;;
        --quick)
            QUICK=true
            shift
            ;;
        --help|-h)
            echo "USORA Test Execution Script"
            echo
            echo "Usage: $0 [options]"
            echo
            echo "Options:"
            echo "  --rust           Run Rust tests only"
            echo "  --java           Run Java tests only"
            echo "  --all            Run all tests (default)"
            echo "  --coverage       Generate coverage reports"
            echo "  --integration    Run integration tests"
            echo "  --quick          Skip slow tests (unit tests only, no coverage)"
            echo "  --help           Display this help message"
            echo
            echo "Rust Services:"
            for d in "$PROJECT_DIR/rust-services"/*/; do
                if [ -f "${d}Cargo.toml" ]; then
                    echo "  - $(basename "$d")"
                fi
            done
            echo
            echo "Java Services:"
            for d in "$PROJECT_DIR/spring-boot-services"/*/; do
                if [ -f "${d}pom.xml" ]; then
                    echo "  - $(basename "$d")"
                fi
            done
            exit 0
            ;;
        *)
            log_error "Unknown option: $1"
            echo "Usage: $0 [--rust] [--java] [--all] [--coverage] [--integration] [--quick]"
            exit 1
            ;;
    esac
done

# ==========================================================================
# RUST TESTS
# ==========================================================================
run_rust_tests() {
    log_step "Running Rust tests"

    if [ ! -d "$PROJECT_DIR/rust-services" ]; then
        log_warn "Rust services directory not found, skipping"
        return
    fi

    # Install coverage tool if needed
    if [ "$COVERAGE" = true ]; then
        if ! command -v cargo-tarpaulin &>/dev/null; then
            log_info "Installing cargo-tarpaulin for coverage..."
            cargo install cargo-tarpaulin --locked 2>/dev/null || {
                log_warn "Could not install cargo-tarpaulin. Coverage will be skipped."
                COVERAGE=false
            }
        fi
    fi

    local rust_services=()
    for d in "$PROJECT_DIR/rust-services"/*/; do
        if [ -f "${d}Cargo.toml" ]; then
            rust_services+=("$(basename "$d")")
        fi
    done

    if [ ${#rust_services[@]} -eq 0 ]; then
        log_warn "No Rust services found"
        return
    fi

    # Track results
    declare -A test_results

    for service in "${rust_services[@]}"; do
        local service_dir="$PROJECT_DIR/rust-services/$service"
        log_info "Testing $service..."

        local test_flags=""
        if [ "$QUICK" = true ]; then
            test_flags="--lib"
        fi

        local test_filter=""
        if [ "$INTEGRATION" = true ]; then
            test_filter="--test integration_test"
        fi

        # Use a temp file to capture output
        local output_file
        output_file=$(mktemp)

        if [ "$COVERAGE" = true ]; then
            log_info "  Running coverage for $service..."
            if (cd "$service_dir" && cargo tarpaulin --out Xml --out Html --output-dir "$PROJECT_DIR/coverage/rust/$service" $test_filter 2>&1 | tee "$output_file"); then
                test_results["$service"]="pass"
            else
                test_results["$service"]="fail"
                TEST_EXIT_CODE=1
            fi
        else
            if (cd "$service_dir" && cargo test --verbose $test_flags $test_filter 2>&1 | tee "$output_file"); then
                test_results["$service"]="pass"
            else
                test_results["$service"]="fail"
                TEST_EXIT_CODE=1
            fi
        fi

        # Parse pass count from output
        local pass_count
        pass_count=$(grep -oP '\d+ passed' "$output_file" 2>/dev/null | awk '{sum+=$1} END{print sum}')
        local fail_count
        fail_count=$(grep -oP '\d+ failed' "$output_file" 2>/dev/null | awk '{sum+=$1} END{print sum}')

        if [ -n "$pass_count" ]; then
            echo -e "    ${GREEN}✓ $pass_count passed${NC}${fail_count:+ ${RED}✗ $fail_count failed${NC}}"
        fi

        rm -f "$output_file"
    done

    # Summary
    echo ""
    echo -e "${CYAN}Rust Test Summary:${NC}"
    local passed=0
    local failed=0
    for service in "${rust_services[@]}"; do
        if [ "${test_results[$service]}" = "pass" ]; then
            echo -e "  ${GREEN}✓${NC} $service"
            passed=$((passed + 1))
        else
            echo -e "  ${RED}✗${NC} $service"
            failed=$((failed + 1))
        fi
    done
    echo -e "${GREEN}  $passed passed${NC}${RED}, $failed failed${NC}"
}

# ==========================================================================
# JAVA TESTS
# ==========================================================================
run_java_tests() {
    log_step "Running Java tests"

    if [ ! -f "$PROJECT_DIR/pom.xml" ]; then
        log_warn "Parent pom.xml not found, skipping Java tests"
        return
    fi

    if [ ! -d "$PROJECT_DIR/spring-boot-services" ]; then
        log_warn "Spring Boot services directory not found, skipping"
        return
    fi

    cd "$PROJECT_DIR"

    local mvn_args=""
    if [ "$QUICK" = true ]; then
        mvn_args="-DskipITs=true -Dtest=!*IntegrationTest"
    fi

    if [ "$INTEGRATION" = true ]; then
        mvn_args="-Dskip.unit.tests=true -Dtest=*IntegrationTest -DfailIfNoTests=false"
    fi

    if [ "$COVERAGE" = true ]; then
        mvn_args="$mvn_args -Djacoco.skip=false"
        log_info "Coverage reports will be generated in spring-boot-services/*/target/site/jacoco/"
    else
        mvn_args="$mvn_args -Djacoco.skip=true"
    fi

    log_info "Running Maven tests..."

    local output_file
    output_file=$(mktemp)

    if mvn verify -B $mvn_args -f pom.xml 2>&1 | tee "$output_file"; then
        log_success "Java tests passed"
    else
        log_error "Java tests failed"
        TEST_EXIT_CODE=1
    fi

    # Parse test counts
    local total_tests
    total_tests=$(grep -oP 'Tests run: \K\d+' "$output_file" 2>/dev/null | awk '{sum+=$1} END{print sum}')
    local total_failures
    total_failures=$(grep -oP 'Failures: \K\d+' "$output_file" 2>/dev/null | awk '{sum+=$1} END{print sum}')
    local total_errors
    total_errors=$(grep -oP 'Errors: \K\d+' "$output_file" 2>/dev/null | awk '{sum+=$1} END{print sum}')

    if [ -n "$total_tests" ]; then
        echo ""
        echo -e "${CYAN}Java Test Results:${NC}"
        echo -e "  Tests run: ${BLUE}$total_tests${NC}"
        echo -e "  Failures:  ${RED}$total_failures${NC}"
        echo -e "  Errors:    ${RED}$total_errors${NC}"

        if [ "$total_failures" -eq 0 ] && [ "$total_errors" -eq 0 ]; then
            echo -e "  ${GREEN}All Java tests passed${NC}"
        fi
    fi

    rm -f "$output_file"
}

# ==========================================================================
# COVERAGE REPORTS
# ==========================================================================
generate_coverage_report() {
    log_step "Generating coverage summary"

    local report_file="$PROJECT_DIR/coverage/coverage-summary.md"
    mkdir -p "$(dirname "$report_file")"

    {
        echo "# USORA Coverage Report"
        echo "Generated: $(date -u)"
        echo ""
    } > "$report_file"

    # Rust coverage
    if [ -d "$PROJECT_DIR/coverage/rust" ]; then
        echo "## Rust Coverage" >> "$report_file"
        for service_dir in "$PROJECT_DIR/coverage/rust"/*/; do
            if [ -f "${service_dir}cobertura.xml" ]; then
                local service
                service=$(basename "$service_dir")
                local coverage_pct
                coverage_pct=$(grep -oP 'line-rate="\K[^"]+' "${service_dir}cobertura.xml" | head -1)
                if [ -n "$coverage_pct" ]; then
                    coverage_pct=$(echo "$coverage_pct * 100" | bc -l | xargs printf "%.1f")
                    echo "- **$service**: $coverage_pct% line coverage" >> "$report_file"
                fi
            fi
        done
    fi

    # Java coverage
    if [ "$RUN_JAVA" = true ]; then
        echo "" >> "$report_file"
        echo "## Java Coverage" >> "$report_file"
        for service_dir in "$PROJECT_DIR/spring-boot-services"/*/; do
            local jacoco_report="${service_dir}target/site/jacoco/jacoco.csv"
            if [ -f "$jacoco_report" ]; then
                local service
                service=$(basename "$service_dir")
                local covered
                local missed
                covered=$(awk -F',' 'NR>1{covered+=$6+$9} END{print covered}' "$jacoco_report" 2>/dev/null)
                missed=$(awk -F',' 'NR>1{missed+=$5+$8} END{print missed}' "$jacoco_report" 2>/dev/null)
                if [ -n "$covered" ] && [ -n "$missed" ] && [ "$((covered + missed))" -gt 0 ]; then
                    local pct
                    pct=$(echo "scale=1; $covered * 100 / ($covered + $missed)" | bc -l)
                    echo "- **$service**: $pct% instruction coverage" >> "$report_file"
                fi
            fi
        done
    fi

    log_success "Coverage summary written to $report_file"
}

# ==========================================================================
# MAIN EXECUTION
# ==========================================================================

echo -e "${CYAN}"
echo "╔══════════════════════════════════════════════════════╗"
echo "║            USORA Test Execution Runner              ║"
echo "╚══════════════════════════════════════════════════════╝"
echo -e "${NC}"

START_TIME=$(date +%s)

echo -e "  Mode: ${BLUE}$([ "$QUICK" = true ] && echo "QUICK" || echo "FULL")${NC}"

if [ "$INTEGRATION" = true ]; then
    echo -e "  Tests: ${YELLOW}Integration Tests Only${NC}"
elif [ "$QUICK" = true ]; then
    echo -e "  Tests: ${YELLOW}Unit Tests Only (Quick Mode)${NC}"
else
    echo -e "  Tests: ${GREEN}All Tests${NC}"
fi

echo -e "  Coverage: ${BLUE}$([ "$COVERAGE" = true ] && echo "ENABLED" || echo "DISABLED")${NC}"
echo ""

if [ "$RUN_RUST" = true ]; then
    run_rust_tests
fi

if [ "$RUN_JAVA" = true ]; then
    run_java_tests
fi

if [ "$COVERAGE" = true ]; then
    generate_coverage_report
fi

END_TIME=$(date +%s)
ELAPSED=$((END_TIME - START_TIME))

echo ""
echo -e "${CYAN}═══════════════════════════════════════════════════════${NC}"
if [ $TEST_EXIT_CODE -eq 0 ]; then
    echo -e "${GREEN}  ALL TESTS PASSED ($(printf '%02d:%02d' $((ELAPSED/60)) $((ELAPSED%60))))${NC}"
else
    echo -e "${RED}  SOME TESTS FAILED ($(printf '%02d:%02d' $((ELAPSED/60)) $((ELAPSED%60))))${NC}"
fi
echo -e "${CYAN}═══════════════════════════════════════════════════════${NC}"

exit $TEST_EXIT_CODE
