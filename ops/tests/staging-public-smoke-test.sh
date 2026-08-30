#!/usr/bin/env bash

set -euo pipefail
set +x
umask 077

TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly TEST_DIR
REPOSITORY_ROOT="$(cd "$TEST_DIR/../.." && pwd)"
readonly REPOSITORY_ROOT
readonly SCRIPT="$REPOSITORY_ROOT/ops/staging-public-smoke.sh"
TEMP_DIR="$(mktemp -d)"
readonly TEMP_DIR
readonly FAKE_BIN="$TEMP_DIR/bin"
readonly CURL_CALLS="$TEMP_DIR/curl-calls"

cleanup() {
    rm -rf "$TEMP_DIR"
}
trap cleanup EXIT

fail() {
    printf '[staging-public-smoke-test] %s\n' "$1" >&2
    exit 1
}

mkdir -p "$FAKE_BIN"
cat >"$FAKE_BIN/curl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

if [[ "${1-}" != "--disable" ]]; then
    printf 'curl 사용자 설정이 비활성화되지 않았습니다\n' >&2
    exit 71
fi
if [[ -z "${FAKE_CURL_CALLS-}" ]]; then
    printf 'curl 호출 기록 경로가 없습니다\n' >&2
    exit 72
fi

config="$(cat)"
header_file=
body_file=
arguments=" $* "
while (( $# > 0 )); do
    case "$1" in
        --dump-header)
            header_file="$2"
            shift 2
            ;;
        --output)
            body_file="$2"
            shift 2
            ;;
        *)
            shift
            ;;
    esac
done

if [[ -z "$header_file" || -z "$body_file" \
        || "$arguments" != *" --config - "* \
        || "$arguments" != *" --proto =https "* \
        || "$arguments" != *" --tlsv1.2 "* \
        || "$arguments" != *" --max-filesize 65536 "* \
        || "$arguments" == *" --location "* ]]; then
    printf '예상하지 않은 curl 요청 계약입니다\n' >&2
    exit 73
fi

case "$config" in
    'url = "https://watch.staging.example.com/api/v1/system/status"')
        printf 'status\n' >>"$FAKE_CURL_CALLS"
        printf '%b' "${FAKE_STATUS_HEADERS:-HTTP/2 200\r\nCF-Ray: test-ray-ICN\r\nCF-Cache-Status: DYNAMIC\r\n\r\n}" >"$header_file"
        printf '%s' "${FAKE_STATUS_BODY:-{\"service\":\"baton-watch\",\"status\":\"UP\"}}" >"$body_file"
        printf '%s %s' "${FAKE_STATUS_CODE:-200}" "${FAKE_STATUS_REDIRECTS:-0}"
        ;;
    'url = "https://watch.staging.example.com/api/v1/resource-monitors/staging-auth-smoke"')
        printf 'unauthorized\n' >>"$FAKE_CURL_CALLS"
        : >"$header_file"
        : >"$body_file"
        printf '%s %s' "${FAKE_UNAUTHORIZED_STATUS:-401}" "${FAKE_UNAUTHORIZED_REDIRECTS:-0}"
        ;;
    'url = "https://watch.staging.example.com/api/v1/ingress-deny-smoke"')
        printf 'catch-all\n' >>"$FAKE_CURL_CALLS"
        : >"$header_file"
        : >"$body_file"
        printf '%s %s' "${FAKE_CATCH_ALL_STATUS:-404}" "${FAKE_CATCH_ALL_REDIRECTS:-0}"
        ;;
    *)
        printf '예상하지 않은 공개 URL입니다\n' >&2
        exit 74
        ;;
esac
EOF
chmod +x "$FAKE_BIN/curl"

run_smoke() {
    : >"$CURL_CALLS"
    env \
        PATH="$FAKE_BIN:/usr/bin:/bin" \
        FAKE_CURL_CALLS="$CURL_CALLS" \
        WATCH_PUBLIC_BASE_URL="https://watch.staging.example.com" \
        "$@" \
        "$SCRIPT"
}

assert_calls() {
    local expected="$1"
    local actual

    actual="$(cat "$CURL_CALLS")"
    if [[ "$actual" != "$expected" ]]; then
        fail "curl 호출 순서가 예상과 다릅니다"
    fi
}

assert_failure() {
    local expected_calls="$1"
    shift
    local output

    if output="$(run_smoke "$@" 2>&1)"; then
        fail "실패해야 하는 공개 스모크가 성공했습니다"
    fi
    if [[ -z "$output" ]]; then
        fail "실패한 공개 스모크가 진단을 남기지 않았습니다"
    fi
    assert_calls "$expected_calls"
}

run_smoke >/dev/null
assert_calls $'status\nunauthorized\ncatch-all'
run_smoke FAKE_STATUS_HEADERS=$'HTTP/2 200\r\nCF-Ray: test-ray-ICN\r\nCF-Cache-Status: BYPASS\r\n\r\n' >/dev/null
assert_calls $'status\nunauthorized\ncatch-all'

assert_failure "status" FAKE_STATUS_CODE=301 FAKE_STATUS_REDIRECTS=0
assert_failure "status" FAKE_STATUS_BODY='not-json'
assert_failure "status" FAKE_STATUS_BODY='{"service":"other","status":"UP"}'
for cache_status in HIT MISS EXPIRED STALE UPDATING REVALIDATED; do
    assert_failure "status" \
        FAKE_STATUS_HEADERS=$'HTTP/2 200\r\nCF-Ray: test-ray-ICN\r\nCF-Cache-Status: '"$cache_status"$'\r\n\r\n'
done
assert_failure "status" FAKE_STATUS_HEADERS=$'HTTP/2 200\r\nCF-Ray: test-ray-ICN\r\n\r\n'
assert_failure "status" FAKE_STATUS_HEADERS=$'HTTP/2 200\r\nCF-Cache-Status: DYNAMIC\r\n\r\n'
assert_failure $'status\nunauthorized' FAKE_UNAUTHORIZED_STATUS=400
assert_failure $'status\nunauthorized\ncatch-all' FAKE_CATCH_ALL_STATUS=401
assert_failure "" WATCH_PUBLIC_BASE_URL='https://2130706433'

printf '[staging-public-smoke-test] 16개 사례와 curl 요청 계약이 통과했습니다\n'
