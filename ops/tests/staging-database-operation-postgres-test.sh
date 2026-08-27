#!/usr/bin/env bash

set -euo pipefail
set +x
umask 077

readonly TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly REPOSITORY_ROOT="$(cd "$TEST_DIR/../.." && pwd)"
readonly IMAGE_REVISION="${WATCH_TEST_IMAGE_REVISION:-$(git -C "$REPOSITORY_ROOT" rev-parse HEAD)}"
readonly PROJECT_NAME="baton-watch-db-test-$$-${RANDOM}"
readonly POSTGRES_VOLUME="${PROJECT_NAME}-postgres"
readonly TEMP_DIR="$(mktemp -d)"
readonly OWNER_SECRET="owner-password-0123456789-abcdef"
readonly RUNTIME_SECRET="runtime-password-0123456789-abcdef"
readonly RUNTIME_ROLE="baton_watch_runtime"
readonly DATABASE_NAME="baton_watch"
readonly OWNER_ROLE="baton_watch_owner"

fail() {
    printf '[staging-database-operation-postgres-test] %s\n' "$1" >&2
    exit 1
}

staging_compose() {
    env \
        WATCH_COMPOSE_PROJECT_NAME="$PROJECT_NAME" \
        WATCH_IMAGE_REVISION="$IMAGE_REVISION" \
        WATCH_POSTGRES_VOLUME_NAME="$POSTGRES_VOLUME" \
        WATCH_DB_OWNER_PASSWORD_FILE="$TEMP_DIR/postgres-owner-password" \
        WATCH_DB_RUNTIME_PASSWORD_FILE="$TEMP_DIR/postgres-runtime-password" \
        WATCH_API_TOKEN_FILE="$TEMP_DIR/watch-api-token" \
        WATCH_DB_NAME="$DATABASE_NAME" \
        WATCH_DB_OWNER_USER="$OWNER_ROLE" \
        WATCH_DB_RUNTIME_USER="$RUNTIME_ROLE" \
        docker compose \
            --project-directory "$REPOSITORY_ROOT" \
            --file "$REPOSITORY_ROOT/compose.staging.yml" \
            "$@"
}

cleanup() {
    local status=$?
    trap - EXIT
    set +e
    staging_compose down --remove-orphans >/dev/null 2>&1
    docker volume rm "$POSTGRES_VOLUME" >/dev/null 2>&1
    rm -rf "$TEMP_DIR"
    exit "$status"
}
trap cleanup EXIT HUP INT TERM

runtime_psql() {
    staging_compose run --rm --no-deps -T \
        --entrypoint /opt/watch/run-as-database-user.sh \
        --env PGUSER="$RUNTIME_ROLE" \
        database-role-init \
        70 70 \
        /bin/sh -c 'PGPASSWORD="$(sed -n "1p" "$WATCH_DB_RUNTIME_PASSWORD_FILE")"; export PGPASSWORD; exec psql --no-psqlrc --set=ON_ERROR_STOP=1 --quiet --tuples-only --no-align'
}

owner_psql() {
    staging_compose exec -T postgres \
        psql --no-psqlrc --set=ON_ERROR_STOP=1 --quiet --tuples-only --no-align \
        --username "$OWNER_ROLE" \
        --dbname "$DATABASE_NAME"
}

if [[ ! "$IMAGE_REVISION" =~ ^[0-9a-f]{40}$ ]]; then
    fail "테스트 이미지 리비전은 전체 Git SHA여야 합니다"
fi

printf '%s\n' "$OWNER_SECRET" > "$TEMP_DIR/postgres-owner-password"
printf '%s\n' "$RUNTIME_SECRET" > "$TEMP_DIR/postgres-runtime-password"
printf '%s\n' 'watch-api-token-0123456789-abcdef' > "$TEMP_DIR/watch-api-token"
chmod 0600 \
    "$TEMP_DIR/postgres-owner-password" \
    "$TEMP_DIR/postgres-runtime-password" \
    "$TEMP_DIR/watch-api-token"

for image in \
    "baton-watch-database-operations:${IMAGE_REVISION}" \
    "baton-watch-migrations:${IMAGE_REVISION}"; do
    if ! docker image inspect "$image" >/dev/null 2>&1; then
        fail "필수 테스트 이미지가 없습니다: $image"
    fi
done

docker volume create "$POSTGRES_VOLUME" >/dev/null
staging_compose up -d --wait postgres
staging_compose run --rm --no-deps -T database-role-init
staging_compose run --rm --no-deps -T migrate

migration_evidence="$(
    printf '%s\n' \
        "SELECT string_agg(version, ',' ORDER BY installed_rank)" \
        "FROM public.flyway_schema_history" \
        "WHERE success;" \
        | owner_psql \
        | tr -d '[:space:]'
)"
if [[ "$migration_evidence" != "1,2,3" ]]; then
    fail "Flyway V1~V3 적용 증거가 올바르지 않습니다"
fi

privilege_evidence="$(
    printf '%s\n' \
        "SELECT concat_ws('|'," \
        "  has_database_privilege('${RUNTIME_ROLE}', current_database(), 'TEMPORARY')," \
        "  has_table_privilege('${RUNTIME_ROLE}', 'public.watch_monitor', 'SELECT')," \
        "  has_table_privilege('${RUNTIME_ROLE}', 'public.flyway_schema_history', 'SELECT')," \
        "  has_table_privilege('${RUNTIME_ROLE}', 'public.watch_health_change_event_backlog', 'UPDATE')," \
        "  has_function_privilege('${RUNTIME_ROLE}', 'public.maintain_watch_health_change_event_backlog()', 'EXECUTE'));" \
        | owner_psql \
        | tr -d '[:space:]'
)"
if [[ "$privilege_evidence" != "f|t|f|f|f" ]]; then
    fail "런타임 역할의 최소 권한 증거가 올바르지 않습니다"
fi

printf '%s\n' \
    "INSERT INTO public.watch_monitor (" \
    "  resource_reference, source_revision, monitor_status, target_url," \
    "  current_health, next_check_at, created_at, updated_at" \
    ") VALUES (" \
    "  'ops-runtime-smoke', 1, 'INACTIVE', NULL," \
    "  'UNKNOWN', NULL, transaction_timestamp(), transaction_timestamp()" \
    ");" \
    "INSERT INTO public.watch_health_change_event (" \
    "  event_id, resource_reference, source_revision, attempt_id," \
    "  previous_health, current_health, changed_at," \
    "  delivery_status, delivery_attempt, next_attempt_at" \
    ") VALUES (" \
    "  '00000000-0000-0000-0000-000000000001', 'ops-runtime-smoke', 1, NULL," \
    "  'UNKNOWN', 'HEALTHY', transaction_timestamp()," \
    "  'PENDING', 0, transaction_timestamp()" \
    ");" \
    | runtime_psql \
    >/dev/null

backlog_evidence="$(
    printf '%s\n' \
        "SELECT pending_count" \
        "FROM public.watch_health_change_event_backlog" \
        "WHERE singleton;" \
        | runtime_psql \
        | tr -d '[:space:]'
)"
if [[ "$backlog_evidence" != "1" ]]; then
    fail "런타임 이벤트 쓰기의 보호된 백로그 갱신 증거가 올바르지 않습니다"
fi

printf '[staging-database-operation-postgres-test] 실제 PostgreSQL 역할·마이그레이션 경계가 통과했습니다\n'
