#!/usr/bin/env bash

set -euo pipefail
set +x
umask 077

TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly TEST_DIR
REPOSITORY_ROOT="$(cd "$TEST_DIR/../.." && pwd)"
readonly REPOSITORY_ROOT
readonly IMAGE_REVISION="${WATCH_TEST_IMAGE_REVISION:-$(git -C "$REPOSITORY_ROOT" rev-parse HEAD)}"
readonly PROJECT_NAME="baton-watch-db-test-$$-${RANDOM}"
readonly POSTGRES_VOLUME="${PROJECT_NAME}-postgres"
TEMP_DIR="$(mktemp -d)"
readonly TEMP_DIR
readonly OWNER_SECRET="owner-password-0123456789-abcdef"
readonly RUNTIME_SECRET="runtime-password-0123456789-abcdef"
readonly NEW_OWNER_SECRET="new-owner-password-0123456789-abcdef"
readonly NEW_RUNTIME_SECRET="new-runtime-password-0123456789-abcdef"
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
    # shellcheck disable=SC2016
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

credential_psql() {
    local role="$1"
    local password_file="$2"
    # shellcheck disable=SC2016
    staging_compose run --rm --no-deps -T \
        --entrypoint /bin/sh \
        --volume "$password_file:/run/secrets/credential-password:ro" \
        database-role-init \
        -c 'PGPASSWORD="$(sed -n "1p" /run/secrets/credential-password)"; export PGPASSWORD; exec psql --no-psqlrc --set=ON_ERROR_STOP=1 --quiet --tuples-only --no-align --username="$1"' \
        credential-psql "$role"
}

rotate_database_password() {
    local operation="$1"
    local new_password_file="$2"
    staging_compose run --rm --no-deps -T \
        --entrypoint /opt/watch/run-as-database-user.sh \
        --volume "$new_password_file:/run/secrets/postgres-new-password:ro" \
        --env WATCH_DB_NEW_PASSWORD_FILE=/run/secrets/postgres-new-password \
        database-role-init \
        70 70 /opt/watch/staging-database-operation.sh "$operation"
}

assert_credential_rejected() {
    local case_name="$1"
    local role="$2"
    local password_file="$3"
    if printf 'SELECT 1;\n' | credential_psql "$role" "$password_file" >"$TEMP_DIR/${case_name}-output" 2>&1; then
        fail "폐기된 ${case_name} 비밀번호로 인증됐습니다"
    fi
}

wait_for_watch() {
    if ! staging_compose up -d --wait --no-deps watch; then
        staging_compose logs --no-color watch >&2 || true
        fail "WATCH 런타임이 정상 상태로 시작되지 않았습니다"
    fi
}

assert_runtime_rejects() {
    local case_name="$1"
    local sql="$2"
    local output="$TEMP_DIR/${case_name}-output"

    if printf '\\set VERBOSITY verbose\n%s\n' "$sql" \
            | runtime_psql >"$output" 2>&1; then
        fail "런타임 역할이 금지된 ${case_name} 변경을 허용했습니다"
    fi
    if ! grep -Fq '42501' "$output"; then
        fail "런타임 역할의 ${case_name} 거부가 권한 오류가 아닙니다"
    fi
}

if [[ ! "$IMAGE_REVISION" =~ ^[0-9a-f]{40}$ ]]; then
    fail "테스트 이미지 리비전은 전체 Git SHA여야 합니다"
fi

printf '%s\n' "$OWNER_SECRET" > "$TEMP_DIR/postgres-owner-password"
printf '%s\n' "$RUNTIME_SECRET" > "$TEMP_DIR/postgres-runtime-password"
printf '%s\n' "$NEW_OWNER_SECRET" > "$TEMP_DIR/new-owner-password"
printf '%s\n' "$NEW_RUNTIME_SECRET" > "$TEMP_DIR/new-runtime-password"
printf '%s\n' 'watch-api-token-0123456789-abcdef' > "$TEMP_DIR/watch-api-token"
printf '%s\n' 'cloudflare-tunnel-token-permission-smoke' > "$TEMP_DIR/cloudflare-tunnel-token"
chmod 0600 \
    "$TEMP_DIR/postgres-owner-password" \
    "$TEMP_DIR/postgres-runtime-password" \
    "$TEMP_DIR/new-owner-password" \
    "$TEMP_DIR/new-runtime-password" \
    "$TEMP_DIR/watch-api-token"
