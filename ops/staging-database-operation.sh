#!/bin/sh

set -eu
set +x
umask 077
export LC_ALL=C

readonly OWNER_PASSWORD_FILE="${WATCH_DB_OWNER_PASSWORD_FILE:-/run/secrets/postgres-owner-password}"
readonly RUNTIME_PASSWORD_FILE="${WATCH_DB_RUNTIME_PASSWORD_FILE:-/run/secrets/postgres-runtime-password}"
readonly NEW_PASSWORD_FILE="${WATCH_DB_NEW_PASSWORD_FILE:-/run/secrets/postgres-new-password}"

require_identifier() {
    value="$1"
    label="$2"
    if ! printf '%s' "$value" | grep -Eq '^[a-z_][a-z0-9_]{0,62}$'; then
        printf '[staging-database-operation] %s 형식이 올바르지 않습니다\n' "$label" >&2
        exit 1
    fi
}

read_secret() {
    secret_file="$1"
    label="$2"
    secret_value=
    extra_value=
    if [ ! -r "$secret_file" ] || ! {
        IFS= read -r secret_value \
            && ! IFS= read -r extra_value \
            && [ -z "$extra_value" ]
    } < "$secret_file"; then
        printf '[staging-database-operation] %s 비밀 파일 형식이 올바르지 않습니다\n' "$label" >&2
        exit 1
    fi
    if ! printf '%s' "$secret_value" | grep -Eq '^[A-Za-z0-9._~-]{32,200}$'; then
        printf '[staging-database-operation] %s 비밀값 형식이 올바르지 않습니다\n' "$label" >&2
        exit 1
    fi
    printf '%s' "$secret_value"
}

require_database_environment() {
    : "${PGHOST:?PGHOST is required}"
    : "${PGPORT:?PGPORT is required}"
    : "${PGDATABASE:?PGDATABASE is required}"
    : "${PGUSER:?PGUSER is required}"
    require_identifier "$PGDATABASE" "데이터베이스 이름"
    require_identifier "$PGUSER" "데이터베이스 소유자"
    if ! printf '%s' "$PGPORT" | grep -Eq '^[0-9]{1,5}$'; then
        printf '[staging-database-operation] 데이터베이스 포트 형식이 올바르지 않습니다\n' >&2
        exit 1
    fi
}

require_runtime_role() {
    : "${WATCH_DB_RUNTIME_USER:?WATCH_DB_RUNTIME_USER is required}"
    require_identifier "$WATCH_DB_RUNTIME_USER" "런타임 데이터베이스 역할"
    case "$WATCH_DB_RUNTIME_USER" in
        pg_*)
            printf '[staging-database-operation] PostgreSQL 예약 역할은 런타임 역할로 사용할 수 없습니다\n' >&2
            exit 1
            ;;
    esac
    if [ "$WATCH_DB_RUNTIME_USER" = "$PGUSER" ]; then
        printf '[staging-database-operation] 데이터베이스 소유자와 런타임 역할은 달라야 합니다\n' >&2
        exit 1
    fi
}

write_pgpass() {
    target_file="$1"
    database_user="$2"
    database_password="$3"
    printf '%s:%s:%s:%s:%s\n' "$PGHOST" "$PGPORT" "$PGDATABASE" "$database_user" "$database_password" > "$target_file"
    chmod 0600 "$target_file"
}

