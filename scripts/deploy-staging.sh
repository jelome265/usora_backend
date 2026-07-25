#!/usr/bin/env bash
#
# USORA Deploy to Staging
# Builds, pushes, and deploys services to the staging Kubernetes cluster.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# --- Source common helpers ---
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

# --- Configuration ---
NAMESPACE="usora-platform"
K8S_OVERLAY_DIR="$PROJECT_ROOT/infrastructure/k8s/overlays/staging"
CONTAINER_REGISTRY="ghcr.io/usora"
DEPLOY_TIMEOUT_SEC=300
HEALTH_CHECK_INTERVAL_SEC=10
SMOKE_TEST_TIMEOUT_SEC=120

# --- Service definitions ---
# Short name => (service dir name, build type, k8s deployment name, health endpoint)
declare -A SERVICE_MAP
SERVICE_MAP["api-gateway"]="usora-api-gateway|rust|usora-gateway|/health"
SERVICE_MAP["document-processor"]="usora-document-processor|rust|usora-document-processor|/health"
SERVICE_MAP["face-matching-engine"]="usora-face-matching-engine|rust|usora-face-matching-engine|/health"
SERVICE_MAP["risk-scoring-engine"]="usora-risk-scoring-engine|rust|usora-risk-scoring-engine|/health"
SERVICE_MAP["core-service"]="usora-core-service|java|usora-core|/actuator/health"
SERVICE_MAP["identity-service"]="usora-identity-service|java|usora-identity|/actuator/health"
SERVICE_MAP["audit-service"]="usora-audit-service|java|usora-audit|/actuator/health"
SERVICE_MAP["compliance-service"]="usora-compliance-service|java|usora-compliance|/actuator/health"
SERVICE_MAP["integration-service"]="usora-integration-service|java|usora-integration|/actuator/health"
SERVICE_MAP["notification-service"]="usora-notification-service|java|usora-notification|/actuator/health"
SERVICE_MAP["tenant-service"]="usora-tenant-service|java|usora-tenant|/actuator/health"

ALL_SERVICES=(
  "api-gateway" "document-processor" "face-matching-engine" "risk-scoring-engine"
  "core-service" "identity-service" "audit-service" "compliance-service"
  "integration-service" "notification-service" "tenant-service"
)

# --- Globals ---
IMAGE_TAG=""
SERVICES_TO_DEPLOY=()
FORCE=false
SKIP_TESTS=false
ROLLBACK_MODE=false
DO_CLEANUP=false
IS_CI=false
BUILD_PIPELINE=()  # services queued for build + deploy

# --- Usage ---
usage() {
  cat <<EOF
Usage: $(basename "$0") [OPTIONS]

Deploy USORA services to the staging Kubernetes cluster.

Options:
  --service NAME   Deploy a specific service (can be repeated)
  --tag TAG        Docker image tag (default: short git commit SHA)
  --force          Force rebuild even without changes
  --skip-tests     Skip post-deployment smoke tests
  --rollback       Rollback the previous deployment
  --cleanup        Remove old unused images from the registry
  --help           Show this help message and exit

Examples:
  $(basename "$0")
  $(basename "$0") --service api-gateway --tag v1.2.3
  $(basename "$0") --service core-service --service identity-service
  $(basename "$0") --rollback --service api-gateway
  $(basename "$0") --force --skip-tests

Services:
$(printf "  - %s\n" "${ALL_SERVICES[@]}")
EOF
  exit 0
}

# --- Utility functions ---

resolve_service_info() {
  local svc="$1"
  local info="${SERVICE_MAP[$svc]:-}"
  if [ -z "$info" ]; then
    log_error "Unknown service: $svc"
    return 1
  fi
  IFS='|' read -r dir_name build_type k8s_name health_path <<< "$info"
  echo "$dir_name|$build_type|$k8s_name|$health_path"
}

get_service_dir() {
  local svc="$1"
  resolve_service_info "$svc" | cut -d'|' -f1
}