chmod 0444 "$TEMP_DIR/cloudflare-tunnel-token"

for image in \
    "baton-watch-database-operations:${IMAGE_REVISION}" \
    "baton-watch-migrations:${IMAGE_REVISION}" \
    "baton-watch:${IMAGE_REVISION}"; do
    if ! docker image inspect "$image" >/dev/null 2>&1; then
        fail "필수 테스트 이미지가 없습니다: $image"
    fi
done

if ! docker run --rm \
        --user 65532:65532 \
        --cap-drop ALL \
        --read-only \
        --mount "type=bind,src=$TEMP_DIR/cloudflare-tunnel-token,dst=/run/secrets/cloudflare-tunnel-token,readonly" \
        --entrypoint /bin/sh \
        "baton-watch-database-operations:${IMAGE_REVISION}" \
        -c 'test -r /run/secrets/cloudflare-tunnel-token && test -s /run/secrets/cloudflare-tunnel-token'; then
    fail "Cloudflared 비루트 UID가 터널 토큰 파일을 읽을 수 없습니다"
fi

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
if [[ "$migration_evidence" != "1,2,3,4,5" ]]; then
    fail "Flyway V1~V5 적용 증거가 올바르지 않습니다"
fi

role_evidence="$(
    printf '%s\n' \
        "SELECT concat_ws('|'," \
        "  r.rolcanlogin," \
        "  r.rolinherit," \
        "  r.rolsuper," \
        "  r.rolcreatedb," \
        "  r.rolcreaterole," \
        "  r.rolreplication," \
        "  r.rolbypassrls," \
        "  r.rolconnlimit," \
        "  r.rolvaliduntil = 'infinity'::timestamptz," \
        "  NOT EXISTS (" \
        "    SELECT 1 FROM pg_catalog.pg_auth_members m" \
        "    WHERE m.member = r.oid OR m.roleid = r.oid" \
        "  )," \
        "  NOT EXISTS (" \
        "    SELECT 1 FROM pg_catalog.pg_shdepend d" \
        "    WHERE d.refclassid = 'pg_authid'::regclass" \
        "      AND d.refobjid = r.oid" \
        "      AND d.deptype = 'o'" \
        "  ))" \
        "FROM pg_catalog.pg_roles r" \
        "WHERE r.rolname = '${RUNTIME_ROLE}';" \
        | owner_psql \
        | tr -d '[:space:]'
)"
if [[ "$role_evidence" != "t|f|f|f|f|f|f|32|t|t|t" ]]; then
    fail "런타임 역할의 속성·소속·소유권 경계가 올바르지 않습니다"
fi

runtime_search_path="$(
    printf 'SHOW search_path;\n' \
        | runtime_psql \
        | tr -d '[:space:]'
)"
if [[ "$runtime_search_path" != "pg_catalog,public" ]]; then
    fail "런타임 역할의 search_path가 올바르지 않습니다"
fi

printf '%s\n' \
    "CREATE TABLE public.watch_default_privilege_probe (id BIGINT);" \
    "CREATE SEQUENCE public.watch_default_privilege_probe_sequence;" \
    'CREATE FUNCTION public.watch_default_privilege_probe() RETURNS integer LANGUAGE sql AS $$ SELECT 1 $$;' \
    | owner_psql \
    >/dev/null

