#!/usr/bin/env bash
#
# USORA Database Migration Script
# Manages Flyway database migrations for all Spring Boot services.
#
# Usage: ./scripts/db-migrate.sh [environment] [command] [options]
#
# Environments: dev, staging, prod
# Commands: migrate, clean, info, validate, baseline, repair
#
# Options:
#   --dry-run           Show SQL that would be executed without running it
#   --tenant=TENANT_ID  Run migrations for a specific tenant schema
#   --help              Display this help message
#
# Environment Variables:
#   DB_HOST             PostgreSQL host (default: localhost)
#   DB_PORT             PostgreSQL port (default: 5432)
#   DB_NAME             PostgreSQL database name (default: usora)
#   DB_USER             PostgreSQL username (default: usora)
#   DB_PASSWORD         PostgreSQL password (default: usora_dev)
#   DB_SCHEMA           Target schema (default: public)
#   FLYWAY_LOCATIONS    Flyway migration locations
#   FLYWAY_TABLE        Flyway history table (default: flyway_schema_history)
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

# --- Default Configuration ---
ENVIRONMENT="${1:-dev}"
COMMAND="${2:-migrate}"
DRY_RUN=false
TENANT_ID=""

# DB connection defaults
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-usora}"
DB_USER="${DB_USER:-usora}"
DB_PASSWORD="${DB_PASSWORD:-usora_dev}"
DB_SCHEMA="${DB_SCHEMA:-public}"
FLYWAY_TABLE="${FLYWAY_TABLE:-flyway_schema_history}"

# --- Parse Arguments ---
shift 2 2>/dev/null || true

while [[ $# -gt 0 ]]; do
    case "$1" in
        --dry-run)
            DRY_RUN=true
            shift
            ;;
        --tenant=*)
            TENANT_ID="${1#*=}"
            shift
            ;;
        --help|-h)
            echo "USORA Database Migration Script"
            echo
            echo "Usage: $0 [environment] [command] [options]"
            echo
            echo "Environments:"
            echo "  dev          Development environment (default)"
            echo "  staging      Staging environment"
            echo "  prod         Production environment"
            echo
            echo "Commands:"
            echo "  migrate      Apply pending migrations (default)"
            echo "  clean        Drop all objects in configured schemas"
            echo "  info         Display migration information"
            echo "  validate     Validate applied migrations against available"
            echo "  baseline     Baseline an existing database (ignore existing migrations)"
            echo "  repair       Repair the Flyway schema history table"
            echo
            echo "Options:"
            echo "  --dry-run           Show SQL without executing"
            echo "  --tenant=TENANT_ID  Target a specific tenant schema"
            echo "  --help              Show this help"
            echo
            echo "Environment Variables:"
            echo "  DB_HOST, DB_PORT, DB_NAME, DB_USER, DB_PASSWORD, DB_SCHEMA"
            echo
            exit 0
            ;;
        *)
            log_error "Unknown option: $1"
            echo "Usage: $0 [environment] [command] [--dry-run] [--tenant=ID]"
            exit 1
            ;;
    esac
done

# --- Environment Configurations ---
case "$ENVIRONMENT" in
    dev)
        DB_HOST="${DB_HOST:-localhost}"
        DB_PORT="${DB_PORT:-5432}"
        DB_NAME="${DB_NAME:-usora}"
        DB_USER="${DB_USER:-usora}"
        DB_PASSWORD="${DB_PASSWORD:-usora_dev}"
        ;;
    staging)
        DB_HOST="${DB_HOST:-staging-db.usora.internal}"
        DB_PORT="${DB_PORT:-5432}"
        DB_NAME="${DB_NAME:-usora_staging}"
        DB_USER="${DB_USER:-usora_staging}"
        DB_PASSWORD="${DB_PASSWORD:-}"
        ;;
    prod)
        DB_HOST="${DB_HOST:-prod-db.usora.internal}"
        DB_PORT="${DB_PORT:-5432}"
        DB_NAME="${DB_NAME:-usora_prod}"
        DB_USER="${DB_USER:-usora_prod}"
        DB_PASSWORD="${DB_PASSWORD:-}"
        ;;
    *)
        log_error "Unknown environment: $ENVIRONMENT"
        echo "Valid environments: dev, staging, prod"
        exit 1
        ;;