configure_runtime_role() {
    require_database_environment
    require_runtime_role

    owner_password="$(read_secret "$OWNER_PASSWORD_FILE" "데이터베이스 소유자")"
    runtime_password="$(read_secret "$RUNTIME_PASSWORD_FILE" "런타임 데이터베이스")"
    if [ "$owner_password" = "$runtime_password" ]; then
        printf '[staging-database-operation] 데이터베이스 소유자와 런타임 비밀값은 달라야 합니다\n' >&2
        exit 1
    fi
    pgpass_file="$(mktemp /tmp/watch-pgpass.XXXXXX)"
    role_sql="$(mktemp /tmp/watch-role.XXXXXX)"
    trap 'rm -f "$pgpass_file" "$role_sql"' EXIT HUP INT TERM

    write_pgpass "$pgpass_file" "$PGUSER" "$owner_password"
    export PGPASSFILE="$pgpass_file"

    {
        printf 'BEGIN;\n'
        # shellcheck disable=SC2016
        printf 'DO $watch$\nBEGIN\n'
        printf "  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '%s') THEN\n" \
            "$WATCH_DB_RUNTIME_USER"
        printf "    EXECUTE 'CREATE ROLE %s LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS';\n" \
            "$WATCH_DB_RUNTIME_USER"
        # shellcheck disable=SC2016
        printf '  END IF;\nEND\n$watch$;\n'
        printf "ALTER ROLE %s LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS CONNECTION LIMIT 32 VALID UNTIL 'infinity';\n" \
            "$WATCH_DB_RUNTIME_USER"
        printf 'ALTER ROLE %s RESET ALL;\n' "$WATCH_DB_RUNTIME_USER"
        printf 'ALTER ROLE %s IN DATABASE %s RESET ALL;\n' \
            "$WATCH_DB_RUNTIME_USER" "$PGDATABASE"
        printf 'ALTER ROLE %s SET search_path TO pg_catalog, public;\n' \
            "$WATCH_DB_RUNTIME_USER"
        # shellcheck disable=SC2016
        printf 'DO $watch$\nDECLARE\n  runtime_role_oid OID;\nBEGIN\n'
        printf "  SELECT oid INTO STRICT runtime_role_oid FROM pg_roles WHERE rolname = '%s';\n" \
            "$WATCH_DB_RUNTIME_USER"
        printf '  IF EXISTS (SELECT 1 FROM pg_auth_members WHERE member = runtime_role_oid OR roleid = runtime_role_oid) THEN\n'
        printf "    RAISE EXCEPTION 'runtime database role must not participate in role memberships';\n"
        printf '  END IF;\n'
        printf "  IF EXISTS (SELECT 1 FROM pg_shdepend WHERE refclassid = 'pg_authid'::regclass AND refobjid = runtime_role_oid AND deptype = 'o') THEN\n"
        printf "    RAISE EXCEPTION 'runtime database role must not own database objects';\n"
        # shellcheck disable=SC2016
        printf '  END IF;\nEND\n$watch$;\n'
        printf 'REVOKE CREATE ON SCHEMA public FROM PUBLIC;\n'
        printf 'REVOKE TEMPORARY ON DATABASE %s FROM PUBLIC;\n' "$PGDATABASE"
        printf 'REVOKE ALL PRIVILEGES ON DATABASE %s FROM %s;\n' "$PGDATABASE" "$WATCH_DB_RUNTIME_USER"
        printf 'GRANT CONNECT ON DATABASE %s TO %s;\n' "$PGDATABASE" "$WATCH_DB_RUNTIME_USER"
        printf 'REVOKE ALL PRIVILEGES ON SCHEMA public FROM %s;\n' "$WATCH_DB_RUNTIME_USER"
        printf 'GRANT USAGE ON SCHEMA public TO %s;\n' "$WATCH_DB_RUNTIME_USER"
        printf 'REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA public FROM %s;\n' \
            "$WATCH_DB_RUNTIME_USER"
        printf 'REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public FROM %s;\n' \
            "$WATCH_DB_RUNTIME_USER"
        printf 'REVOKE ALL PRIVILEGES ON ALL FUNCTIONS IN SCHEMA public FROM %s;\n' \
            "$WATCH_DB_RUNTIME_USER"
        printf 'REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA public FROM PUBLIC;\n'
        printf 'REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public FROM PUBLIC;\n'
        printf 'REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA public FROM PUBLIC;\n'
        printf 'ALTER DEFAULT PRIVILEGES FOR ROLE %s IN SCHEMA public REVOKE ALL ON TABLES FROM %s;\n' \
            "$PGUSER" "$WATCH_DB_RUNTIME_USER"
        printf 'ALTER DEFAULT PRIVILEGES FOR ROLE %s REVOKE ALL ON TABLES FROM %s;\n' \
            "$PGUSER" "$WATCH_DB_RUNTIME_USER"
        printf 'ALTER DEFAULT PRIVILEGES FOR ROLE %s IN SCHEMA public REVOKE ALL ON SEQUENCES FROM %s;\n' \
            "$PGUSER" "$WATCH_DB_RUNTIME_USER"
        printf 'ALTER DEFAULT PRIVILEGES FOR ROLE %s REVOKE ALL ON SEQUENCES FROM %s;\n' \
            "$PGUSER" "$WATCH_DB_RUNTIME_USER"
        printf 'ALTER DEFAULT PRIVILEGES FOR ROLE %s REVOKE ALL ON FUNCTIONS FROM %s;\n' \
            "$PGUSER" "$WATCH_DB_RUNTIME_USER"
        printf 'ALTER DEFAULT PRIVILEGES FOR ROLE %s IN SCHEMA public REVOKE ALL ON FUNCTIONS FROM %s;\n' \
            "$PGUSER" "$WATCH_DB_RUNTIME_USER"
        printf 'ALTER DEFAULT PRIVILEGES FOR ROLE %s IN SCHEMA public REVOKE ALL ON TABLES FROM PUBLIC;\n' \
            "$PGUSER"
        printf 'ALTER DEFAULT PRIVILEGES FOR ROLE %s REVOKE ALL ON TABLES FROM PUBLIC;\n' \
            "$PGUSER"
        printf 'ALTER DEFAULT PRIVILEGES FOR ROLE %s IN SCHEMA public REVOKE ALL ON SEQUENCES FROM PUBLIC;\n' \
            "$PGUSER"
        printf 'ALTER DEFAULT PRIVILEGES FOR ROLE %s REVOKE ALL ON SEQUENCES FROM PUBLIC;\n' \
            "$PGUSER"
        # 함수 EXECUTE의 PostgreSQL 기본 PUBLIC 부여는 전역 기본값이므로 스키마 범위에서 취소할 수 없다.
        printf 'ALTER DEFAULT PRIVILEGES FOR ROLE %s REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;\n' \
            "$PGUSER"
        printf 'ALTER DEFAULT PRIVILEGES FOR ROLE %s IN SCHEMA public REVOKE ALL ON FUNCTIONS FROM PUBLIC;\n' \
            "$PGUSER"
        printf 'COMMIT;\n'
    } > "$role_sql"
    chmod 0600 "$role_sql"

    psql --no-psqlrc --set=ON_ERROR_STOP=1 --file="$role_sql"
    printf '%s\n%s\n' "$runtime_password" "$runtime_password" \
        | psql --no-psqlrc --set=ON_ERROR_STOP=1 \
            --command="\\password $WATCH_DB_RUNTIME_USER"
    unset owner_password runtime_password PGPASSFILE
}