default_privilege_evidence="$(
    printf '%s\n' \
        "SELECT concat_ws('|'," \
        "  has_table_privilege(" \
        "    '${RUNTIME_ROLE}'," \
        "    'public.watch_default_privilege_probe'," \
        "    'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')," \
        "  has_sequence_privilege(" \
        "    '${RUNTIME_ROLE}'," \
        "    'public.watch_default_privilege_probe_sequence'," \
        "    'USAGE,SELECT,UPDATE')," \
        "  has_function_privilege(" \
        "    '${RUNTIME_ROLE}'," \
        "    'public.watch_default_privilege_probe()'," \
        "    'EXECUTE'));" \
        | owner_psql \
        | tr -d '[:space:]'
)"
if [[ "$default_privilege_evidence" != "f|f|f" ]]; then
    fail "소유자가 새로 만든 객체에 런타임 기본 권한이 부여됐습니다"
fi

privilege_evidence="$(
    printf '%s\n' \
        "SELECT concat_ws('|'," \
        "  has_database_privilege('${RUNTIME_ROLE}', current_database(), 'TEMPORARY')," \
        "  has_table_privilege('${RUNTIME_ROLE}', 'public.watch_monitor', 'SELECT')," \
        "  has_table_privilege('${RUNTIME_ROLE}', 'public.watch_monitor', 'DELETE')," \
        "  has_table_privilege('${RUNTIME_ROLE}', 'public.watch_attempt', 'DELETE')," \
        "  has_column_privilege('${RUNTIME_ROLE}', 'public.watch_attempt', 'claimed_at', 'UPDATE')," \
        "  has_table_privilege('${RUNTIME_ROLE}', 'public.watch_result', 'INSERT')," \
        "  has_column_privilege('${RUNTIME_ROLE}', 'public.watch_result', 'outcome', 'UPDATE')," \
        "  has_table_privilege('${RUNTIME_ROLE}', 'public.watch_health_change_event', 'DELETE')," \
        "  has_column_privilege('${RUNTIME_ROLE}', 'public.watch_health_change_event', 'changed_at', 'UPDATE')," \
        "  has_column_privilege('${RUNTIME_ROLE}', 'public.watch_health_change_event', 'delivery_status', 'UPDATE')," \
        "  has_table_privilege('${RUNTIME_ROLE}', 'public.flyway_schema_history', 'SELECT')," \
        "  has_table_privilege('${RUNTIME_ROLE}', 'public.watch_health_change_event_backlog', 'UPDATE')," \
        "  has_function_privilege('${RUNTIME_ROLE}', 'public.maintain_watch_health_change_event_backlog()', 'EXECUTE'));" \
        | owner_psql \
        | tr -d '[:space:]'
)"
if [[ "$privilege_evidence" != "f|t|f|t|f|t|f|t|f|t|f|f|f" ]]; then
    fail "런타임 역할의 최소 권한 증거가 올바르지 않습니다"
fi

rotate_database_password rotate-runtime-password "$TEMP_DIR/new-runtime-password"
assert_credential_rejected "이전-런타임" "$RUNTIME_ROLE" "$TEMP_DIR/postgres-runtime-password"
printf 'SELECT 1;\n' | credential_psql "$RUNTIME_ROLE" "$TEMP_DIR/new-runtime-password" >/dev/null
cp "$TEMP_DIR/new-runtime-password" "$TEMP_DIR/postgres-runtime-password"

rotate_database_password rotate-owner-password "$TEMP_DIR/new-owner-password"
assert_credential_rejected "이전-소유자" "$OWNER_ROLE" "$TEMP_DIR/postgres-owner-password"
printf 'SELECT 1;\n' | credential_psql "$OWNER_ROLE" "$TEMP_DIR/new-owner-password" >/dev/null
cp "$TEMP_DIR/new-owner-password" "$TEMP_DIR/postgres-owner-password"

