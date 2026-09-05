#!/usr/bin/env bash

set -euo pipefail
set +x

fail() {
    printf '[monitor-diagnostics] %s\n' "$1" >&2
    exit 1
}

if [[ $# -lt 2 || $# -gt 3 ]]; then
    fail '사용법: staging-monitor-diagnostics.sh <PostgreSQL 컨테이너> <리소스 참조> [조회 건수: 기본 50, 최대 100]'
fi

readonly CONTAINER="$1"
readonly REFERENCE="$2"
readonly LIMIT="${3:-50}"
[[ "$REFERENCE" =~ ^[A-Za-z0-9._:-]{1,128}$ ]] || fail '리소스 참조 형식이 올바르지 않습니다'
[[ "$LIMIT" =~ ^([1-9]|[1-9][0-9]|100)$ ]] || fail '조회 건수는 1부터 100까지의 정수여야 합니다'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR

# 접속 대상은 지정한 컨테이너의 로컬 소켓으로 고정하고 비밀번호를 호스트로 가져오지 않는다.
# SQL과 COMMIT이 모두 성공한 뒤에만 결과를 출력한다.
if ! result="$(docker exec -i -- "$CONTAINER" sh -c '
    exec env PGCONNECT_TIMEOUT=5 psql \
        --host=/var/run/postgresql --port=5432 \
        --username="${POSTGRES_USER:?}" --dbname="${POSTGRES_DB:?}" \
        --no-psqlrc --no-password --quiet --tuples-only --no-align \
        --set=ON_ERROR_STOP=1 --set=resource_reference="$1" --set=row_limit="$2"
' sh "$REFERENCE" "$LIMIT" < "$SCRIPT_DIR/monitor-diagnostics.sql" 2>/dev/null)"; then
    fail '진단 조회 실패: PostgreSQL 컨테이너와 DB 상태를 확인하세요'
fi

printf '%s\n' "$result"
