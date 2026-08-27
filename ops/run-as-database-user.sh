#!/bin/sh

set -eu
set +x
umask 077

target_uid="$1"
target_gid="$2"
shift 2

copy_secret() {
    source_file="$1"
    target_file="$2"
    if [ ! -r "$source_file" ]; then
        printf '[run-as-database-user] 데이터베이스 비밀 파일을 읽을 수 없습니다\n' >&2
        exit 1
    fi
    cp "$source_file" "$target_file"
    chmod 0400 "$target_file"
    chown "$target_uid:$target_gid" "$target_file"
}

owner_secret_source="${WATCH_DB_OWNER_PASSWORD_FILE:-/run/secrets/postgres-owner-password}"
owner_secret_target=/tmp/watch-postgres-owner-password
copy_secret "$owner_secret_source" "$owner_secret_target"
export WATCH_DB_OWNER_PASSWORD_FILE="$owner_secret_target"

runtime_secret_source="${WATCH_DB_RUNTIME_PASSWORD_FILE:-/run/secrets/postgres-runtime-password}"
if [ -r "$runtime_secret_source" ]; then
    runtime_secret_target=/tmp/watch-postgres-runtime-password
    copy_secret "$runtime_secret_source" "$runtime_secret_target"
    export WATCH_DB_RUNTIME_PASSWORD_FILE="$runtime_secret_target"
fi

exec su-exec "$target_uid:$target_gid" "$@"
