#!/bin/sh

set -eu
set +x
umask 077
export LC_ALL=C

readonly REVISION="${WATCH_IMAGE_REVISION:?WATCH_IMAGE_REVISION is required}"
readonly ARCHIVE_DIR="${WATCH_IMAGE_ARCHIVE_DIR:?WATCH_IMAGE_ARCHIVE_DIR is required}"
readonly TAB="$(printf '\t')"

fail() {
    printf '[staging-image-evidence] %s\n' "$1" >&2
    exit 1
}

require_revision() {
    if ! printf '%s' "$REVISION" | grep -Eq '^[0-9a-f]{40}$'; then
        fail "이미지 리비전은 전체 Git SHA여야 합니다"
    fi
}

image_tag() {
    case "$1" in
        database-operations)
            printf 'baton-watch-database-operations:%s' "$REVISION"
            ;;
        migrations)
            printf 'baton-watch-migrations:%s' "$REVISION"
            ;;
        runtime)
            printf 'baton-watch:%s' "$REVISION"
            ;;
        *)
            fail "지원하지 않는 이미지 종류입니다"
            ;;
    esac
}

archive_name() {
    printf '%s.tar' "$1"
}

sha256_file() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | awk '{print $1}'
    else
        fail "SHA-256 계산 도구가 없습니다"
    fi
}

inspect_image_id() {
    docker image inspect --format '{{.Id}}' "$1"
}

inspect_image_revision() {
    docker image inspect --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}' "$1"
}

require_local_image() {
    expected_tag="$1"
    expected_id="$2"
    if ! actual_id="$(inspect_image_id "$expected_tag")"; then
        fail "로컬 이미지 ID를 확인할 수 없습니다: $expected_tag"
    fi
    if ! actual_revision="$(inspect_image_revision "$expected_tag")"; then
        fail "로컬 이미지 리비전을 확인할 수 없습니다: $expected_tag"
    fi
    if [ "$actual_id" != "$expected_id" ]; then
        fail "로컬 이미지 ID가 보관 증거와 다릅니다: $expected_tag"
    fi
    if [ "$actual_revision" != "$REVISION" ]; then
        fail "로컬 이미지 OCI 리비전이 배포 리비전과 다릅니다: $expected_tag"
    fi
}

manifest_entry() {
    evidence_dir="$1"
    kind="$2"
    manifest="$evidence_dir/manifest.tsv"
    entry="$(
        awk -F '\t' -v kind="$kind" '
            $1 == "image" && $2 == kind {
                count += 1
                line = $0
            }
            END {
                if (count == 1) {
                    print line
                } else {
                    exit 1
                }
            }
        ' "$manifest"
    )" || fail "이미지 보관 명세 항목이 없거나 중복됐습니다: $kind"
    printf '%s' "$entry"
}

verify_archive_dir() {
    evidence_dir="$1"
    verify_local="$2"
    manifest="$evidence_dir/manifest.tsv"
    expected_revision_line="revision${TAB}${REVISION}"

    if [ ! -f "$manifest" ] || [ "$(sed -n '1p' "$manifest")" != "$expected_revision_line" ]; then
        fail "이미지 보관 명세의 리비전이 올바르지 않습니다"
    fi
    if [ "$(wc -l < "$manifest" | tr -d '[:space:]')" != "4" ]; then
        fail "이미지 보관 명세 항목 수가 올바르지 않습니다"
    fi

    for kind in database-operations migrations runtime; do
        entry="$(manifest_entry "$evidence_dir" "$kind")"
        IFS="$TAB" read -r record actual_kind tag image_id archive checksum extra <<EOF
$entry
EOF
        expected_tag="$(image_tag "$kind")"
        expected_archive="$(archive_name "$kind")"
        if [ "$record" != "image" ] || [ "$actual_kind" != "$kind" ] || [ "$tag" != "$expected_tag" ] || [ "$archive" != "$expected_archive" ] || [ -n "${extra:-}" ]; then
            fail "이미지 보관 명세 항목이 올바르지 않습니다: $kind"
        fi
        if ! printf '%s' "$image_id" | grep -Eq '^sha256:[0-9a-f]{64}$'; then
            fail "이미지 ID 형식이 올바르지 않습니다: $kind"
        fi
        if ! printf '%s' "$checksum" | grep -Eq '^[0-9a-f]{64}$'; then
            fail "이미지 아카이브 체크섬 형식이 올바르지 않습니다: $kind"
        fi
        archive_path="$evidence_dir/$archive"
        if [ ! -f "$archive_path" ] || [ "$(sha256_file "$archive_path")" != "$checksum" ]; then
            fail "이미지 아카이브 체크섬이 보관 명세와 다릅니다: $kind"
        fi
        if [ "$verify_local" = "true" ]; then
            require_local_image "$tag" "$image_id"
        fi
    done
}

