#!/bin/sh
set -eu

manifest=${1:-ops/iana-registry-sha256.txt}
temporary_directory=$(mktemp -d "${TMPDIR:-/tmp}/baton-watch-iana.XXXXXX")

cleanup() {
  rm -f "$temporary_directory"/*
  rmdir "$temporary_directory"
}
trap cleanup EXIT
trap 'exit 1' HUP INT TERM

checksum() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

checked=0
while IFS=' ' read -r expected url; do
  case "$expected" in
    ''|'#'*) continue ;;
  esac
  if ! printf '%s\n' "$expected" | grep -Eq '^[0-9a-f]{64}$'; then
    printf '%s\n' 'IANA 체크섬 목록 형식이 올바르지 않습니다.' >&2
    exit 1
  fi
  case "$url" in
    https://www.iana.org/*) ;;
    *)
      printf '%s\n' '허용되지 않은 IANA 원본 URL입니다.' >&2
      exit 1
      ;;
  esac

  destination="$temporary_directory/registry-$checked"
  curl \
    --proto '=https' \
    --tlsv1.2 \
    --fail \
    --silent \
    --show-error \
    --retry 3 \
    --connect-timeout 10 \
    --max-time 30 \
    --output "$destination" \
    "$url"
  actual=$(checksum "$destination")
  if [ "$actual" != "$expected" ]; then
    printf '%s\n' 'IANA 레지스트리가 변경되었습니다. 주소 정책과 경계 테스트를 검토하세요.' >&2
    exit 1
  fi
  checked=$((checked + 1))
done < "$manifest"

if [ "$checked" -ne 3 ]; then
  printf '%s\n' 'IANA 체크섬 목록은 정확히 세 개의 원본을 포함해야 합니다.' >&2
  exit 1
fi

printf '%s\n' 'IANA 주소 레지스트리 체크섬이 현재 정책 스냅샷과 일치합니다.'