esac

# --- Validate ---
if [ -z "$DB_PASSWORD" ] && [ "$ENVIRONMENT" != "dev" ]; then
    log_error "DB_PASSWORD is not set. This is required for $ENVIRONMENT environment."
    exit 1
fi

# --- Display Configuration ---
log_step "Database Migration: $ENVIRONMENT / $COMMAND"
echo -e "  ${CYAN}Host:${NC}       $DB_HOST:$DB_PORT"
echo -e "  ${CYAN}Database:${NC}   $DB_NAME"
echo -e "  ${CYAN}Schema:${NC}     $DB_SCHEMA"
echo -e "  ${CYAN}User:${NC}       $DB_USER"
echo -e "  ${CYAN}Table:${NC}      $FLYWAY_TABLE"
if [ -n "$TENANT_ID" ]; then
    echo -e "  ${CYAN}Tenant:${NC}     $TENANT_ID"
fi
if [ "$DRY_RUN" = true ]; then
    echo -e "  ${YELLOW}Mode:${NC}       DRY RUN (no changes will be made)"
fi
echo

# --- Locate Migration Files ---
MIGRATION_DIRS=()
if [ -d "$PROJECT_DIR/spring-boot-services" ]; then
    for service_dir in "$PROJECT_DIR"/spring-boot-services/*/; do
        migration_dir="${service_dir}src/main/resources/db/migration"
        if [ -d "$migration_dir" ]; then
            MIGRATION_DIRS+=("$migration_dir")
        fi
    done
fi

if [ ${#MIGRATION_DIRS[@]} -eq 0 ]; then
    log_error "No migration directories found in spring-boot-services/*/src/main/resources/db/migration/"
    exit 1
fi

log_info "Found ${#MIGRATION_DIRS[@]} migration source directories"

# --- Build JDBC URL ---
JDBC_URL="jdbc:postgresql://$DB_HOST:$DB_PORT/$DB_NAME?currentSchema=$DB_SCHEMA"

# --- Flyway Command Construction ---
build_flyway_cmd() {
    local locations=""
    local first=true
    for dir in "${MIGRATION_DIRS[@]}"; do
        if [ "$first" = true ]; then
            locations="filesystem:$dir"
            first=false
        else
            locations="$locations,filesystem:$dir"
        fi
    done

    local cmd_parts=(
        "mvn"
        "flyway:$COMMAND"
        "-Dflyway.url=$JDBC_URL"
        "-Dflyway.user=$DB_USER"
        "-Dflyway.password=$DB_PASSWORD"
        "-Dflyway.schemas=$DB_SCHEMA"
        "-Dflyway.table=$FLYWAY_TABLE"
        "-Dflyway.locations=$locations"
        "-Dflyway.baselineOnMigrate=true"
        "-Dflyway.outOfOrder=false"
        "-q"
    )

    if [ -n "$TENANT_ID" ]; then
        cmd_parts+=("-Dflyway.schemas=tenant_${TENANT_ID}")
    fi

    echo "${cmd_parts[@]}"
}