archive_images() {
    if [ -e "$ARCHIVE_DIR" ]; then
        fail "이미지 보관 디렉터리가 이미 존재합니다"
    fi
    parent_dir="$(dirname "$ARCHIVE_DIR")"
    mkdir -p "$parent_dir"
    temp_dir="$(mktemp -d "${ARCHIVE_DIR}.tmp.XXXXXX")"
    trap 'rm -rf "$temp_dir"' EXIT HUP INT TERM
    manifest="$temp_dir/manifest.tsv"
    printf 'revision\t%s\n' "$REVISION" > "$manifest"

    for kind in database-operations migrations runtime; do
        tag="$(image_tag "$kind")"
        if ! image_id="$(inspect_image_id "$tag")"; then
            fail "배포 이미지를 확인할 수 없습니다: $tag"
        fi
        if ! image_revision="$(inspect_image_revision "$tag")"; then
            fail "배포 이미지 리비전을 확인할 수 없습니다: $tag"
        fi
        if [ "$image_revision" != "$REVISION" ]; then
            fail "배포 이미지 OCI 리비전이 Git SHA와 다릅니다: $tag"
        fi
        if ! printf '%s' "$image_id" | grep -Eq '^sha256:[0-9a-f]{64}$'; then
            fail "배포 이미지 ID 형식이 올바르지 않습니다: $tag"
        fi
        archive="$(archive_name "$kind")"
        docker image save --output "$temp_dir/$archive" "$tag"
        checksum="$(sha256_file "$temp_dir/$archive")"
        printf 'image\t%s\t%s\t%s\t%s\t%s\n' "$kind" "$tag" "$image_id" "$archive" "$checksum" >> "$manifest"
    done

    chmod 0600 "$manifest" "$temp_dir"/*.tar
    verify_archive_dir "$temp_dir" true
    mv "$temp_dir" "$ARCHIVE_DIR"
    trap - EXIT HUP INT TERM
    printf '[staging-image-evidence] 이미지 보관 증거를 생성했습니다: %s\n' "$ARCHIVE_DIR"
}

verify_images() {
    verify_archive_dir "$ARCHIVE_DIR" true
    printf '[staging-image-evidence] 이미지 보관 증거와 로컬 이미지가 일치합니다\n'
}

restore_images() {
    verify_archive_dir "$ARCHIVE_DIR" false
    for kind in database-operations migrations runtime; do
        docker image load --input "$ARCHIVE_DIR/$(archive_name "$kind")" >/dev/null
    done
    verify_archive_dir "$ARCHIVE_DIR" true
    printf '[staging-image-evidence] 보관된 이미지를 복원하고 검증했습니다\n'
}

require_revision
case "${1:-}" in
    archive)
        archive_images
        ;;
    verify)
        verify_images
        ;;
    restore)
        restore_images
        ;;
    *)
        printf '[staging-image-evidence] 지원하지 않는 작업입니다\n' >&2
        exit 64
        ;;
esac
