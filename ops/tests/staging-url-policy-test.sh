#!/usr/bin/env bash

set -euo pipefail
set +x

TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly TEST_DIR
REPOSITORY_ROOT="$(cd "$TEST_DIR/../.." && pwd)"
readonly REPOSITORY_ROOT
readonly POLICY="$REPOSITORY_ROOT/ops/staging-url-policy.py"

fail() {
    printf '[staging-url-policy-test] %s\n' "$1" >&2
    exit 1
}

assert_allowed() {
    local mode="$1"
    local value="$2"

    if ! printf '%s' "$value" | python3 "$POLICY" "$mode"; then
        fail "허용해야 하는 URL이 거부됐습니다"
    fi
}

assert_rejected() {
    local mode="$1"
    local value="$2"

    if printf '%s' "$value" | python3 "$POLICY" "$mode"; then
        fail "거부해야 하는 URL이 허용됐습니다"
    fi
}

assert_allowed origin 'https://watch.example.com'
assert_allowed origin 'https://watch.example.com:443/'
assert_allowed event-delivery-endpoint \
    'https://baton.example.com/api/v1/internal/resource-health-events'

for value in \
    'https://watch.example.com:' \
    'https://watch.example.com:/' \
    'https://watch.example.com:0443' \
    'https://watch.example.com:00443/' \
    'https://watch.example.com?' \
    'https://watch.example.com#' \
    'https://watch.example.com?#' \
    'https://09.09.09.09' \
    'https://99999999999.1' \
    'https://1.2.3.4.5' \
    'http://watch.staging.example.com' \
    'https://2130706433' \
    'https://0177.0.0.1' \
    'https://0x7f.0.0.1' \
    'https://127.1' \
    ' https://watch.example.com' \
    $'https://watch.example.com\thealth' \
    $'https://watch.example.com\n'; do
    assert_rejected origin "$value"
done

assert_rejected event-delivery-endpoint \
    'https://baton.example.com:0443/api/v1/internal/resource-health-events'
assert_rejected event-delivery-endpoint \
    'https://baton.example.com/api/v1/internal/resource-health-events?'
assert_rejected event-delivery-endpoint \
    'http://baton.staging.example.com/api/v1/internal/resource-health-events'
assert_rejected event-delivery-endpoint \
    'https://bad..example.com/api/v1/internal/resource-health-events'
assert_rejected event-delivery-endpoint \
    'https://-bad.example.com/api/v1/internal/resource-health-events'
assert_rejected event-delivery-endpoint \
    'https://0x7f.0.0.1/api/v1/internal/resource-health-events'

printf '[staging-url-policy-test] 27개 원문 URL 경계 사례가 통과했습니다\n'
