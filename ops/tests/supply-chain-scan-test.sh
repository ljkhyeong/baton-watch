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
readonly REPORTS_DIR="$TEMP_DIR/reports"

cleanup() {
    rm -rf "$TEMP_DIR"
}
trap cleanup EXIT

fail() {
    printf '[supply-chain-scan-test] %s\n' "$1" >&2
    exit 1
}

mkdir "$TEMP_DIR/bin"
printf 'test-jar' >"$TEMP_DIR/baton-watch.jar"
cat >"$TEMP_DIR/bin/docker" <<'SH'
#!/bin/sh
set -eu

printf '%s\n' "$*" >>"$WATCH_TEST_DOCKER_CALLS"
case "$1 $2" in
    "image inspect")
        exit 0
        ;;
    "image save")
        printf '%s\n' "$5" >"$4"
        ;;
    "run --rm")
        report=""
        previous=""
        for argument in "$@"; do
            if [ "$previous" = "--output" ]; then
                report="$(basename "$argument")"
                break
            fi
            previous="$argument"
        done
        if [ -n "$report" ]; then
            printf '%s\n' '{"bomFormat":"CycloneDX","components":[{"purl":"pkg:maven/example/test@1.0","licenses":[{"license":{"id":"Apache-2.0"}}]}]}' >"$WATCH_TEST_REPORTS/$report"
            if [ "${WATCH_TEST_FAIL_REPORT-}" = "$report" ]; then
                exit 1
            fi
        fi
        ;;
    *)
        exit 64
        ;;
esac
SH
chmod 0700 "$TEMP_DIR/bin/docker"
cat >"$TEMP_DIR/bin/python3" <<'SH'
#!/bin/sh
set -eu

case "$1" in
    */check-runtime-licenses.py)
        printf '%s\n' 'Apache-2.0'
        ;;
    *)
        exec "$WATCH_TEST_REAL_PYTHON3" "$@"
        ;;
esac
SH
chmod 0700 "$TEMP_DIR/bin/python3"
REAL_PYTHON3="$(command -v python3)"
readonly REAL_PYTHON3

PATH="$TEMP_DIR/bin:$PATH" \
WATCH_TEST_DOCKER_CALLS="$TEMP_DIR/docker-calls" \
WATCH_TEST_REPORTS="$REPORTS_DIR" \
WATCH_TEST_REAL_PYTHON3="$REAL_PYTHON3" \
    "$REPOSITORY_ROOT/ops/scan-supply-chain.sh" \
    "$REPORTS_DIR" \
    "$TEMP_DIR/baton-watch.jar" \
    database-operations:test migrations:test runtime:test gateway:test >/dev/null

for report in \
    baton-watch.cdx.json database-operations.cdx.json migrations.cdx.json \
    runtime.cdx.json gateway.cdx.json baton-watch.jar SHA256SUMS; do
    if [ ! -s "$REPORTS_DIR/$report" ]; then
        fail "필수 산출물이 없습니다: $report"
    fi
done
if [ "$(grep -c '^image inspect ' "$TEMP_DIR/docker-calls")" -ne 4 ]; then
    fail "정확한 로컬 이미지 네 개를 검사하지 않았습니다"
fi
if [ "$(grep -c '^image save --output ' "$TEMP_DIR/docker-calls")" -ne 4 ]; then
    fail "이미지 아카이브 네 개를 만들지 않았습니다"
fi
if [ "$(grep -c '^run --rm ' "$TEMP_DIR/docker-calls")" -ne 6 ]; then
    fail "취약점 검사 다섯 개와 라이선스 검사 한 개를 실행하지 않았습니다"
fi
if ! grep -Fq -- '--ignored-licenses Apache-2.0' "$TEMP_DIR/docker-calls"; then
    fail "검증된 라이선스 제외 목록을 Trivy에 전달하지 않았습니다"
fi
if PATH="$TEMP_DIR/bin:$PATH" \
    WATCH_TEST_DOCKER_CALLS="$TEMP_DIR/docker-calls" \
    WATCH_TEST_REPORTS="$REPORTS_DIR" \
    WATCH_TEST_REAL_PYTHON3="$REAL_PYTHON3" \
    "$REPOSITORY_ROOT/ops/scan-supply-chain.sh" \
    "$REPORTS_DIR" "$TEMP_DIR/baton-watch.jar" \
    database-operations:test migrations:test runtime:test gateway:test \
    >"$TEMP_DIR/retry-output" 2>&1; then
    fail "기존 검사 보고서를 덮어썼습니다"
fi

failure_reports="$TEMP_DIR/failure-reports"
if PATH="$TEMP_DIR/bin:$PATH" \
    WATCH_TEST_DOCKER_CALLS="$TEMP_DIR/failure-docker-calls" \
    WATCH_TEST_REPORTS="$failure_reports" \
    WATCH_TEST_FAIL_REPORT="migrations.cdx.json" \
    WATCH_TEST_REAL_PYTHON3="$REAL_PYTHON3" \
    "$REPOSITORY_ROOT/ops/scan-supply-chain.sh" \
    "$failure_reports" "$TEMP_DIR/baton-watch.jar" \
    database-operations:test migrations:test runtime:test gateway:test \
    >"$TEMP_DIR/failure-output" 2>&1; then
    fail "취약점 검사 실패를 허용했습니다"
fi
if [ ! -s "$failure_reports/migrations.cdx.json" ] || [ -e "$failure_reports/SHA256SUMS" ]; then
    fail "실패 보고서를 보존하지 않았거나 완료 체크섬을 잘못 생성했습니다"
fi

printf '[supply-chain-scan-test] 공용 취약점·라이선스 검사 계약이 통과했습니다\n'