get_build_type() {
  local svc="$1"
  resolve_service_info "$svc" | cut -d'|' -f2
}

get_k8s_name() {
  local svc="$1"
  resolve_service_info "$svc" | cut -d'|' -f3
}

get_health_path() {
  local svc="$1"
  resolve_service_info "$svc" | cut -d'|' -f4
}

get_image_name() {
  local svc="$1"
  echo "$CONTAINER_REGISTRY/$(get_service_dir "$svc")"
}

get_dockerfile_path() {
  local build_type="$1"
  case "$build_type" in
    rust) echo "infrastructure/docker/Dockerfile.rust" ;;
    java) echo "infrastructure/docker/Dockerfile.spring-boot" ;;
    *)    log_error "Unknown build type: $build_type"; return 1 ;;
  esac
}

get_build_context() {
  local svc="$1"
  local build_type="$2"
  local dir_name
  dir_name="$(get_service_dir "$svc")"
  case "$build_type" in
    rust) echo "rust-services/$dir_name" ;;
    java) echo "spring-boot-services/$dir_name" ;;
    *)    return 1 ;;
  esac
}

# --- Core functions ---

build_docker() {
  local svc="$1"
  local tag="$2"
  local build_type
  build_type="$(get_build_type "$svc")"
  local image
  image="$(get_image_name "$svc")"
  local dockerfile
  dockerfile="$(get_dockerfile_path "$build_type")"
  local context
  context="$(get_build_context "$svc" "$build_type")"

  log_step "Building Docker image for $svc"
  log_info "  Image: $image:$tag"
  log_info "  Context: $context"
  log_info "  Dockerfile: $dockerfile"

  docker build \
    -t "$image:$tag" \
    -t "$image:latest" \
    -f "$PROJECT_ROOT/$dockerfile" \
    --build-arg SERVICE_NAME="$svc" \
    --build-arg BUILD_TYPE="$build_type" \
    --label "org.opencontainers.image.revision=$tag" \
    --label "org.opencontainers.image.source=https://github.com/usora/usora" \
    "$PROJECT_ROOT/$context"

  log_success "Built $image:$tag"
}

push_docker() {
  local svc="$1"
  local tag="$2"
  local image
  image="$(get_image_name "$svc")"

  log_step "Pushing Docker image for $svc"

  if [ "$IS_CI" = true ]; then
    echo "$GHCR_PAT" | docker login ghcr.io -u "$GHCR_USER" --password-stdin 2>/dev/null || {
      log_warn "ghcr.io login failed; trying with available credentials"
    }
  fi

  docker push "$image:$tag"
  docker push "$image:latest"

  log_success "Pushed $image:$tag"
}

update_k8s_manifests() {
  local svc="$1"
  local tag="$2"
  local k8s_name
  k8s_name="$(get_k8s_name "$svc")"
  local image
  image="$(get_image_name "$svc")"
  local kustomization_file="$K8S_OVERLAY_DIR/kustomization.yml"

  log_step "Updating kustomize manifest for $svc"

  if [ ! -f "$kustomization_file" ]; then
    log_warn "Kustomization file not found at $kustomization_file, creating minimal one"
    cat > "$kustomization_file" <<-KUSTOMIZE_EOF
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization

namespace: $NAMESPACE
namePrefix: staging-

commonLabels:
  environment: staging
  managed-by: kustomize

resources:
  - ../../base
KUSTOMIZE_EOF
  fi

  local patch_dir="$K8S_OVERLAY_DIR/patches"
  mkdir -p "$patch_dir"

  local patch_file="$patch_dir/$k8s_name-image-tag.yaml"
  cat > "$patch_file" <<-PATCH_EOF
apiVersion: apps/v1
kind: Deployment
metadata:
  name: $k8s_name
spec:
  template:
    spec:
      containers:
        - name: $k8s_name
          image: $image:$tag
PATCH_EOF

  log_info "Wrote image patch: $patch_file"
  log_success "Updated $k8s_name image to $image:$tag"
}

