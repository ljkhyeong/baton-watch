#!/usr/bin/env bash

set -euo pipefail
set +x
umask 077

readonly PREFIX="[staging-public-smoke]"

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

if ! python3 - "$WATCH_PUBLIC_BASE_URL" <<'PY'
import ipaddress
import re
import sys
from urllib.parse import urlsplit

value = sys.argv[1]
try:
    parsed = urlsplit(value)
    port = parsed.port
except ValueError:
    raise SystemExit(1)

host = parsed.hostname
if (
    len(value) > 2048
    or parsed.scheme != "https"
    or host is None
    or parsed.username is not None
    or parsed.password is not None
    or port not in (None, 443)
    or parsed.path not in ("", "/")
    or parsed.query
    or parsed.fragment
):
    raise SystemExit(1)

try:
    ipaddress.ip_address(host)
except ValueError:
    labels = host.split(".")
    if not labels or any(
        not re.fullmatch(r"[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?", label)
        for label in labels
    ):
        raise SystemExit(1)
else:
    raise SystemExit(1)
PY
then
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

if ! status_result="$(request "$public_base_url/api/v1/system/status")"; then
    fail "공개 상태 요청이 실패했습니다"
fi
read -r status_code redirect_count <<<"$status_result"
if [[ "$status_code" != "200" || "$redirect_count" != "0" ]]; then
    fail "공개 상태 요청은 리다이렉트 없이 HTTP 200이어야 합니다"
fi
if grep -Eiq '^CF-Cache-Status:[[:space:]]*HIT[[:space:]]*$' "$response_headers"; then
    fail "공개 상태 응답이 Cloudflare 캐시에서 제공됐습니다"
fi
if ! python3 - "$response_body" <<'PY'
import json
import sys

try:
    with open(sys.argv[1], encoding="utf-8") as response:
        payload = json.load(response)
except (OSError, UnicodeError, json.JSONDecodeError):
    raise SystemExit(1)

if not isinstance(payload, dict):
    raise SystemExit(1)
if payload.get("service") != "baton-watch" or payload.get("status") != "UP":
    raise SystemExit(1)
PY
then
    fail "공개 상태 응답이 baton-watch의 UP 상태 JSON이 아닙니다"
fi

if ! unauthorized_result="$(request \
        "$public_base_url/api/v1/resource-monitors/staging-auth-smoke" \
        --request PUT \
        --header 'Content-Type: application/json' \
        --data-binary '{')"; then
    fail "미인증 모니터 요청이 실패했습니다"
fi
read -r unauthorized_status unauthorized_redirects <<<"$unauthorized_result"
if [[ "$unauthorized_status" != "401" || "$unauthorized_redirects" != "0" ]]; then
    fail "미인증 모니터 요청은 리다이렉트 없이 HTTP 401이어야 합니다"
fi

if ! catch_all_result="$(request "$public_base_url/api/v1/ingress-deny-smoke")"; then
    fail "인그레스 기타 경로 요청이 실패했습니다"
fi
read -r catch_all_status catch_all_redirects <<<"$catch_all_result"
if [[ "$catch_all_status" != "404" || "$catch_all_redirects" != "0" ]]; then
    fail "인그레스 기타 경로는 리다이렉트 없이 HTTP 404여야 합니다"
fi

unset \
    public_base_url \
    status_result status_code redirect_count \
    unauthorized_result unauthorized_status unauthorized_redirects \
    catch_all_result catch_all_status catch_all_redirects
printf '%s 공개 상태·캐시·인증·기타 경로 검사가 통과했습니다\n' "$PREFIX"