rotate_password() {
    target_role="$1"
    current_password_file="$2"
    current_password_label="$3"
    comparison_password_file="$4"
    comparison_password_label="$5"
    verify_current="$6"

    require_identifier "$target_role" "교체 대상 데이터베이스 역할"
    owner_password="$(read_secret "$OWNER_PASSWORD_FILE" "데이터베이스 소유자")"
    if [ "$current_password_file" = "$OWNER_PASSWORD_FILE" ]; then
        current_password="$owner_password"
    else
        current_password="$(read_secret "$current_password_file" "$current_password_label")"
    fi
    if [ "$comparison_password_file" = "$OWNER_PASSWORD_FILE" ]; then
        comparison_password="$owner_password"
    else
        comparison_password="$(read_secret "$comparison_password_file" "$comparison_password_label")"
    fi
    new_password="$(read_secret "$NEW_PASSWORD_FILE" "새 데이터베이스")"
    if [ "$new_password" = "$current_password" ]; then
        printf '[staging-database-operation] 새 비밀값은 현재 비밀값과 달라야 합니다\n' >&2
        exit 1
    fi
    if [ "$new_password" = "$comparison_password" ]; then
        printf '[staging-database-operation] 새 비밀값은 다른 데이터베이스 역할의 비밀값과 달라야 합니다\n' >&2
        exit 1
    fi

    owner_pgpass="$(mktemp /tmp/watch-owner-pgpass.XXXXXX)"
    current_pgpass="$(mktemp /tmp/watch-current-pgpass.XXXXXX)"
    new_pgpass="$(mktemp /tmp/watch-new-pgpass.XXXXXX)"
    trap 'rm -f "$owner_pgpass" "$current_pgpass" "$new_pgpass"' EXIT HUP INT TERM
    write_pgpass "$owner_pgpass" "$PGUSER" "$owner_password"
    write_pgpass "$current_pgpass" "$target_role" "$current_password"
    write_pgpass "$new_pgpass" "$target_role" "$new_password"

    if [ "$verify_current" = "true" ]; then
        PGPASSFILE="$current_pgpass" psql --no-psqlrc --set=ON_ERROR_STOP=1 --quiet --tuples-only --no-align --username="$target_role" --command="SELECT 1" >/dev/null
    fi
    printf '%s\n%s\n' "$new_password" "$new_password" | PGPASSFILE="$owner_pgpass" psql --no-psqlrc --set=ON_ERROR_STOP=1 --command="\password $target_role"
    PGPASSFILE="$new_pgpass" psql --no-psqlrc --set=ON_ERROR_STOP=1 --quiet --tuples-only --no-align --username="$target_role" --command="SELECT 1" >/dev/null
    unset owner_password current_password comparison_password new_password
}

