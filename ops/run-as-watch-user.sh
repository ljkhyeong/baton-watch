#!/bin/sh

set -eu
set +x
umask 077

readonly target_uid=10001
readonly target_gid=10001
readonly secret_directory=/run/watch-secrets
readonly datasource_secret=/run/secrets/spring.datasource.password
readonly api_token_secret=/run/secrets/watch.api-token
readonly event_delivery_token_secret=/run/secrets/watch.event-delivery.bearer-token

require_secret() {
    source_file="$1"
    if [ ! -f "$source_file" ] || [ -L "$source_file" ] || [ ! -r "$source_file" ]; then
        printf '[run-as-watch-user] WATCH 시작 비밀 파일을 읽을 수 없습니다\n' >&2
        exit 1
    fi
}

copy_secret() {
    source_file="$1"
    target_file="$2"
    rm -f "$target_file"
    cp "$source_file" "$target_file"
    chmod 0400 "$target_file"
    chown "$target_uid:$target_gid" "$target_file"
}

require_secret "$datasource_secret"
require_secret "$api_token_secret"

if [ -L "$secret_directory" ]; then
    printf '[run-as-watch-user] WATCH 비밀 복사 디렉터리를 사용할 수 없습니다\n' >&2
    exit 1
fi

mkdir -p "$secret_directory"
chown 0:0 "$secret_directory"
chmod 0700 "$secret_directory"

copy_secret "$datasource_secret" "$secret_directory/spring.datasource.password"
copy_secret "$api_token_secret" "$secret_directory/watch.api-token"
if [ -e "$event_delivery_token_secret" ] || [ -L "$event_delivery_token_secret" ]; then
    require_secret "$event_delivery_token_secret"
    copy_secret \
        "$event_delivery_token_secret" \
        "$secret_directory/watch.event-delivery.bearer-token"
fi
chmod 0500 "$secret_directory"
chown "$target_uid:$target_gid" "$secret_directory"

exec su-exec "$target_uid:$target_gid" "$@"