deploy_to_staging() {
  local svc="$1"
  local k8s_name
  k8s_name="$(get_k8s_name "$svc")"

  log_step "Deploying $svc to staging"

  if [ ! -d "$K8S_OVERLAY_DIR" ]; then
    log_error "Kustomize overlay directory not found: $K8S_OVERLAY_DIR"
    return 1
  fi

  kubectl apply -k "$K8S_OVERLAY_DIR" --namespace="$NAMESPACE" 2>&1 | while IFS= read -r line; do
    log_info "kubectl: $line"
  done

  log_success "Applied kustomize overlay for $svc"
}

health_check() {
  local svc="$1"
  local k8s_name
  k8s_name="$(get_k8s_name "$svc")"
  local health_path
  health_path="$(get_health_path "$svc")"

  log_step "Running health check for $svc (deployment: $k8s_name)"

  log_info "Waiting for rollout to complete (timeout: ${DEPLOY_TIMEOUT_SEC}s)..."
  if ! kubectl rollout status "deployment/$k8s_name" \
       --namespace="$NAMESPACE" \
       --timeout="${DEPLOY_TIMEOUT_SEC}s"; then
    log_error "Rollout for $k8s_name failed or timed out"
    log_warn "Fetching pod status for debugging..."
    kubectl get pods --namespace="$NAMESPACE" --selector="app=$k8s_name" 2>/dev/null || true
    kubectl describe deployment "$k8s_name" --namespace="$NAMESPACE" 2>/dev/null | tail -30 || true
    return 1
  fi
  log_success "Rollout completed for $k8s_name"

  local endpoint="${health_path}"
  local svc_host="${k8s_name}.${NAMESPACE}.svc.cluster.local"
  local probe_url="http://${svc_host}${endpoint}"

  log_info "Probing health endpoint: $probe_url"
  local elapsed=0
  while [ $elapsed -lt $DEPLOY_TIMEOUT_SEC ]; do
    if kubectl run --namespace="$NAMESPACE" \
          "health-probe-$(echo "$svc" | tr -cd 'a-z0-9')" \
          --image=curlimages/curl:latest \
          --restart=Never \
          --command -- curl -sf -o /dev/null "$probe_url" 2>/dev/null; then
      kubectl delete pod "health-probe-$(echo "$svc" | tr -cd 'a-z0-9')" \
        --namespace="$NAMESPACE" --wait=false 2>/dev/null || true
      log_success "Health check passed for $svc"
      return 0
    fi
    kubectl delete pod "health-probe-$(echo "$svc" | tr -cd 'a-z0-9')" \
      --namespace="$NAMESPACE" --wait=false 2>/dev/null || true
    sleep "$HEALTH_CHECK_INTERVAL_SEC"
    elapsed=$((elapsed + HEALTH_CHECK_INTERVAL_SEC))
  done

  log_error "Health check failed for $svc after ${DEPLOY_TIMEOUT_SEC}s"
  return 1
}

