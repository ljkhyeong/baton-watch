#!/usr/bin/env bash

set -euo pipefail
set +x
umask 077

readonly TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly REPOSITORY_ROOT="$(cd "$TEST_DIR/../.." && pwd)"
readonly TEMP_DIR="$(mktemp -d)"
readonly RUNTIME_PRIVILEGES_CALLBACK="$REPOSITORY_ROOT/ops/flyway/afterMigrate__runtime_privileges.sql"
readonly OWNER_SECRET="owner-password-0123456789-abcdef"
readonly RUNTIME_SECRET="runtime-password-0123456789-abcdef"

cleanup() {
    rm -rf "$TEMP_DIR"
}
trap cleanup EXIT

mkdir -p "$TEMP_DIR/bin"
printf '%s\n' "$OWNER_SECRET" > "$TEMP_DIR/owner-password"
printf '%s\n' "$RUNTIME_SECRET" > "$TEMP_DIR/runtime-password"

cat > "$TEMP_DIR/bin/psql" <<'SH'
#!/bin/sh
set -eu
for argument in "$@"; do
    case "$argument" in
        --file=*)
            cp "${argument#--file=}" "$WATCH_TEST_CAPTURE"
            exit 0
            ;;
        --command=*)
            cat > /dev/null
            printf '%s\n' "${argument#--command=}" > "${WATCH_TEST_CAPTURE}.password-command"
            exit 0
            ;;
    esac
done
exit 64
SH

cat > "$TEMP_DIR/bin/flyway" <<'SH'
#!/bin/sh
set -eu
for argument in "$@"; do
    case "$argument" in
        -configFiles=*)
            cp "${argument#-configFiles=}" "$WATCH_TEST_CAPTURE"
            exit 0
            ;;
    esac
done
exit 64
SH
chmod 0700 "$TEMP_DIR/bin/psql" "$TEMP_DIR/bin/flyway"

run_operation() {
    operation="$1"
    capture="$2"
    PATH="$TEMP_DIR/bin:$PATH" \
    WATCH_TEST_CAPTURE="$capture" \
    WATCH_DB_OWNER_PASSWORD_FILE="$TEMP_DIR/owner-password" \
    WATCH_DB_RUNTIME_PASSWORD_FILE="$TEMP_DIR/runtime-password" \
    WATCH_DB_RUNTIME_USER=baton_watch_runtime \
    PGHOST=postgres \
    PGPORT=5432 \
    PGDATABASE=baton_watch \
    PGUSER=baton_watch_owner \
        "$REPOSITORY_ROOT/ops/staging-database-operation.sh" "$operation"
}

