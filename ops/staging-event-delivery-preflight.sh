#!/usr/bin/env bash

set -euo pipefail
set +x
umask 077

readonly PREFIX="[staging-event-delivery-preflight]"

fail() {
    printf '%s %s\n' "$PREFIX" "$1" >&2
    exit 1
}

require_non_empty() {
    local name="$1"
    local value="${!name-}"
    if [[ -z "$value" ]]; then
        fail "$name is required"
    fi
    if [[ "$value" == *$'\n'* || "$value" == *$'\r'* ]]; then
        fail "$name must be a single line"
    fi
}

require_https_origin() {
    local name="$1"
    local value="$2"
    local authority
    local host

    if [[ ! "$value" =~ ^https://[A-Za-z0-9.-]+(:443)?$ ]]; then
        fail "$name must be an HTTPS origin on the default port without a path"
    fi
    authority="${value#https://}"
    host="${authority%:443}"
    require_dns_hostname "$name" "$host"
}

require_delivery_endpoint() {
    local value="$1"
    local authority
    local host

    if (( ${#value} > 2048 )) \
            || [[ ! "$value" =~ ^https://[A-Za-z0-9.-]+(:443)?/api/v1/internal/resource-health-events$ ]]; then
        fail "WATCH_EVENT_DELIVERY_ENDPOINT must be the public BATON receiver HTTPS URL"
    fi
    authority="${value#https://}"
    authority="${authority%%/*}"
    host="${authority%:443}"
    require_dns_hostname WATCH_EVENT_DELIVERY_ENDPOINT "$host"
}

require_dns_hostname() {
    local name="$1"
    local host="$2"
    local remaining="$host"
    local label

    if (( ${#host} > 253 )) \
            || [[ "$host" == .* || "$host" == *. ]] \
            || [[ "$host" =~ ^(0[xX][0-9A-Fa-f]+|[0-9]+)(\.(0[xX][0-9A-Fa-f]+|[0-9]+)){0,3}$ ]]; then
        fail "$name must use an unambiguous DNS hostname"
    fi

    while [[ -n "$remaining" ]]; do
        if [[ "$remaining" == *.* ]]; then
            label="${remaining%%.*}"
            remaining="${remaining#*.}"
        else
            label="$remaining"
            remaining=""
        fi
        if [[ ! "$label" =~ ^[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?$ ]]; then
            fail "$name must use an unambiguous DNS hostname"
        fi
    done
}

require_non_empty WATCH_PUBLIC_BASE_URL
require_non_empty WATCH_EVENT_DELIVERY_ENABLED
require_non_empty WATCH_EVENT_DELIVERY_ENDPOINT
require_non_empty WATCH_API_TOKEN
require_non_empty WATCH_EVENT_DELIVERY_TOKEN

if [[ "$WATCH_EVENT_DELIVERY_ENABLED" != "true" ]]; then
    fail "WATCH_EVENT_DELIVERY_ENABLED must be true for the staging exercise"
fi

watch_public_base_url="${WATCH_PUBLIC_BASE_URL%/}"
delivery_endpoint="$WATCH_EVENT_DELIVERY_ENDPOINT"
monitor_api_token="$WATCH_API_TOKEN"
delivery_token="$WATCH_EVENT_DELIVERY_TOKEN"

require_https_origin WATCH_PUBLIC_BASE_URL "$watch_public_base_url"
require_delivery_endpoint "$delivery_endpoint"

if [[ ! "$monitor_api_token" =~ ^[A-Za-z0-9._~+/-]{32,}=*$ ]]; then
    fail "WATCH_API_TOKEN must contain at least 32 non-padding RFC 6750 token68 characters"
fi
if [[ ! "$delivery_token" =~ ^[A-Za-z0-9._~-]{32,200}$ ]]; then
    fail "WATCH_EVENT_DELIVERY_TOKEN must be 32 to 200 URL-safe characters"
fi
if [[ "$monitor_api_token" == "$delivery_token" ]]; then
    fail "monitor API and event delivery tokens must be distinct"
fi

# The public checks below intentionally use no credentials. Do not let runtime
# secrets reach curl through its inherited environment.
unset \
    WATCH_PUBLIC_BASE_URL \
    WATCH_EVENT_DELIVERY_ENABLED \
    WATCH_EVENT_DELIVERY_ENDPOINT \
    WATCH_API_TOKEN \
    WATCH_EVENT_DELIVERY_TOKEN \
    monitor_api_token \
    delivery_token

if ! command -v curl >/dev/null 2>&1; then
    fail "curl is required"
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
    fail "WATCH public status request failed"
fi
if [[ "$watch_status" != "200" ]]; then
    fail "WATCH public status returned unexpected HTTP status $watch_status"
fi

# The body is deliberately malformed. HTTP 401 externally demonstrates that
# the receiver rejected it before a JSON deserialization error surfaced.
if ! receiver_status="$(curl_status \
        "$delivery_endpoint" \
        --request POST \
        --header 'Content-Type: application/json' \
        --data-binary '{"eventId":')"; then
    fail "BATON receiver authentication preflight request failed"
fi
if [[ "$receiver_status" != "401" ]]; then
    fail "BATON receiver must reject the unauthenticated preflight with HTTP 401, got $receiver_status"
fi

unset watch_public_base_url delivery_endpoint watch_status receiver_status
printf '%s public WATCH status and BATON receiver authentication checks passed\n' "$PREFIX"