run_smoke_tests() {
  local svc="$1"

  if [ "$SKIP_TESTS" = true ]; then
    log_warn "Skipping smoke tests for $svc (--skip-tests)"
    return 0
  fi

  log_step "Running smoke tests for $svc"
  local test_script="$PROJECT_ROOT/scripts/run-tests.sh"

  if [ ! -f "$test_script" ]; then
    log_warn "Smoke test script not found at $test_script; performing basic connectivity check"
    local k8s_name
    k8s_name="$(get_k8s_name "$svc")"
    local health_path
    health_path="$(get_health_path "$svc")"
    local port=8080
    local svc_host="${k8s_name}.${NAMESPACE}.svc.cluster.local"

    kubectl run --namespace="$NAMESPACE" \
      "smoke-test-$(echo "$svc" | tr -cd 'a-z0-9')" \
      --image=curlimages/curl:latest \
      --restart=Never \
      --command -- sh -c \
        "curl -sf http://${svc_host}:${port}${health_path} && echo 'SMOKE_OK'" 2>/dev/null

    local pod_status
    pod_status=$(kubectl get pod --namespace="$NAMESPACE" \
      "smoke-test-$(echo "$svc" | tr -cd 'a-z0-9')" -o jsonpath='{.status.phase}' 2>/dev/null || echo "Unknown")

    kubectl delete pod --namespace="$NAMESPACE" \
      "smoke-test-$(echo "$svc" | tr -cd 'a-z0-9')" --wait=false 2>/dev/null || true

    if [ "$pod_status" != "Succeeded" ]; then
      log_error "Smoke test failed for $svc (pod status: $pod_status)"
      return 1
    fi

    log_success "Smoke test passed for $svc"
    return 0
  fi

  log_info "Running smoke tests via $test_script for $svc..."
  bash "$test_script" --service "$svc" --env staging --timeout "$SMOKE_TEST_TIMEOUT_SEC"
  log_success "Smoke tests passed for $svc"
}

rollback() {
  local svc="$1"
  local k8s_name
  k8s_name="$(get_k8s_name "$svc")"

  log_step "Rolling back $svc (deployment: $k8s_name)"

  local revision
  revision=$(kubectl rollout history "deployment/$k8s_name" --namespace="$NAMESPACE" 2>/dev/null | \
    tail -2 | head -1 | awk '{print $1}' || echo "")

  if [ -z "$revision" ] || [ "$revision" -le 1 ]; then
    log_warn "No previous revision found for $k8s_name, annotating for manual rollback"
    kubectl annotate "deployment/$k8s_name" --namespace="$NAMESPACE" \
      "usora.dev/rollback-requested=$(date -u '+%Y-%m-%dT%H:%M:%SZ')" --overwrite
    log_info "Rollback annotation added; please perform manual rollback if needed"
    return 0
  fi

  local target_revision=$((revision - 1))
  log_info "Rolling back $k8s_name to revision $target_revision"

  kubectl rollout undo "deployment/$k8s_name" --namespace="$NAMESPACE" --to-revision="$target_revision"

  log_info "Waiting for rollback rollout to complete..."
  if ! kubectl rollout status "deployment/$k8s_name" \
       --namespace="$NAMESPACE" \
       --timeout="${DEPLOY_TIMEOUT_SEC}s"; then
    log_error "Rollback rollout for $k8s_name failed"
    return 1
  fi

  log_success "Rollback completed for $k8s_name to revision $target_revision"
}

notify_slack() {
  local status="$1"
  local details="$2"
  local webhook_url="${SLACK_WEBHOOK_URL:-}"

  if [ -z "$webhook_url" ]; then
    log_warn "SLACK_WEBHOOK_URL not set; skipping Slack notification"
    return 0
  fi

  local color
  case "$status" in
    success) color="good" ;;
    error|failure) color="danger" ;;
    warning|started) color="warning" ;;
    *) color="#808080" ;;
  esac

  local message
  message=$(cat <<-PAYLOAD
{
  "attachments": [
    {
      "color": "$color",
      "title": "USORA Staging Deploy: $status",
      "text": "$details",
      "fields": [
        {
          "title": "Environment",
          "value": "staging",
          "short": true
        },
        {
          "title": "Git SHA",
          "value": "$(git rev-parse --short HEAD 2>/dev/null || echo 'unknown')",
          "short": true
        },
        {
          "title": "Timestamp",
          "value": "$(date -u '+%Y-%m-%d %H:%M:%S UTC')",
          "short": true
        }
      ],
      "footer": "USORA Deploy Pipeline",
      "ts": $(date +%s)
    }
  ]
}
PAYLOAD
)

  curl -sf -X POST -H "Content-Type: application/json" -d "$message" "$webhook_url" 2>/dev/null && \
    log_success "Slack notification sent" || \
    log_warn "Failed to send Slack notification"
}

