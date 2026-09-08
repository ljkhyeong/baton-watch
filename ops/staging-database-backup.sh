#!/usr/bin/env bash

set -euo pipefail
set +x
# Docker CLI와 플러그인을 같은 별도 프로세스 그룹으로 실행한다.
set -m
umask 077

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR
TEMP_DIR="$(mktemp -d)"
readonly TEMP_DIR
restore_project=
archive_temp=
active_pid=

fail() {
    printf '[staging-database-backup] %s\n' "$1" >&2
    exit 1
}

run_command() {
    local status=0
    "$@" <&0 &
    active_pid=$!
    # 포그라운드 명령 대기와 달리 wait는 종료 신호를 받으면 trap을 즉시 실행한다.
    wait "$active_pid" || status=$?
    active_pid=
    return "$status"
}

restore_compose() {
    run_command docker compose --project-name "$restore_project" \
        --file "$SCRIPT_DIR/compose.restore-test.yml" "$@"
}

cleanup() {
    local status=$?
    trap - EXIT
    trap '' INT TERM HUP
    if [[ -n "$active_pid" ]]; then
        # 종료 요청 뒤에는 CLI 그룹을 중단하고, 복원 DB는 아래 down으로 별도 정리한다.
        kill -KILL -- "-$active_pid" 2>/dev/null || true
        wait "$active_pid" 2>/dev/null || true
        active_pid=
    fi
    if [[ -n "$restore_project" ]]; then
        if ! restore_compose down --volumes --timeout 5 >"$TEMP_DIR/cleanup.log" 2>&1; then
            printf '[staging-database-backup] 임시 복원 환경 정리 실패: %s\n' "$restore_project" >&2
            status=1
        fi
    fi
    if [[ -n "$archive_temp" ]]; then
        rm -f -- "$archive_temp"
    fi
    rm -rf -- "$TEMP_DIR"
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM HUP

create_backup() {
    local source_container="$1"
    local destination="$2"
    [[ ! -e "$destination" && ! -L "$destination" ]] || fail '기존 백업 파일은 덮어쓰지 않습니다'
    archive_temp="$(mktemp "$(dirname "$destination")/.watch-backup.XXXXXX")"
    # shellcheck disable=SC2016
    if ! run_command docker exec -- "$source_container" sh -c \
        'exec pg_dump --format=custom --no-owner --no-privileges --lock-wait-timeout=5s --username="${POSTGRES_USER:?}" "${POSTGRES_DB:?}"' \
        >"$archive_temp" 2>"$TEMP_DIR/dump.log"; then
        fail '백업 실패: 원본 PostgreSQL 컨테이너와 DB 상태를 확인하세요'
    fi
    [[ -s "$archive_temp" ]] || fail '백업 파일이 비어 있습니다'
    # 같은 디렉터리의 임시 파일을 연결해, 생성 중 생긴 기존 파일도 덮어쓰지 않는다.
    ln -- "$archive_temp" "$destination" || fail '백업 파일을 저장하지 못했습니다'
    printf '[staging-database-backup] 백업 파일 생성 완료; verify 명령으로 복원 시험이 필요합니다\n'
}

verify_backup() {
    local archive="$1"
    [[ -f "$archive" && -s "$archive" ]] || fail '읽을 수 있는 백업 파일이 필요합니다'
    restore_project="watch-restore-$$-${RANDOM}"
    printf '[staging-database-backup] 임시 복원 프로젝트: %s\n' "$restore_project" >&2
    if ! restore_compose up --detach --wait --wait-timeout 60 >"$TEMP_DIR/start.log" 2>&1; then
        fail '임시 PostgreSQL 시작 실패: Docker 자원과 이미지 다운로드 상태를 확인하세요'
    fi
    if ! restore_compose exec -T postgres pg_restore \
        --exit-on-error --single-transaction --no-owner --no-privileges \
        --username=watch_restore --dbname=baton_watch \
        <"$archive" >"$TEMP_DIR/restore.log" 2>&1; then
        fail '복원 실패: 백업 파일 손상 또는 1GiB 임시 저장 공간 초과 여부를 확인하세요'
    fi
    if ! restore_compose exec -T postgres psql \
        --no-psqlrc --set=ON_ERROR_STOP=1 --quiet --tuples-only --no-align \
        --username=watch_restore --dbname=baton_watch \
        <"$SCRIPT_DIR/verify-restored-database.sql" >"$TEMP_DIR/evidence" 2>"$TEMP_DIR/verify.log"; then
        fail '복원 데이터 확인 실패: 마이그레이션 이력 또는 백로그 요약이 올바르지 않습니다'
    fi
    cat "$TEMP_DIR/evidence"
}

case "${1:-}" in
    create)
        [[ $# == 3 ]] || fail '사용법: create <원본 PostgreSQL 컨테이너 ID> <새 백업 파일>'
        create_backup "$2" "$3"
        ;;
    verify)
        [[ $# == 2 ]] || fail '사용법: verify <백업 파일>'
        verify_backup "$2"
        ;;
    *)
        fail '사용법: staging-database-backup.sh create <컨테이너 ID> <새 파일> | verify <파일>'
        ;;
esac
