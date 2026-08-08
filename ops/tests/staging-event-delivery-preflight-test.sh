#!/usr/bin/env bash

set -euo pipefail
set +x
umask 077

readonly TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly REPOSITORY_ROOT="$(cd "$TEST_DIR/../.." && pwd)"
readonly SCRIPT="$REPOSITORY_ROOT/ops/staging-event-delivery-preflight.sh"
readonly TEMP_DIR="$(mktemp -d)"
readonly FAKE_BIN="$TEMP_DIR/bin"
readonly CURL_CALLS="$TEMP_DIR/curl-calls"

cleanup() {
    rm -rf "$TEMP_DIR"
}
trap cleanup EXIT

fail() {
    printf '[staging-event-delivery-preflight-test] %s\n' "$1" >&2
    exit 1
}

mkdir -p "$FAKE_BIN"
cat >"$FAKE_BIN/curl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

if [[ -n "${WATCH_PUBLIC_BASE_URL-}" \
        || -n "${WATCH_EVENT_DELIVERY_ENABLED-}" \
        || -n "${WATCH_EVENT_DELIVERY_ENDPOINT-}" \
        || -n "${WATCH_API_TOKEN-}" \
        || -n "${WATCH_EVENT_DELIVERY_TOKEN-}" ]]; then
    printf 'curl inherited a WATCH staging variable\n' >&2
    exit 70
fi
if [[ "${1-}" != "--disable" ]]; then
    printf 'curl user configuration was not disabled\n' >&2
    exit 71
fi
if [[ -z "${FAKE_CURL_CALLS-}" ]]; then
    printf 'curl call log was not configured\n' >&2
    exit 72
fi

readonly -a ACTUAL_ARGUMENTS=("$@")
readonly -a COMMON_ARGUMENTS=(
    --disable
    --config -
    --silent
    --noproxy '*'
    --output /dev/null
    --write-out '%{http_code}'
    --proto '=https'
    --tlsv1.2
    --connect-timeout 5
    --max-time 10
)
readonly WATCH_STATUS_CONFIG='url = "https://watch.staging.example.com/api/v1/system/status"'
readonly RECEIVER_CONFIG='url = "https://baton.staging.example.com/api/v1/internal/resource-health-events"'
readonly MALFORMED_RECEIVER_BODY='{"eventId":'

fail_curl_contract() {
    printf 'unexpected curl request contract\n' >&2
    exit 73
}

assert_arguments() {
    local -a expected=("$@")
    local index

    if (( ${#ACTUAL_ARGUMENTS[@]} != ${#expected[@]} )); then
        fail_curl_contract
    fi
    for (( index = 0; index < ${#expected[@]}; index++ )); do
        if [[ "${ACTUAL_ARGUMENTS[$index]}" != "${expected[$index]}" ]]; then
            fail_curl_contract
        fi
    done
}

config="$(cat)"
for argument in "${ACTUAL_ARGUMENTS[@]}"; do
    if [[ "$argument" == *"monitor-api-token-0123456789-abcdef"* \
            || "$argument" == *"delivery-token-0123456789-abcdefg"* ]]; then
        printf 'curl received a WATCH service token\n' >&2
        exit 74
    fi
done

if [[ "$config" == "$WATCH_STATUS_CONFIG" ]]; then
    assert_arguments "${COMMON_ARGUMENTS[@]}"
    printf 'watch\n' >>"$FAKE_CURL_CALLS"
    printf '%s' "${FAKE_WATCH_STATUS:-200}"
elif [[ "$config" == "$RECEIVER_CONFIG" ]]; then
    assert_arguments \
        "${COMMON_ARGUMENTS[@]}" \
        --request POST \
        --header 'Content-Type: application/json' \
        --data-binary "$MALFORMED_RECEIVER_BODY"
    printf 'receiver\n' >>"$FAKE_CURL_CALLS"
    printf '%s' "${FAKE_RECEIVER_STATUS:-401}"
else
    printf 'unexpected curl request\n' >&2
    exit 64
fi
EOF
chmod +x "$FAKE_BIN/curl"

run_preflight() {
    : >"$CURL_CALLS"
    env \
        PATH="$FAKE_BIN:/usr/bin:/bin" \
        FAKE_CURL_CALLS="$CURL_CALLS" \
        WATCH_PUBLIC_BASE_URL="https://watch.staging.example.com" \
        WATCH_EVENT_DELIVERY_ENABLED="true" \
        WATCH_EVENT_DELIVERY_ENDPOINT="https://baton.staging.example.com/api/v1/internal/resource-health-events" \
        WATCH_API_TOKEN="monitor-api-token-0123456789-abcdef" \
        WATCH_EVENT_DELIVERY_TOKEN="delivery-token-0123456789-abcdefg" \
        "$@" \
        "$SCRIPT"
}

assert_failure_contains() {
    local expected="$1"
    shift
    local output
    if output="$(run_preflight "$@" 2>&1)"; then
        fail "expected failure containing: $expected"
    fi
    if [[ "$output" != *"$expected"* ]]; then
        fail "failure did not contain: $expected"
    fi
    if [[ "$output" == *"monitor-api-token-0123456789-abcdef"* \
            || "$output" == *"delivery-token-0123456789-abcdefg"* ]]; then
        fail "failure output exposed a service token"
    fi
}

success_output="$(run_preflight)"
if [[ "$success_output" != *"checks passed"* ]]; then
    fail "successful preflight did not report completion"
fi
if [[ "$(cat "$CURL_CALLS")" != $'watch\nreceiver' ]]; then
    fail "successful preflight did not call WATCH then receiver exactly once"
fi

non_url_safe_monitor_output="$(run_preflight \
    WATCH_API_TOKEN="monitor:api:token:0123456789:abcdef")"
if [[ "$non_url_safe_monitor_output" != *"checks passed"* ]]; then
    fail "valid non-URL-safe monitor API token was rejected"
fi

assert_failure_contains \
    "must be distinct" \
    WATCH_EVENT_DELIVERY_TOKEN="monitor-api-token-0123456789-abcdef"
assert_failure_contains \
    "public BATON receiver HTTPS URL" \
    WATCH_EVENT_DELIVERY_ENDPOINT="http://baton.staging.example.com/api/v1/internal/resource-health-events"
assert_failure_contains \
    "unambiguous DNS hostname" \
    WATCH_EVENT_DELIVERY_ENDPOINT="https://bad..example.com/api/v1/internal/resource-health-events"
assert_failure_contains \
    "unambiguous DNS hostname" \
    WATCH_EVENT_DELIVERY_ENDPOINT="https://-bad.example.com/api/v1/internal/resource-health-events"
assert_failure_contains \
    "must be true" \
    WATCH_EVENT_DELIVERY_ENABLED="false"
assert_failure_contains \
    "unexpected HTTP status 503" \
    FAKE_WATCH_STATUS="503"
assert_failure_contains \
    "must reject the unauthenticated preflight with HTTP 401, got 400" \
    FAKE_RECEIVER_STATUS="400"

printf '[staging-event-delivery-preflight-test] 9 cases and curl request contract passed\n'