cleanup_old_images() {
  local svc="$1"
  local keep_last="${2:-10}"
  local image
  image="$(get_image_name "$svc")"

  log_step "Cleaning up old images for $svc (keeping last $keep_last)"

  if [ "$IS_CI" = true ] && [ -n "${GHCR_PAT:-}" ]; then
    log_info "Attempting ghcr.io cleanup for $image..."

    local token
    token=$(echo -n "$GHCR_USER:$GHCR_PAT" | base64 -w0 2>/dev/null || true)

    local tags
    tags=$(curl -sf -H "Authorization: Bearer $GHCR_PAT" \
      "https://api.github.com/orgs/usora/packages/container/$(echo "$image" | tr '/' '%2F')/versions" 2>/dev/null | \
      python3 -c "
import json, sys
data = json.load(sys.stdin)
tags = []
for v in data:
    t = v.get('metadata', {}).get('container', {}).get('tags', [])
    if t:
        tags.append((v['id'], t[0], v.get('created_at', '')))
tags.sort(key=lambda x: x[2], reverse=True)
for item in tags[$keep_last:]:
    print(item[0])
" 2>/dev/null || true)

    if [ -n "$tags" ]; then
      while IFS= read -r version_id; do
        if [ -n "$version_id" ]; then
          log_info "Deleting old image version: $version_id"
          curl -sf -X DELETE -H "Authorization: Bearer $GHCR_PAT" \
            "https://api.github.com/orgs/usora/packages/container/$(echo "$image" | tr '/' '%2F')/versions/$version_id" \
            2>/dev/null || log_warn "Failed to delete version $version_id (may be untagged)"
        fi
      done <<< "$tags"
    fi
  else
    log_info "Registry cleanup only runs in CI with GHCR_PAT; skipping for local run"
  fi

  log_success "Cleanup completed for $svc"
}

# --- Change detection ---

get_changed_services() {
  log_step "Detecting changed services"

  local base_ref
  if [ -n "${GITHUB_BASE_REF:-}" ]; then
    base_ref="origin/${GITHUB_BASE_REF}"
  elif [ -n "${GITHUB_SHA:-}" ]; then
    base_ref="${GITHUB_SHA}^"
  else
    base_ref="HEAD~1"
  fi

  if ! git rev-parse --git-dir &>/dev/null; then
    log_warn "Not in a git repository; deploying all services"
    printf '%s\n' "${ALL_SERVICES[@]}"
    return
  fi

  local changed_files
  changed_files=$(git diff --name-only "$base_ref" 2>/dev/null || git diff --name-only HEAD~1 2>/dev/null || echo "")

  if [ -z "$changed_files" ]; then
    log_warn "No changes detected or unable to compare; deploying all services"
    printf '%s\n' "${ALL_SERVICES[@]}"
    return
  fi

  log_info "Changed files:"
  echo "$changed_files" | while IFS= read -r f; do log_info "  - $f"; done

  local changed_services=()
  for svc in "${ALL_SERVICES[@]}"; do
    local dir_name
    dir_name="$(get_service_dir "$svc")"
    local build_type
    build_type="$(get_build_type "$svc")"

    local search_path
    case "$build_type" in
      rust)   search_path="rust-services/$dir_name" ;;
      java)   search_path="spring-boot-services/$dir_name" ;;
      *)      continue ;;
    esac

    if echo "$changed_files" | grep -q "^$search_path"; then
      changed_services+=("$svc")
      log_info "$svc: changes detected in $search_path"
    fi
  done

  local shared_changed=false
  if echo "$changed_files" | grep -qE "^shared/|^infrastructure/|^pom\.xml|^Cargo\."; then
    shared_changed=true
    log_warn "Shared/infrastructure files changed; all services will be rebuilt"
  fi

  if [ "$shared_changed" = true ]; then
    printf '%s\n' "${ALL_SERVICES[@]}"
  elif [ ${#changed_services[@]} -eq 0 ]; then
    log_warn "No service-specific changes detected; deploying all services by default"
    printf '%s\n' "${ALL_SERVICES[@]}"
  else
    printf '%s\n' "${changed_services[@]}"
  fi
}

# --- Parse arguments ---

parse_args() {
  while [ $# -gt 0 ]; do
    case "$1" in
      --help|-h)
        usage
        ;;
      --service|-s)
        shift
        if [ $# -eq 0 ]; then
          log_error "--service requires an argument"
          exit 1
        fi
        SERVICES_TO_DEPLOY+=("$1")
        shift
        ;;
      --tag|-t)
        shift
        if [ $# -eq 0 ]; then
          log_error "--tag requires an argument"
          exit 1
        fi
        IMAGE_TAG="$1"
        shift
        ;;
      --force|-f)
        FORCE=true
        shift
        ;;
      --skip-tests)
        SKIP_TESTS=true
        shift
        ;;
      --rollback)
        ROLLBACK_MODE=true
        shift
        ;;
      --cleanup)
        DO_CLEANUP=true
        shift
        ;;
      *)
        log_error "Unknown option: $1"
        usage
        ;;
    esac
  done
}