role_output="$TEMP_DIR/role-output"
run_operation configure-runtime-role "$TEMP_DIR/role.sql" > "$role_output" 2>&1
grep -Fq 'CREATE ROLE baton_watch_runtime LOGIN NOINHERIT NOSUPERUSER' "$TEMP_DIR/role.sql"
grep -Fq "ALTER ROLE baton_watch_runtime LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS CONNECTION LIMIT 32 VALID UNTIL 'infinity'" "$TEMP_DIR/role.sql"
grep -Fq 'ALTER ROLE baton_watch_runtime RESET ALL' "$TEMP_DIR/role.sql"
grep -Fq 'ALTER ROLE baton_watch_runtime IN DATABASE baton_watch RESET ALL' "$TEMP_DIR/role.sql"
grep -Fq 'ALTER ROLE baton_watch_runtime SET search_path TO pg_catalog, public' "$TEMP_DIR/role.sql"
grep -Fq 'FROM pg_auth_members WHERE member = runtime_role_oid OR roleid = runtime_role_oid' "$TEMP_DIR/role.sql"
grep -Fq "FROM pg_shdepend WHERE refclassid = 'pg_authid'::regclass" "$TEMP_DIR/role.sql"
grep -Fq 'REVOKE TEMPORARY ON DATABASE baton_watch FROM PUBLIC' "$TEMP_DIR/role.sql"
grep -Fq 'REVOKE ALL PRIVILEGES ON DATABASE baton_watch FROM baton_watch_runtime' "$TEMP_DIR/role.sql"
grep -Fq 'REVOKE ALL PRIVILEGES ON SCHEMA public FROM baton_watch_runtime' "$TEMP_DIR/role.sql"
grep -Fq 'REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA public FROM baton_watch_runtime' "$TEMP_DIR/role.sql"
grep -Fq 'REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public FROM baton_watch_runtime' "$TEMP_DIR/role.sql"
grep -Fq 'REVOKE ALL PRIVILEGES ON ALL FUNCTIONS IN SCHEMA public FROM baton_watch_runtime' "$TEMP_DIR/role.sql"
grep -Fq 'REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA public FROM PUBLIC' "$TEMP_DIR/role.sql"
grep -Fq 'REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public FROM PUBLIC' "$TEMP_DIR/role.sql"
grep -Fq 'REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA public FROM PUBLIC' "$TEMP_DIR/role.sql"
grep -Fq 'ALTER DEFAULT PRIVILEGES FOR ROLE baton_watch_owner IN SCHEMA public REVOKE ALL ON TABLES FROM baton_watch_runtime' "$TEMP_DIR/role.sql"
grep -Fq 'ALTER DEFAULT PRIVILEGES FOR ROLE baton_watch_owner REVOKE ALL ON TABLES FROM baton_watch_runtime' "$TEMP_DIR/role.sql"
grep -Fq 'ALTER DEFAULT PRIVILEGES FOR ROLE baton_watch_owner IN SCHEMA public REVOKE ALL ON SEQUENCES FROM baton_watch_runtime' "$TEMP_DIR/role.sql"
grep -Fq 'ALTER DEFAULT PRIVILEGES FOR ROLE baton_watch_owner REVOKE ALL ON SEQUENCES FROM baton_watch_runtime' "$TEMP_DIR/role.sql"
grep -Fq 'ALTER DEFAULT PRIVILEGES FOR ROLE baton_watch_owner REVOKE ALL ON FUNCTIONS FROM baton_watch_runtime' "$TEMP_DIR/role.sql"
grep -Fq 'ALTER DEFAULT PRIVILEGES FOR ROLE baton_watch_owner IN SCHEMA public REVOKE ALL ON FUNCTIONS FROM baton_watch_runtime' "$TEMP_DIR/role.sql"
grep -Fq 'ALTER DEFAULT PRIVILEGES FOR ROLE baton_watch_owner IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO baton_watch_runtime' "$TEMP_DIR/role.sql"
grep -Fq 'ALTER DEFAULT PRIVILEGES FOR ROLE baton_watch_owner REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC' "$TEMP_DIR/role.sql"
if grep -Fq 'PASSWORD' "$TEMP_DIR/role.sql" || grep -Fq "$RUNTIME_SECRET" "$TEMP_DIR/role.sql"; then
    printf '[staging-database-operation-test] 역할 SQL 파일에 런타임 비밀번호가 포함됐습니다\n' >&2
    exit 1
fi
grep -Fq '\password baton_watch_runtime' "$TEMP_DIR/role.sql.password-command"
if grep -Fq 'GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES' "$TEMP_DIR/role.sql"; then
    printf '[staging-database-operation-test] 역할 초기화가 마이그레이션 전 테이블 권한을 부여했습니다\n' >&2
    exit 1
fi
if grep -Fq "$OWNER_SECRET" "$role_output" || grep -Fq "$RUNTIME_SECRET" "$role_output"; then
    printf '[staging-database-operation-test] 역할 초기화가 비밀값을 출력했습니다\n' >&2
    exit 1
fi

if PATH="$TEMP_DIR/bin:$PATH" \
        WATCH_TEST_CAPTURE="$TEMP_DIR/same-role.sql" \
        WATCH_DB_OWNER_PASSWORD_FILE="$TEMP_DIR/owner-password" \
        WATCH_DB_RUNTIME_PASSWORD_FILE="$TEMP_DIR/runtime-password" \
        WATCH_DB_RUNTIME_USER=baton_watch_owner \
        PGHOST=postgres PGPORT=5432 PGDATABASE=baton_watch PGUSER=baton_watch_owner \
        "$REPOSITORY_ROOT/ops/staging-database-operation.sh" configure-runtime-role \
        > "$TEMP_DIR/same-role-output" 2>&1; then
    printf '[staging-database-operation-test] 소유자와 같은 런타임 역할을 허용했습니다\n' >&2
    exit 1
fi