printf '%s\n' "$RUNTIME_SECRET" > "$TEMP_DIR/rollback-runtime-password"
chmod 0600 "$TEMP_DIR/rollback-runtime-password"
rotate_database_password rotate-runtime-password "$TEMP_DIR/rollback-runtime-password"
assert_credential_rejected "교체된-런타임" "$RUNTIME_ROLE" "$TEMP_DIR/new-runtime-password"
printf 'SELECT 1;\n' | credential_psql "$RUNTIME_ROLE" "$TEMP_DIR/rollback-runtime-password" >/dev/null
cp "$TEMP_DIR/rollback-runtime-password" "$TEMP_DIR/postgres-runtime-password"

printf '%s\n' "$OWNER_SECRET" > "$TEMP_DIR/rollback-owner-password"
chmod 0600 "$TEMP_DIR/rollback-owner-password"
rotate_database_password rotate-owner-password "$TEMP_DIR/rollback-owner-password"
assert_credential_rejected "교체된-소유자" "$OWNER_ROLE" "$TEMP_DIR/new-owner-password"
printf 'SELECT 1;\n' | credential_psql "$OWNER_ROLE" "$TEMP_DIR/rollback-owner-password" >/dev/null
cp "$TEMP_DIR/rollback-owner-password" "$TEMP_DIR/postgres-owner-password"

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

printf '%s\n' \
    "INSERT INTO public.watch_attempt (" \
    "  attempt_id, resource_reference, source_revision, target_url," \
    "  lease_token, claimed_at, lease_expires_at" \
    ") VALUES (" \
    "  '00000000-0000-0000-0000-000000000010', 'ops-runtime-smoke', 1," \
    "  'https://runtime-smoke.example/path'," \
    "  '00000000-0000-0000-0000-000000000011'," \
    "  transaction_timestamp() - INTERVAL '1 second'," \
    "  transaction_timestamp() + INTERVAL '1 minute'" \
    ");" \
    "INSERT INTO public.watch_result (" \
    "  attempt_id, outcome, http_status_code, completed_at," \
    "  duration_seconds, duration_nanos, response_bytes, redirect_count" \
    ") VALUES (" \
    "  '00000000-0000-0000-0000-000000000010', 'SUCCESS', 204," \
    "  transaction_timestamp(), 0, 0, 0, 0" \
    ");" \
    "INSERT INTO public.watch_health_change_event (" \
    "  event_id, resource_reference, source_revision, attempt_id," \
    "  previous_health, current_health, changed_at," \
    "  delivery_status, delivery_attempt, next_attempt_at" \
    ") VALUES (" \
    "  '00000000-0000-0000-0000-000000000020', 'ops-runtime-smoke', 1, NULL," \
    "  'HEALTHY', 'UNKNOWN', transaction_timestamp()," \
    "  'PENDING', 0, transaction_timestamp()" \
    ");" \
    "UPDATE public.watch_monitor" \
    "SET updated_at = transaction_timestamp()" \
    "WHERE resource_reference = 'ops-runtime-smoke';" \
    "UPDATE public.watch_health_change_event" \
    "SET delivery_attempt = delivery_attempt + 1," \
    "    delivery_lease_token = '00000000-0000-0000-0000-000000000021'," \
    "    delivery_lease_expires_at = next_attempt_at + INTERVAL '1 minute'" \
    "WHERE event_id = '00000000-0000-0000-0000-000000000020';" \
    "UPDATE public.watch_health_change_event" \
    "SET delivery_status = 'DELIVERED'," \
    "    next_attempt_at = NULL," \
    "    delivery_lease_token = NULL," \
    "    delivery_lease_expires_at = NULL," \
    "    delivered_at = transaction_timestamp()," \
    "    last_delivery_outcome = 'DELIVERED'," \
    "    last_http_status_code = 204" \
    "WHERE event_id = '00000000-0000-0000-0000-000000000020';" \
    | runtime_psql \
    >/dev/null

assert_runtime_rejects \
    "attempt-update" \
    "UPDATE public.watch_attempt SET target_url = target_url WHERE attempt_id = '00000000-0000-0000-0000-000000000010';"