# --- Main ---

main() {
  parse_args "$@"

  # Detect CI environment
  if [ -n "${GITHUB_ACTIONS:-}" ] || [ -n "${CI:-}" ]; then
    IS_CI=true
    log_info "Running in CI environment (GitHub Actions detected)"
  else
    log_info "Running in local environment"
  fi

  # Determine image tag
  if [ -z "$IMAGE_TAG" ]; then
    IMAGE_TAG=$(git rev-parse --short HEAD 2>/dev/null || echo "local-$(date +%s)")
    log_info "Using auto-detected tag: $IMAGE_TAG"
  fi

  # Determine which services to deploy
  if [ ${#SERVICES_TO_DEPLOY[@]} -eq 0 ]; then
    if [ "$FORCE" = true ]; then
      SERVICES_TO_DEPLOY=("${ALL_SERVICES[@]}")
      log_info "Force flag set; deploying all services"
    else
      log_info "No services specified; detecting changed services..."
      mapfile -t SERVICES_TO_DEPLOY < <(get_changed_services)
    fi
  fi

  # Validate all requested services
  for svc in "${SERVICES_TO_DEPLOY[@]}"; do
    if [ -z "${SERVICE_MAP[$svc]:-}" ]; then
      log_error "Invalid service: $svc. Use --help to see valid services"
      exit 1
    fi
  done

  log_step "=== USORA Staging Deploy ==="
  log_info "  Tag:         $IMAGE_TAG"
  log_info "  Services:    ${SERVICES_TO_DEPLOY[*]}"
  log_info "  Force:       $FORCE"
  log_info "  Skip Tests:  $SKIP_TESTS"
  log_info "  Rollback:    $ROLLBACK_MODE"
  log_info "  Cleanup:     $DO_CLEANUP"
  log_info "  CI Mode:     $IS_CI"

  # Validate prerequisites
  if ! command -v kubectl &>/dev/null; then
    log_error "kubectl is required but not installed"
    exit 1
  fi

  if ! command -v docker &>/dev/null; then
    log_error "docker is required but not installed"
    exit 1
  fi

  if ! kubectl cluster-info &>/dev/null; then
    log_error "Cannot connect to Kubernetes cluster. Check your kubeconfig."
    exit 1
  fi

  # Notify start
  notify_slack "started" "Deploy started for: ${SERVICES_TO_DEPLOY[*]} (tag: $IMAGE_TAG)"

  local overall_status="success"
  local deploy_start
  deploy_start=$(date +%s)

  # Process each service
  for svc in "${SERVICES_TO_DEPLOY[@]}"; do
    local svc_status="success"

    log_step "Processing service: $svc"

    if [ "$ROLLBACK_MODE" = true ]; then
      notify_slack "started" "Rollback started for $svc"

      if rollback "$svc"; then
        log_success "Rollback successful for $svc"
      else
        log_error "Rollback failed for $svc"
        svc_status="failure"
        notify_slack "error" "Rollback failed for $svc"
        continue
      fi

      if health_check "$svc"; then
        log_success "Post-rollback health check passed for $svc"
      else
        log_error "Post-rollback health check failed for $svc"
        svc_status="failure"
        notify_slack "error" "Post-rollback health check failed for $svc"
        continue
      fi

      notify_slack "success" "Rollback completed for $svc"
      continue
    fi

    # --- Build ---
    notify_slack "started" "Building $svc (tag: $IMAGE_TAG)"

    if ! build_docker "$svc" "$IMAGE_TAG"; then
      log_error "Docker build failed for $svc"
      notify_slack "error" "Docker build failed for $svc (tag: $IMAGE_TAG)"
      svc_status="failure"
      overall_status="failure"
      continue
    fi

    # --- Push ---
    if ! push_docker "$svc" "$IMAGE_TAG"; then
      log_error "Docker push failed for $svc"
      notify_slack "error" "Docker push failed for $svc (tag: $IMAGE_TAG)"
      svc_status="failure"
      overall_status="failure"
      continue
    fi

    # --- Update manifests ---
    if ! update_k8s_manifests "$svc" "$IMAGE_TAG"; then
      log_error "Failed to update k8s manifests for $svc"
      notify_slack "error" "Failed to update k8s manifests for $svc (tag: $IMAGE_TAG)"
      svc_status="failure"
      overall_status="failure"
      continue
    fi

    # --- Deploy ---
    if ! deploy_to_staging "$svc"; then
      log_error "Deployment failed for $svc"
      notify_slack "error" "Deployment failed for $svc"
      svc_status="failure"
      overall_status="failure"
      continue
    fi

    # --- Health check ---
    if ! health_check "$svc"; then
      log_error "Health check failed for $svc"
      notify_slack "error" "Health check failed for $svc"
      svc_status="failure"
      overall_status="failure"

      log_warn "Attempting automatic rollback for $svc due to health check failure..."
      rollback "$svc" || log_warn "Automatic rollback also failed for $svc"
      continue
    fi

    # --- Smoke tests ---
    if ! run_smoke_tests "$svc"; then
      log_error "Smoke tests failed for $svc"
      notify_slack "error" "Smoke tests failed for $svc"
      svc_status="failure"
      overall_status="failure"

      log_warn "Attempting automatic rollback for $svc due to smoke test failure..."
      rollback "$svc" || log_warn "Automatic rollback also failed for $svc"
      continue
    fi

    if [ "$svc_status" = "success" ]; then
      notify_slack "success" "$svc deployed successfully (tag: $IMAGE_TAG)"
      log_success "$svc deployed and verified successfully!"

      # Cleanup old images after successful deploy
      if [ "$DO_CLEANUP" = true ]; then
        cleanup_old_images "$svc" || log_warn "Cleanup had warnings for $svc"
      fi
    fi
  done

  local deploy_end
  deploy_end=$(date +%s)
  local duration=$((deploy_end - deploy_start))

  log_step "=== Deploy Summary ==="
  log_info "  Duration:  ${duration}s"
  log_info "  Tag:       $IMAGE_TAG"
  log_info "  Services:  ${SERVICES_TO_DEPLOY[*]}"

  if [ "$overall_status" = "success" ]; then
    log_success "All services deployed successfully!"
    notify_slack "success" "All services deployed successfully (${SERVICES_TO_DEPLOY[*]}) in ${duration}s"
    exit 0
  else
    log_error "One or more services failed to deploy. Check logs above for details."
    notify_slack "failure" "Deploy completed with failures (${SERVICES_TO_DEPLOY[*]}) in ${duration}s"
    exit 1
  fi
}

main "$@"
