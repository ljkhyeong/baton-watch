#!/bin/sh

set -eu

mkdir -p /licenses
while read -r _checksum file; do
    module_version="${file%/*}"
    module="${module_version%@*}"
    directory="$(go list -mod=readonly -m -f '{{.Dir}}' "$module")"
    if [ "${directory#/go/pkg/mod/}" != "$module_version" ]; then
        printf '라이선스 확인이 필요한 모듈 버전입니다: %s\n' "$module" >&2
        exit 1
    fi
    mkdir -p "/licenses/$module_version"
    cp "$directory/${file##*/}" "/licenses/$file"
done < /src/licenses.sha256

cd /licenses
sha256sum -c /src/licenses.sha256
