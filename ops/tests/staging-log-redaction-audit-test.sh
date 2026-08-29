#!/usr/bin/env bash

set -euo pipefail
set +x
umask 077

TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly TEST_DIR
REPOSITORY_ROOT="$(cd "$TEST_DIR/../.." && pwd)"
readonly REPOSITORY_ROOT
readonly SCRIPT="$REPOSITORY_ROOT/ops/staging-log-redaction-audit.sh"
TEMP_DIR="$(mktemp -d)"
readonly TEMP_DIR
readonly LOG_FILE="$TEMP_DIR/compose.log"
readonly FORBIDDEN_VALUES_FILE="$TEMP_DIR/forbidden-values"
readonly SECRET_ONE_FILE="$TEMP_DIR/secret-one"
readonly SECRET_TWO_FILE="$TEMP_DIR/secret-two"

readonly SECRET_ONE='database-owner-secret-0123456789'
readonly SECRET_TWO='watch-api-secret-01234567890123'
readonly TARGET_URL='https://target.example.com/private?token=hidden'
readonly RESOURCE_REFERENCE='role-resource-sensitive-123'
readonly PAYLOAD='{"resourceReference":"role-resource-sensitive-123"}'

cleanup() {
    rm -rf "$TEMP_DIR"
}
trap cleanup EXIT

fail() {
    printf '[staging-log-redaction-audit-test] %s\n' "$1" >&2
    exit 1
}

printf '%s\n' "$SECRET_ONE" >"$SECRET_ONE_FILE"
printf '%s\n' "$SECRET_TWO" >"$SECRET_TWO_FILE"
printf '%s\n' "$TARGET_URL" "$RESOURCE_REFERENCE" "$PAYLOAD" >"$FORBIDDEN_VALUES_FILE"

run_audit() {
    "$SCRIPT" \
        "$LOG_FILE" \
        "$FORBIDDEN_VALUES_FILE" \
        "$SECRET_ONE_FILE" \
        "$SECRET_TWO_FILE"
}

assert_safe_output() {
    local output="$1"

    for sensitive_value in \
        "$SECRET_ONE" \
        "$SECRET_TWO" \
        "$TARGET_URL" \
        "$RESOURCE_REFERENCE" \
        "$PAYLOAD" \
        'Bearer delivery-secret-0123456789' \
        'Authorization: Bearer'; do
        if [[ "$output" == *"$sensitive_value"* ]]; then
            fail "감사 결과가 민감 원문을 출력했습니다"
        fi
    done
}

assert_failure() {
    local leaked_value="$1"
    local output

    printf 'prefix %s suffix\n' "$leaked_value" >"$LOG_FILE"
    if output="$(run_audit 2>&1)"; then
        fail "노출이 있는 로그 감사가 성공했습니다"
    fi
    assert_safe_output "$output"
}

printf '%s\n' '정상적인 제한 결과와 상태만 있는 로그' >"$LOG_FILE"
success_output="$(run_audit)"
assert_safe_output "$success_output"

assert_failure "$SECRET_ONE"
assert_failure 'Authorization: Basic redacted'
assert_failure 'Bearer delivery-secret-0123456789'
assert_failure "$TARGET_URL"
assert_failure "$RESOURCE_REFERENCE"
assert_failure "$PAYLOAD"

printf '[staging-log-redaction-audit-test] 7개 사례와 비식별 출력 계약이 통과했습니다\n'
