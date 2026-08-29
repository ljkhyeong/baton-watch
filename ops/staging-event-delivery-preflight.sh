#!/usr/bin/env bash

set -euo pipefail
set +x
umask 077

readonly PREFIX="[staging-event-delivery-preflight]"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR
readonly URL_POLICY="$SCRIPT_DIR/staging-url-policy.py"

fail() {
    printf '%s %s\n' "$PREFIX" "$1" >&2
    exit 1
}

require_non_empty() {
    local name="$1"
    local value="${!name-}"
    if [[ -z "$value" ]]; then
        fail "$name 값이 필요합니다"
    fi
    if [[ "$value" == *$'\n'* || "$value" == *$'\r'* ]]; then
        fail "$name 값은 한 줄이어야 합니다"
    fi
}

require_https_origin() {
    local name="$1"
    local value="$2"

    if ! printf '%s' "$value" | python3 "$URL_POLICY" origin; then
        fail "$name 값은 경로가 없는 기본 포트의 HTTPS DNS 오리진이어야 합니다"
    fi
}

require_delivery_endpoint() {
    local value="$1"

    if ! printf '%s' "$value" | python3 "$URL_POLICY" event-delivery-endpoint; then
        fail "WATCH_EVENT_DELIVERY_ENDPOINT는 공개 BATON 수신기의 HTTPS URL이어야 합니다"
    fi
}

require_non_empty WATCH_PUBLIC_BASE_URL
require_non_empty WATCH_EVENT_DELIVERY_ENABLED
require_non_empty WATCH_EVENT_DELIVERY_ENDPOINT
require_non_empty WATCH_API_TOKEN
require_non_empty WATCH_EVENT_DELIVERY_TOKEN

if [[ "$WATCH_EVENT_DELIVERY_ENABLED" != "true" ]]; then
    fail "스테이징 검증에서는 WATCH_EVENT_DELIVERY_ENABLED가 true여야 합니다"
fi

if ! command -v python3 >/dev/null 2>&1; then
    fail "python3가 필요합니다"
fi

# shellcheck disable=SC2153
watch_public_base_url="${WATCH_PUBLIC_BASE_URL%/}"
delivery_endpoint="$WATCH_EVENT_DELIVERY_ENDPOINT"
monitor_api_token="$WATCH_API_TOKEN"
delivery_token="$WATCH_EVENT_DELIVERY_TOKEN"

unset \
    WATCH_PUBLIC_BASE_URL \
    WATCH_EVENT_DELIVERY_ENABLED \
    WATCH_EVENT_DELIVERY_ENDPOINT \
    WATCH_API_TOKEN \
    WATCH_EVENT_DELIVERY_TOKEN

require_https_origin WATCH_PUBLIC_BASE_URL "$watch_public_base_url"
require_delivery_endpoint "$delivery_endpoint"

if (( ${#monitor_api_token} > 200 )) \
        || [[ ! "$monitor_api_token" =~ ^[A-Za-z0-9._~+/-]{32,}=*$ ]]; then
    fail "WATCH_API_TOKEN은 패딩이 아닌 RFC 6750 token68 문자 32개 이상, 전체 200자 이하여야 합니다"
fi
if [[ ! "$delivery_token" =~ ^[A-Za-z0-9._~-]{32,200}$ ]]; then
    fail "WATCH_EVENT_DELIVERY_TOKEN은 URL에 안전한 문자 32~200개여야 합니다"
fi
if [[ "$monitor_api_token" == "$delivery_token" ]]; then
    fail "모니터 API 토큰과 이벤트 전달 토큰은 달라야 합니다"
fi

# 아래 공개 검사는 의도적으로 자격 증명을 사용하지 않습니다.
unset monitor_api_token delivery_token

if ! command -v curl >/dev/null 2>&1; then
    fail "curl이 필요합니다"
fi

curl_status() {
    local request_url="$1"
    shift

    printf 'url = "%s"\n' "$request_url" | curl \
        --disable \
        --config - \
        --silent \
        --noproxy '*' \
        --output /dev/null \
        --write-out '%{http_code}' \
        --proto '=https' \
        --tlsv1.2 \
        --connect-timeout 5 \
        --max-time 10 \
        "$@"
}

if ! watch_status="$(curl_status "$watch_public_base_url/api/v1/system/status")"; then
    fail "WATCH 공개 상태 요청이 실패했습니다"
fi
if [[ "$watch_status" != "200" ]]; then
    fail "WATCH 공개 상태 요청이 예상하지 않은 HTTP 상태 $watch_status를 반환했습니다"
fi

# 본문은 의도적으로 잘못된 형식입니다. HTTP 401은 JSON 역직렬화 오류가 드러나기
# 전에 수신기가 요청을 거부했음을 외부에서 증명합니다.
if ! receiver_status="$(curl_status \
        "$delivery_endpoint" \
        --request POST \
        --header 'Content-Type: application/json' \
        --data-binary '{"eventId":')"; then
    fail "BATON 수신기 인증 사전 요청이 실패했습니다"
fi
if [[ "$receiver_status" != "401" ]]; then
    fail "BATON 수신기는 미인증 사전 요청을 HTTP 401로 거부해야 하지만 $receiver_status를 반환했습니다"
fi

unset watch_public_base_url delivery_endpoint watch_status receiver_status
printf '%s 공개 WATCH 상태와 BATON 수신기 인증 검사가 통과했습니다\n' "$PREFIX"
