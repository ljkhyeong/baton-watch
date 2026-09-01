#!/bin/sh

set -eu
set +x
umask 077
export LC_ALL=C

readonly PREFIX="[scan-supply-chain]"
SCRIPT_DIR="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)"
readonly SCRIPT_DIR
readonly TRIVY_IMAGE="docker.io/aquasec/trivy:0.74.0@sha256:62b1e65e8869bc4b4c6aa4fa2b21595256c7c2f6018a9d9ad61caf87187c1969"

fail() {
    printf '%s %s\n' "$PREFIX" "$1" >&2
    exit 1
}

if [ "$#" -ne 6 ]; then
    fail "사용법: $0 <보고서 디렉터리> <부트 JAR> <데이터베이스 작업 이미지> <마이그레이션 이미지> <WATCH 이미지> <NGINX 이미지>"
fi

readonly OUTPUT_ARGUMENT="$1"
readonly JAR_ARGUMENT="$2"
readonly DATABASE_OPERATIONS_IMAGE="$3"
readonly MIGRATIONS_IMAGE="$4"
readonly RUNTIME_IMAGE="$5"
readonly GATEWAY_IMAGE="$6"

if [ ! -f "$JAR_ARGUMENT" ]; then
    fail "부트 JAR를 찾을 수 없습니다: $JAR_ARGUMENT"
fi
if [ -e "$OUTPUT_ARGUMENT" ]; then
    fail "보고서 디렉터리가 이미 존재합니다: $OUTPUT_ARGUMENT"
fi

mkdir -p "$(dirname -- "$OUTPUT_ARGUMENT")"
OUTPUT_PARENT="$(CDPATH='' cd -- "$(dirname -- "$OUTPUT_ARGUMENT")" && pwd)"
readonly OUTPUT_PARENT
OUTPUT_DIR="$OUTPUT_PARENT/$(basename -- "$OUTPUT_ARGUMENT")"
readonly OUTPUT_DIR

INPUT_DIR="$(mktemp -d)"
readonly INPUT_DIR
readonly JAR_DIR="$INPUT_DIR/jar"
readonly IMAGE_DIR="$INPUT_DIR/images"
readonly CACHE_DIR="$INPUT_DIR/cache"
WORK_OUTPUT_DIR=""
cleanup() {
    rm -rf "$INPUT_DIR"
    if [ -n "$WORK_OUTPUT_DIR" ]; then
        rm -rf "$WORK_OUTPUT_DIR"
    fi
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM
mkdir "$JAR_DIR" "$IMAGE_DIR" "$CACHE_DIR"
cp "$JAR_ARGUMENT" "$JAR_DIR/baton-watch.jar"

save_image() {
    image="$1"
    archive="$2"
    docker image inspect "$image" >/dev/null
    docker image save --output "$IMAGE_DIR/$archive" "$image"
}

save_image "$DATABASE_OPERATIONS_IMAGE" database-operations.tar
save_image "$MIGRATIONS_IMAGE" migrations.tar
save_image "$RUNTIME_IMAGE" runtime.tar
save_image "$GATEWAY_IMAGE" gateway.tar

WORK_OUTPUT_DIR="$(mktemp -d "${OUTPUT_DIR}.tmp.XXXXXX")"
chmod 0700 "$WORK_OUTPUT_DIR"

scan_rootfs() {
    docker run --rm \
        --volume "$WORK_OUTPUT_DIR:/reports" \
        --volume "$JAR_DIR:/inputs:ro" \
        --volume "$CACHE_DIR:/root/.cache/trivy" \
        "$TRIVY_IMAGE" rootfs \
        --cache-dir /root/.cache/trivy \
        --format cyclonedx \
        --output /reports/baton-watch.cdx.json \
        --scanners vuln \
        --severity HIGH,CRITICAL \
        --ignore-unfixed \
        --exit-code 1 \
        --skip-version-check \
        --no-progress \
        /inputs
}

scan_image_archive() {
    archive="$1"
    report="$2"
    docker run --rm \
        --volume "$WORK_OUTPUT_DIR:/reports" \
        --volume "$IMAGE_DIR:/inputs:ro" \
        --volume "$CACHE_DIR:/root/.cache/trivy" \
        "$TRIVY_IMAGE" image \
        --cache-dir /root/.cache/trivy \
        --format cyclonedx \
        --output "/reports/$report" \
        --scanners vuln \
        --severity HIGH,CRITICAL \
        --ignore-unfixed \
        --exit-code 1 \
        --skip-version-check \
        --no-progress \
        --input "/inputs/$archive"
}

scan_failed=false
run_scan() {
    label="$1"
    shift
    if ! "$@"; then
        printf '%s %s 검사에 실패했습니다\n' "$PREFIX" "$label" >&2
        scan_failed=true
    fi
}

run_scan "부트 JAR 취약점" scan_rootfs
run_scan "데이터베이스 작업 이미지 취약점" scan_image_archive database-operations.tar database-operations.cdx.json
run_scan "마이그레이션 이미지 취약점" scan_image_archive migrations.tar migrations.cdx.json
run_scan "WATCH 이미지 취약점" scan_image_archive runtime.tar runtime.cdx.json
run_scan "NGINX 이미지 취약점" scan_image_archive gateway.tar gateway.cdx.json

if ignored_licenses="$(python3 "$SCRIPT_DIR/check-runtime-licenses.py" "$WORK_OUTPUT_DIR")"; then
    run_scan "부트 JAR 라이선스" docker run --rm \
        --volume "$WORK_OUTPUT_DIR:/reports:ro" \
        "$TRIVY_IMAGE" sbom \
        --scanners license \
        --severity UNKNOWN,LOW,MEDIUM,HIGH,CRITICAL \
        --exit-code 1 \
        --ignored-licenses "$ignored_licenses" \
        --skip-version-check \
        --no-progress \
        /reports/baton-watch.cdx.json
else
    printf '%s 부트 JAR 라이선스 정책 검사에 실패했습니다\n' "$PREFIX" >&2
    scan_failed=true
fi

if [ "$scan_failed" = "true" ]; then
    for artifact in "$WORK_OUTPUT_DIR"/*; do
        if [ -f "$artifact" ]; then
            chmod 0600 "$artifact"
        fi
    done
    mv "$WORK_OUTPUT_DIR" "$OUTPUT_DIR"
    WORK_OUTPUT_DIR=""
    fail "취약점·라이선스 검사에 실패했습니다. 보고서: $OUTPUT_DIR"
fi

cp "$JAR_DIR/baton-watch.jar" "$WORK_OUTPUT_DIR/baton-watch.jar"
(
    cd "$WORK_OUTPUT_DIR"
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum baton-watch.jar ./*.cdx.json >SHA256SUMS
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 baton-watch.jar ./*.cdx.json >SHA256SUMS
    else
        fail "SHA-256 계산 도구가 없습니다"
    fi
)
chmod 0600 "$WORK_OUTPUT_DIR"/*
mv "$WORK_OUTPUT_DIR" "$OUTPUT_DIR"
WORK_OUTPUT_DIR=""
printf '%s 취약점·라이선스 검사와 SBOM 생성을 완료했습니다: %s\n' "$PREFIX" "$OUTPUT_DIR"
