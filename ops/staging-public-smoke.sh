#!/usr/bin/env bash

set -euo pipefail
set +x
umask 077

readonly PREFIX="[staging-public-smoke]"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR
readonly URL_POLICY="$SCRIPT_DIR/staging-url-policy.py"

fail() {
    printf '%s %s\n' "$PREFIX" "$1" >&2
    exit 1
}

if [[ -z "${WATCH_PUBLIC_BASE_URL-}" ]]; then
    fail "WATCH_PUBLIC_BASE_URL이 필요합니다"
fi
if [[ "$WATCH_PUBLIC_BASE_URL" == *$'\n'* || "$WATCH_PUBLIC_BASE_URL" == *$'\r'* ]]; then
    fail "WATCH_PUBLIC_BASE_URL은 한 줄이어야 합니다"
fi
if ! command -v curl >/dev/null 2>&1; then
    fail "curl이 필요합니다"
fi
if ! command -v python3 >/dev/null 2>&1; then
    fail "python3가 필요합니다"
fi

if ! printf '%s' "$WATCH_PUBLIC_BASE_URL" | python3 "$URL_POLICY" origin; then
    fail "WATCH_PUBLIC_BASE_URL은 기본 HTTPS 포트의 DNS 오리진이어야 합니다"
fi

public_base_url="${WATCH_PUBLIC_BASE_URL%/}"
unset WATCH_PUBLIC_BASE_URL

audit_dir="$(mktemp -d "${TMPDIR:-/tmp}/baton-watch-public-smoke.XXXXXX")"
readonly audit_dir
readonly response_headers="$audit_dir/response.headers"
readonly response_body="$audit_dir/response.body"

cleanup() {
    rm -f "$response_headers" "$response_body"
    rmdir "$audit_dir"
}
trap cleanup EXIT

request() {
    local request_url="$1"
    shift

    : >"$response_headers"
    : >"$response_body"
    printf 'url = "%s"\n' "$request_url" | curl \
        --disable \
        --config - \
        --silent \
        --show-error \
        --noproxy '*' \
        --dump-header "$response_headers" \
        --output "$response_body" \
        --write-out '%{http_code} %{num_redirects}' \
        --proto '=https' \
        --tlsv1.2 \
        --connect-timeout 5 \
        --max-time 10 \
        --max-filesize 65536 \
        "$@"
}

# 사용법: expect_status <예상 상태> <요청 실패 문구> <상태 불일치 문구> <URL> [curl 인자...]
expect_status() {
    local expected="$1" request_failure="$2" status_failure="$3" result status_code redirect_count
    shift 3
    result="$(request "$@")" || fail "$request_failure"
    read -r status_code redirect_count <<<"$result"
    [[ "$status_code" == "$expected" && "$redirect_count" == "0" ]] || fail "$status_failure"
}

expect_status 200 "공개 상태 요청이 실패했습니다" \
    "공개 상태 요청은 리다이렉트 없이 HTTP 200이어야 합니다" \
    "$public_base_url/api/v1/system/status"
if [[ "$(grep -Eic '^CF-Ray:' "$response_headers" || true)" != "1" ]] \
        || ! grep -Eiq '^CF-Ray:[[:space:]]*[^[:space:]]+' "$response_headers"; then
    fail "공개 상태 응답의 CF-Ray 헤더가 없거나 올바르지 않습니다"
fi
if [[ "$(grep -Eic '^CF-Cache-Status:' "$response_headers" || true)" != "1" ]] \
        || ! grep -Eiq '^CF-Cache-Status:[[:space:]]*(DYNAMIC|BYPASS)[[:space:]]*$' "$response_headers"; then
    fail "공개 상태 응답의 CF-Cache-Status는 DYNAMIC 또는 BYPASS여야 합니다"
fi
if ! python3 "$SCRIPT_DIR/check-watch-status.py" "$response_body"; then
    fail "공개 상태 응답이 baton-watch의 UP 상태 JSON이 아닙니다"
fi

expect_status 401 "미인증 모니터 요청이 실패했습니다" \
    "미인증 모니터 요청은 리다이렉트 없이 HTTP 401이어야 합니다" \
    "$public_base_url/api/v1/resource-monitors/staging-auth-smoke" \
    --request PUT \
    --header 'Content-Type: application/json' \
    --data-binary '{'

expect_status 404 "인그레스 기타 경로 요청이 실패했습니다" \
    "인그레스 기타 경로는 리다이렉트 없이 HTTP 404여야 합니다" \
    "$public_base_url/api/v1/ingress-deny-smoke"

printf '%s 공개 상태·캐시·인증·기타 경로 검사가 통과했습니다\n' "$PREFIX"