# Dry-run mode: Just list SQL files that would be applied
dry_run_migrate() {
    log_step "DRY RUN: Migration files that would be applied"
    for dir in "${MIGRATION_DIRS[@]}"; do
        if [ -d "$dir" ]; then
            echo -e "\n${CYAN}Source:${NC} $dir"
            for file in "$dir"/*.sql; do
                if [ -f "$file" ]; then
                    filename=$(basename "$file")
                    file_size=$(wc -c < "$file" | tr -d ' ')
                    echo "  [$(date -u +%H:%M:%S)] Would apply: $filename (${file_size} bytes)"
                fi
            done
        fi
    done

    if [ -n "$TENANT_ID" ]; then
        echo -e "\n${YELLOW}Note:${NC} Tenant schema 'tenant_${TENANT_ID}' would be targeted"

        # Show tenant-specific V2 migrations
        for dir in "${MIGRATION_DIRS[@]}"; do
            for file in "$dir"/V2__*.sql; do
                if [ -f "$file" ]; then
                    echo "  Tenant migration: $(basename "$file") would be applied to schema tenant_${TENANT_ID}"
                fi
            done
        done
    fi

    echo -e "\n${GREEN}DRY RUN complete. No changes were made.${NC}"
    exit 0
}

if [ "$DRY_RUN" = true ] && [ "$COMMAND" = "migrate" ]; then
    dry_run_migrate
elif [ "$DRY_RUN" = true ]; then
    log_info "Dry-run mode: displaying $COMMAND command that would be executed"
    FLYWAY_CMD=$(build_flyway_cmd)
    log_info "Would execute:"
    echo "  $FLYWAY_CMD"
    exit 0
fi

# --- Execute Migration ---
log_step "Executing Flyway $COMMAND on $ENVIRONMENT"

FLYWAY_CMD=$(build_flyway_cmd)
cd "$PROJECT_DIR"

TIMESTAMP_START=$(timestamp)
log_info "Migration started at $TIMESTAMP_START"

if eval "$FLYWAY_CMD"; then
    TIMESTAMP_END=$(timestamp)
    log_success "Flyway $COMMAND completed successfully"
    log_info "Started:  $TIMESTAMP_START"
    log_info "Finished: $TIMESTAMP_END"
else
    TIMESTAMP_END=$(timestamp)
    log_error "Flyway $COMMAND failed"
    log_info "Started:  $TIMESTAMP_START"
    log_info "Failed:   $TIMESTAMP_END"
    exit 1
fi

# --- Tenant Schema Migrations ---
if [ -n "$TENANT_ID" ]; then
    log_step "Running migrations for tenant schema: tenant_${TENANT_ID}"

    # Ensure tenant schema exists
    PGPASSWORD="$DB_PASSWORD" psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" \
        -c "CREATE SCHEMA IF NOT EXISTS tenant_${TENANT_ID};" 2>/dev/null

    # Run Flyway on tenant schema
    TENANT_CMD=$(build_flyway_cmd)
    eval "$TENANT_CMD" || {
        log_error "Tenant migration failed for tenant_${TENANT_ID}"
        exit 1
    }

    log_success "Tenant schema tenant_${TENANT_ID} migrations complete"
fi

# --- Output Migration Results ---
log_step "Migration Summary"
echo ""
echo -e "${GREEN}┌──────────────────────────────────────────────────────────────┐${NC}"

case "$COMMAND" in
    migrate)
        echo -e "${GREEN}│ Migration applied successfully                             │${NC}"
        ;;
    info)
        echo -e "${GREEN}│ Migration information displayed above                      │${NC}"
        ;;
    validate)
        echo -e "${GREEN}│ All migrations validated successfully                      │${NC}"
        ;;
    baseline)
        echo -e "${GREEN}│ Database baselined successfully                            │${NC}"
        ;;
    repair)
        echo -e "${GREEN}│ Flyway history table repaired                              │${NC}"
        ;;
    clean)
        echo -e "${GREEN}│ Database cleaned                                           │${NC}"
        ;;
esac

echo -e "${GREEN}├──────────────────────────────────────────────────────────────┤${NC}"
printf "${GREEN}│${NC} Environment: %-40s ${GREEN}│${NC}\n" "$ENVIRONMENT"
printf "${GREEN}│${NC} Command:     %-40s ${GREEN}│${NC}\n" "$COMMAND"
printf "${GREEN}│${NC} Database:    %-40s ${GREEN}│${NC}\n" "$DB_NAME@$DB_HOST:$DB_PORT"
printf "${GREEN}│${NC} Schema:      %-40s ${GREEN}│${NC}\n" "${TENANT_ID:+tenant_$TENANT_ID}"
printf "${GREEN}│${NC} Started:     %-40s ${GREEN}│${NC}\n" "$TIMESTAMP_START"
printf "${GREEN}│${NC} Finished:    %-40s ${GREEN}│${NC}\n" "$TIMESTAMP_END"
echo -e "${GREEN}└──────────────────────────────────────────────────────────────┘${NC}"

exit 0
