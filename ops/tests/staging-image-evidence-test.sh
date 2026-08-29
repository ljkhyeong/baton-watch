#!/usr/bin/env bash

set -euo pipefail
set +x
umask 077

TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly TEST_DIR
REPOSITORY_ROOT="$(cd "$TEST_DIR/../.." && pwd)"
readonly REPOSITORY_ROOT
TEMP_DIR="$(mktemp -d)"
readonly TEMP_DIR
readonly REVISION="1111111111111111111111111111111111111111"
readonly ARCHIVE_DIR="$TEMP_DIR/evidence/$REVISION"

cleanup() {
    rm -rf "$TEMP_DIR"
}
trap cleanup EXIT

fail() {
    printf '[staging-image-evidence-test] %s\n' "$1" >&2
    exit 1
}

state_key() {
    printf '%s' "$1" | tr '/:' '__'
}

write_image_state() {
    local tag="$1"
    local identifier="$2"
    local key
    key="$(state_key "$tag")"
    printf '%s\n' "$identifier" > "$TEMP_DIR/state/${key}.id"
    printf '%s\n' "$REVISION" > "$TEMP_DIR/state/${key}.revision"
}

mkdir -p "$TEMP_DIR/bin" "$TEMP_DIR/state"
cat > "$TEMP_DIR/bin/docker" <<'SH'
#!/bin/sh
set -eu

state_key() {
    printf '%s' "$1" | tr '/:' '__'
}

case "$1 $2 $3" in
    "image inspect --format")
        format="$4"
        tag="$5"
        key="$(state_key "$tag")"
        case "$format" in
            "{{.Id}}")
                cat "$WATCH_TEST_IMAGE_STATE/${key}.id"
                ;;
            *org.opencontainers.image.revision*)
                cat "$WATCH_TEST_IMAGE_STATE/${key}.revision"
                ;;
            *)
                exit 64
                ;;
        esac
        ;;
    "image save --output")
        output="$4"
        tag="$5"
        key="$(state_key "$tag")"
        printf '%s\t%s\t%s\n' "$tag" "$(cat "$WATCH_TEST_IMAGE_STATE/${key}.id")" "$(cat "$WATCH_TEST_IMAGE_STATE/${key}.revision")" > "$output"
        ;;
    "image load --input")
        input="$4"
        IFS="$(printf '\t')" read -r tag identifier revision < "$input"
        key="$(state_key "$tag")"
        printf '%s\n' "$identifier" > "$WATCH_TEST_IMAGE_STATE/${key}.id"
        printf '%s\n' "$revision" > "$WATCH_TEST_IMAGE_STATE/${key}.revision"
        ;;
    *)
        exit 64
        ;;
esac
SH
chmod 0700 "$TEMP_DIR/bin/docker"

write_image_state "baton-watch-database-operations:$REVISION" "sha256:$(printf 'a%.0s' {1..64})"
write_image_state "baton-watch-migrations:$REVISION" "sha256:$(printf 'b%.0s' {1..64})"
write_image_state "baton-watch:$REVISION" "sha256:$(printf 'c%.0s' {1..64})"

run_evidence() {
    PATH="$TEMP_DIR/bin:$PATH" WATCH_TEST_IMAGE_STATE="$TEMP_DIR/state" WATCH_IMAGE_REVISION="$REVISION" WATCH_IMAGE_ARCHIVE_DIR="$ARCHIVE_DIR" "$REPOSITORY_ROOT/ops/staging-image-evidence.sh" "$1"
}

run_evidence archive >/dev/null
run_evidence verify >/dev/null

if run_evidence archive >"$TEMP_DIR/rearchive-output" 2>&1; then
    fail "기존 이미지 보관 증거를 덮어썼습니다"
fi

write_image_state "baton-watch:$REVISION" "sha256:$(printf 'd%.0s' {1..64})"
if run_evidence verify >"$TEMP_DIR/mismatch-output" 2>&1; then
    fail "같은 태그의 다른 이미지 ID를 허용했습니다"
fi

run_evidence restore >/dev/null
run_evidence verify >/dev/null

printf 'tampered' >> "$ARCHIVE_DIR/runtime.tar"
if run_evidence verify >"$TEMP_DIR/checksum-output" 2>&1; then
    fail "변경된 이미지 아카이브를 허용했습니다"
fi

printf '[staging-image-evidence-test] 이미지 ID·아카이브 체크섬·복원 계약이 통과했습니다\n'