assert_runtime_rejects \
    "result-update" \
    "UPDATE public.watch_result SET outcome = outcome WHERE attempt_id = '00000000-0000-0000-0000-000000000010';"
assert_runtime_rejects \
    "event-payload-update" \
    "UPDATE public.watch_health_change_event SET changed_at = changed_at WHERE event_id = '00000000-0000-0000-0000-000000000001';"

printf '%s\n' \
    "DELETE FROM public.watch_health_change_event" \
    "WHERE event_id = '00000000-0000-0000-0000-000000000020';" \
    "DELETE FROM public.watch_attempt" \
    "WHERE attempt_id = '00000000-0000-0000-0000-000000000010';" \
    | runtime_psql \
    >/dev/null

retention_evidence="$(
    printf '%s\n' \
        "SELECT concat_ws('|'," \
        "  (SELECT COUNT(*) FROM public.watch_attempt" \
        "   WHERE attempt_id = '00000000-0000-0000-0000-000000000010')," \
        "  (SELECT COUNT(*) FROM public.watch_result" \
        "   WHERE attempt_id = '00000000-0000-0000-0000-000000000010')," \
        "  (SELECT COUNT(*) FROM public.watch_health_change_event" \
        "   WHERE event_id = '00000000-0000-0000-0000-000000000020')," \
        "  (SELECT pending_count FROM public.watch_health_change_event_backlog" \
        "   WHERE singleton));" \
        | owner_psql \
        | tr -d '[:space:]'
)"
if [[ "$retention_evidence" != "0|0|0|1" ]]; then
    fail "런타임 역할의 허용된 보존 DML 또는 백로그 증거가 올바르지 않습니다"
fi

wait_for_watch
staging_compose restart watch
wait_for_watch

runtime_identity="$(
    # shellcheck disable=SC2016
    staging_compose exec -T watch sh -c '
        printf "%s|" "$(cat /proc/1/comm)"
        sed -n \
            -e "s/^Uid:[[:space:]]*\([0-9]*\).*/\1|/p" \
            -e "s/^Gid:[[:space:]]*\([0-9]*\).*/\1|/p" \
            -e "s/^CapPrm:[[:space:]]*\([0-9a-f]*\).*/\1|/p" \
            -e "s/^CapEff:[[:space:]]*\([0-9a-f]*\).*/\1|/p" \
            -e "s/^NoNewPrivs:[[:space:]]*\([0-9]*\).*/\1/p" \
            /proc/1/status
    ' | tr -d '[:space:]'
)"
if [[ "$runtime_identity" != "java|10001|10001|0000000000000000|0000000000000000|1" ]]; then
    fail "WATCH Java 프로세스의 PID 1·비루트 사용자·권한 제거 증거가 올바르지 않습니다"
fi

secret_permission_evidence="$(
    staging_compose exec -T watch sh -c '
        stat -c "%u:%g:%a" /run/watch-secrets
        stat -c "%u:%g:%a" /run/watch-secrets/spring.datasource.password
        stat -c "%u:%g:%a" /run/watch-secrets/watch.api-token
    ' | tr '\n' '|'
)"
if [[ "$secret_permission_evidence" != "10001:10001:500|10001:10001:400|10001:10001:400|" ]]; then
    fail "WATCH 복사 비밀의 소유권 또는 접근 권한이 올바르지 않습니다"
fi

status_response="$(
    staging_compose exec -T watch \
        wget -q -O - http://127.0.0.1:8080/api/v1/system/status
)"
if ! printf '%s' "$status_response" | python3 -c '
import json
import sys

response = json.load(sys.stdin)
if response.get("service") != "baton-watch" or response.get("status") != "UP":
    raise SystemExit(1)
'; then
    fail "WATCH 런타임 상태 응답이 올바르지 않습니다"
fi

printf '[staging-database-operation-postgres-test] 실제 PostgreSQL 역할·마이그레이션·WATCH 런타임 경계가 통과했습니다\n'