if PATH="$TEMP_DIR/bin:$PATH" \
        WATCH_TEST_CAPTURE="$TEMP_DIR/reserved-role.sql" \
        WATCH_DB_OWNER_PASSWORD_FILE="$TEMP_DIR/owner-password" \
        WATCH_DB_RUNTIME_PASSWORD_FILE="$TEMP_DIR/runtime-password" \
        WATCH_DB_RUNTIME_USER=pg_read_all_data \
        PGHOST=postgres PGPORT=5432 PGDATABASE=baton_watch PGUSER=baton_watch_owner \
        "$REPOSITORY_ROOT/ops/staging-database-operation.sh" configure-runtime-role \
        > "$TEMP_DIR/reserved-role-output" 2>&1; then
    printf '[staging-database-operation-test] PostgreSQL 예약 역할을 런타임 역할로 허용했습니다\n' >&2
    exit 1
fi

cp "$TEMP_DIR/owner-password" "$TEMP_DIR/runtime-password"
if run_operation configure-runtime-role "$TEMP_DIR/same-secret.sql" \
        > "$TEMP_DIR/same-secret-output" 2>&1; then
    printf '[staging-database-operation-test] 소유자와 같은 런타임 비밀값을 허용했습니다\n' >&2
    exit 1
fi
printf '%s\n' "$RUNTIME_SECRET" > "$TEMP_DIR/runtime-password"

migration_output="$TEMP_DIR/migration-output"
run_operation migrate "$TEMP_DIR/flyway.conf" > "$migration_output" 2>&1
grep -Fq 'flyway.url=jdbc:postgresql://postgres:5432/baton_watch' "$TEMP_DIR/flyway.conf"
grep -Fq 'flyway.user=baton_watch_owner' "$TEMP_DIR/flyway.conf"
grep -Fq 'flyway.locations=filesystem:/flyway/sql,filesystem:/flyway/callbacks' "$TEMP_DIR/flyway.conf"
grep -Fq 'flyway.placeholders.runtimeRole=baton_watch_runtime' "$TEMP_DIR/flyway.conf"
grep -Fq 'flyway.cleanDisabled=true' "$TEMP_DIR/flyway.conf"
grep -Fq 'REVOKE ALL PRIVILEGES' "$RUNTIME_PRIVILEGES_CALLBACK"
grep -Fq 'ON ALL TABLES IN SCHEMA public' "$RUNTIME_PRIVILEGES_CALLBACK"
grep -Fq 'ON ALL SEQUENCES IN SCHEMA public' "$RUNTIME_PRIVILEGES_CALLBACK"
grep -Fq 'ON ALL FUNCTIONS IN SCHEMA public' "$RUNTIME_PRIVILEGES_CALLBACK"
grep -Fq 'ON TABLE flyway_schema_history' "$RUNTIME_PRIVILEGES_CALLBACK"
grep -Fq 'REVOKE INSERT, UPDATE, DELETE' "$RUNTIME_PRIVILEGES_CALLBACK"
grep -Fq 'ON TABLE watch_health_change_event_backlog' "$RUNTIME_PRIVILEGES_CALLBACK"
grep -Fq 'SET search_path = pg_catalog, pg_temp' \
    "$REPOSITORY_ROOT/adapter-out-persistence/src/main/resources/db/migration/V3__bound_persistence_maintenance.sql"
grep -Fq 'UPDATE public.watch_health_change_event_backlog' \
    "$REPOSITORY_ROOT/adapter-out-persistence/src/main/resources/db/migration/V3__bound_persistence_maintenance.sql"
if grep -Fq "$OWNER_SECRET" "$migration_output"; then
    printf '[staging-database-operation-test] 마이그레이션이 비밀값을 출력했습니다\n' >&2
    exit 1
fi

printf '%s\n' 'invalid:database-password' > "$TEMP_DIR/runtime-password"
if run_operation configure-runtime-role "$TEMP_DIR/invalid.sql" \
        > "$TEMP_DIR/invalid-output" 2>&1; then
    printf '[staging-database-operation-test] 잘못된 비밀값을 허용했습니다\n' >&2
    exit 1
fi
if grep -Fq 'invalid:database-password' "$TEMP_DIR/invalid-output"; then
    printf '[staging-database-operation-test] 실패 출력이 비밀값을 노출했습니다\n' >&2
    exit 1
fi

printf '[staging-database-operation-test] 데이터베이스 역할과 마이그레이션 경계가 통과했습니다\n'