rotate_runtime_password() {
    require_database_environment
    require_runtime_role
    rotate_password "$WATCH_DB_RUNTIME_USER" "$RUNTIME_PASSWORD_FILE" "런타임 데이터베이스" "$OWNER_PASSWORD_FILE" "데이터베이스 소유자" true
}

rotate_owner_password() {
    require_database_environment
    rotate_password "$PGUSER" "$OWNER_PASSWORD_FILE" "데이터베이스 소유자" "$RUNTIME_PASSWORD_FILE" "런타임 데이터베이스" false
}

run_migrations() {
    require_database_environment
    require_runtime_role
    owner_password="$(read_secret "$OWNER_PASSWORD_FILE" "데이터베이스 소유자")"
    flyway_config="$(mktemp /tmp/watch-flyway.XXXXXX)"
    trap 'rm -f "$flyway_config"' EXIT HUP INT TERM

    {
        printf 'flyway.url=jdbc:postgresql://%s:%s/%s\n' "$PGHOST" "$PGPORT" "$PGDATABASE"
        printf 'flyway.user=%s\n' "$PGUSER"
        printf 'flyway.password=%s\n' "$owner_password"
        printf 'flyway.locations=filesystem:/flyway/sql,filesystem:/flyway/callbacks\n'
        printf 'flyway.placeholders.runtimeRole=%s\n' "$WATCH_DB_RUNTIME_USER"
        printf 'flyway.connectRetries=12\n'
        printf 'flyway.cleanDisabled=true\n'
        printf 'flyway.validateMigrationNaming=true\n'
        printf 'flyway.failOnMissingLocations=true\n'
    } > "$flyway_config"
    chmod 0600 "$flyway_config"

    flyway -configFiles="$flyway_config" migrate
    unset owner_password
}

case "${1:-}" in
    configure-runtime-role)
        configure_runtime_role
        ;;
    migrate)
        run_migrations
        ;;
    rotate-runtime-password)
        rotate_runtime_password
        ;;
    rotate-owner-password)
        rotate_owner_password
        ;;
    *)
        printf '[staging-database-operation] 지원하지 않는 작업입니다\n' >&2
        exit 64
        ;;
esac
