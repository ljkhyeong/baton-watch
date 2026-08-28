# Cloudflare Tunnel 스테이징 배포

상태: 운영자용 실행 절차서이며, 저장소 산출물은 실제 배포의 증거가 아님

최종 수정일: 2026-08-29

## 목적과 현재 경계

이 실행 절차서는 현재 Mac을 `https://watch-staging.b4ton.com`의 단일 스테이징
오리진으로 준비합니다. 의도한 엣지 경로는 원격 관리형 Cloudflare Tunnel에서
Docker `watch-edge` 네트워크로 연결됩니다. 터널 오버레이를 사용하면 오리진은
호스트 포트를 공개하지 않습니다.

저장소에는 스테이징 Compose 정의와 이 절차가 들어 있지만, 아직 오리진에서
Cloudflare 계정 인증을 완료하지 않았고 터널 토큰도 설치하지 않았으며 실제
배포나 공개 HTTPS 스모크 테스트도 검증하지 않았습니다. 스테이징 오리진을
사용하려면 Mac의 전원이 켜져 있고, 절전 상태가 아니며, 네트워크에 연결된 채
Docker가 실행 중이어야 합니다. 운영 또는 고가용성 토폴로지가 아닙니다.

배포에는 다음 항목을 사용합니다.

- PostgreSQL과 WATCH용 [compose.staging.yml](../../compose.staging.yml)
- 호스트 포트를 공개하지 않는 경계를 유지하면서 `cloudflared`를 추가하는
  [compose.staging-tunnel.yml](../../compose.staging-tunnel.yml)
- 비밀값이 없는 환경 템플릿인
  [ops/staging.env.example](../../ops/staging.env.example)
- 로컬에서 빌드하고 이미지 ID·OCI 리비전·아카이브 SHA-256을 보관한
  `baton-watch-database-operations:<full-git-sha>` 데이터베이스 작업 이미지,
  `baton-watch-migrations:<full-git-sha>` Flyway 이미지,
  `baton-watch:<full-git-sha>` 런타임 이미지
- 운영자가 생성한 외부 PostgreSQL 볼륨 한 개
- Compose 비밀값으로 마운트하는 권한 모드 `0600` 데이터베이스·WATCH 비밀
  파일 세 개와, 권한 모드 `0700` 상위 디렉터리 안의 `0444` 터널 토큰 파일

이 스테이징 범위에서는 상태 변경 전달을 비활성화한 상태로 유지합니다. Compose
파일은 `WATCH_EVENT_DELIVERY_ENABLED=false`로 고정합니다. 이 실행 절차서를
따르는 동안 BATON 콜백이나 전달 토큰을 추가하지 마세요. 호환되는 BATON
수신기를 준비한 뒤에만 별도의
[공개 스테이징 전달 검증 실행 절차서](public-staging-event-delivery.md)를
사용하세요.

## 네트워크 및 영속성 불변 조건

두 Compose 파일을 모두 적용하면 다음 조건을 충족해야 합니다.

- PostgreSQL은 호스트 포트를 공개하지 않고 내부 `watch-db` 네트워크에만
  참여합니다.
- PostgreSQL 소유자 역할은 데이터베이스 초기화와 마이그레이션에만
  사용합니다. 역할 초기화 스크립트를 내장한 불변 데이터베이스 작업
  이미지의 일회성 `database-role-init`가 런타임 역할을 설정하고,
  일회성 `migrate`가 소유자 자격 증명으로 Flyway를 완료한 뒤에만
  WATCH가 런타임 역할로 시작합니다. 역할 초기화에 워크트리 바인드
  마운트를 사용하지 않습니다. 데이터베이스 작업·마이그레이션 컨테이너는 시작
  직후 `DAC_READ_SEARCH`로 Compose 비밀을 읽어 각자의 tmpfs로 복사한 뒤 UID 70과
  UID 65532로 즉시 권한을 낮춥니다. 이 초기 전환에만 `CHOWN`,
  `DAC_READ_SEARCH`, `SETGID`, `SETUID` capability를 사용하며 SQL과 Flyway 실행은
  비루트 주체가 담당합니다.
- WATCH 이미지는 기본적으로 UID/GID `10001`로 실행합니다. 스테이징에서는 시작
  래퍼만 root와 `CHOWN`, `DAC_READ_SEARCH`, `SETGID`, `SETUID` capability로
  데이터베이스 비밀번호와 API 토큰을 전용 `/run/watch-secrets` tmpfs에 복사한 뒤
  Java를 UID/GID `10001`의 PID 1로 실행합니다. 복사 뒤 디렉터리는 `0500`, 파일은
  `0400`이며 Java와 상태 점검 프로세스에는 capability가 남지 않습니다.
- WATCH에서 Flyway는 비활성화됩니다. 런타임 역할은 모니터 조회·삽입·갱신,
  시도 조회·삽입·보존 삭제, 결과 조회·삽입, 이벤트 조회·삽입·보존 삭제와
  지정된 전달 메타데이터 열 갱신만 허용받습니다. 불변 시도·결과 열과 이벤트 페이로드
  열은 갱신할 수 없습니다. `flyway_schema_history`를 읽거나 변경할 수 없고,
  `watch_health_change_event_backlog` 요약을 조회할 수는 있지만 직접 변경하거나
  보호된 트리거 함수를 직접 실행할 수 없습니다. `PUBLIC`과 런타임 역할의
  데이터베이스 `TEMPORARY` 권한도 회수합니다.
- WATCH는 호스트 포트를 공개하지 않고 `watch-db`와 `watch-edge`에 참여하며,
  해당 네트워크의 컨테이너에만 8080 포트를 노출합니다.
- 관리 서버는 WATCH 컨테이너 내부의 `127.0.0.1:8081`에 계속 바인딩되며,
  터널을 통해 접근할 수 없습니다.
- `cloudflared`는 `watch-edge`에만 참여하고 호스트 포트를 공개하지 않으며,
  `http://watch:8080`으로 WATCH에 접근합니다. 공식 이미지의 UID/GID `65532`가
  file-source bind mount를 읽을 수 있도록 터널 토큰만 `0444`로 두되, 호스트의
  상위 비밀 디렉터리는 운영자 소유 `0700`으로 유지합니다.
- Mac에는 인바운드 방화벽이나 라우터 포트 포워딩이 필요하지 않습니다. QUIC용
  아웃바운드 UDP 7844와 터널 대체 연결용 TCP 7844를 허용하세요. WATCH에는
  대상 점검을 위해 기존에 의도한 DNS 및 공개 HTTP/HTTPS 아웃바운드 접근도
  별도로 필요합니다.
- PostgreSQL 데이터는 `WATCH_POSTGRES_VOLUME_NAME`으로 지정한 외부 볼륨에
  저장합니다. 일상적인 종료 및 롤백 과정에서 해당 볼륨을 삭제해서는 안 됩니다.

기본 스테이징 Compose 파일도 호스트 포트를 공개하지 않습니다. 내부 진단에는
`docker compose exec` 또는 `watch-edge`의 일회성 컨테이너를 사용합니다. 기본
파일만으로는 공개 인그레스가 없으므로, 공개 스테이징 배포에는 항상 두 파일을
모두 사용해야 합니다.

## 최초 1회 Cloudflare 설정

원격 관리형 터널을 사용합니다. 인그레스의 신뢰할 수 있는 기준은 Cloudflare
대시보드/API입니다. 오리진 인증서, 터널 인증 정보 JSON 또는 로컬 인그레스
설정 파일을 이 저장소에 추가하지 마세요.

1. `b4ton.com` 영역을 관리할 수 있는 운영자 계정으로 인증합니다.
2. 전용 스테이징 터널을 생성하고 커넥터 토큰을 발급받습니다. 아래에 설명한
   비밀 파일에는 해당 토큰만 저장하세요.
3. 다음 순서대로 인그레스 규칙을 설정합니다.

   | 순서 | 호스트 이름 | 경로 | 서비스 |
   | --- | --- | --- | --- |
   | 1 | `watch-staging.b4ton.com` | `^/api/v1/system/status$` | `http://watch:8080` |
   | 2 | `watch-staging.b4ton.com` | `^/api/v1/resource-monitors/.*$` | `http://watch:8080` |
   | 3 | 기타 모든 요청 | 기타 모든 요청 | `http_status:404` |

   기타 모든 요청을 처리하는 규칙은 마지막에 두세요. `/actuator`, 관리 포트
   또는 제한 없는 호스트 이름을 WATCH로 라우팅하지 마세요.
4. 공개 호스트 이름이 터널을 향하는 프록시 DNS 경로를 생성하는지 확인합니다.
   Mac의 주소가 노출되어서는 안 됩니다.
5. `watch-staging.b4ton.com`의 캐시를 우회하는 Cloudflare 캐시 규칙을
   추가합니다. 상태 응답과 인증된 모니터 응답이 엣지 캐시에서 제공되어서는
   안 됩니다.
6. 배포 전에 `watch-staging.b4ton.com`의 엣지 인증서 상태가 Active인지
   확인합니다. 아래 공개 스모크 테스트는 정상적인 호스트 이름 및 신뢰 검증을
   완료해야 합니다. `curl -k`를 사용하거나 TLS 검증을 비활성화하지 마세요.

이 경로 앞에 Cloudflare Access를 추가하지 마세요. WATCH 상태 엔드포인트는
의도적으로 공개되어 있고 모니터 경로는 BATON-WATCH Bearer 계약을 사용합니다.
별도의 엣지 인증 흐름을 추가하면 외부에서 관찰되는 HTTP 계약이 달라집니다.

Cloudflare 설정, DNS, Active 인증서만으로는 오리진이 실행 중임을 입증할 수
없습니다. 내부 및 외부 스모크 테스트 증거를 모두 보관하세요.

## 공개 인바운드와 용량 승인 기준

WATCH 오리진은 요청·응답 헤더를 각각 8 KiB, 연결을 128개, 수락 대기를 32개,
워커 스레드를 최대 32개·최소 유휴 4개, 워커 큐를 64개로 고정합니다. 이 값에는
외부 설정보다 우선하는 Spring Boot 환경 후처리기 속성 소스를 사용하므로 환경
변수·시스템 속성·명령행 인수로 완화할 수 없습니다. 이 상한은 단일 오리진의 유한 자원 경계이며
지원 용량이나 요청 속도 제한이 아닙니다.

현재 기본 점검 설정은 단일 스케줄러 레인, 배치 크기 1, 요청당 전체 제한 시간
5초입니다. 이벤트 전달은 단일 레인에서 배치 2개를 직렬 처리합니다. 두 작업자는
각 외부 호출 직전에 한 건씩 점유하며, 기본 데이터베이스·HTTP 제한을 포함한 계산
예산은 점검 21초와 전달 42초로 60초 실행 예산 안에 들어갑니다. 이 계산은 지원
용량이나 SLO가 아니라 부하 시험을 시작하기 위한 보수적 상한입니다.

공개 배포 전에 다음 증거를 별도로 남기세요.

- 예상 활성 모니터 수, 분당 동기화 요청 수, 상태 변경 이벤트 폭주량을 포함한
  부하 시나리오와 승인된 지원 규모
- `baton_watch_check_inflight`, `baton_watch_check_schedule_delay_seconds`,
  `baton_watch_check_claimed_total`,
  `baton_watch_check_finalizations_total`, `baton_watch_check_lease_recoveries_total`,
  `baton_watch_event_delivery_inflight`, 제한된 결과별
  `baton_watch_event_delivery_duration_seconds`,
  `baton_watch_event_delivery_claimed_total`,
  `baton_watch_event_delivery_finalizations_total`,
  `baton_watch_event_delivery_lease_recoveries_total`,
  `baton_watch_event_delivery_backlog`,
  `baton_watch_event_delivery_oldest_age_seconds`,
  `baton_watch_database_clock_offset_seconds`의 정상 범위와 경보 임계치
- 최대 HTTP 제한 시간과 데이터베이스 지연을 포함한 부하에서 일정 지연과
  전달 백로그가 계속 증가하지 않고 회복되는지에 대한 결과
- 데이터베이스 연결 풀 포화, Spring `tasks.scheduled.execution` 오류와 기존
  저카디널리티 WATCH 메트릭을 연결한 외부 대시보드와 알림 전달 경로
- 완료 처리 실패, 만료 리스 회수와 데이터베이스 시계 편차에 대한 대시보드·경보를
  운영 승인 전에 구성하고 장애 주입으로 검증한 증거

현재 저장소에는 지원 규모, 외부 대시보드와 알림 임계치가 확정되어 있지 않으므로
이 증거 없이 운영 용량을 주장해서는 안 됩니다.

또한 대상 점검의 DNS·공개 HTTP/HTTPS 이그레스만 허용하고 사설·메타데이터·
플랫폼 예약 목적지를 네트워크 계층에서도 차단하는 인프라 정책이 승인되지
않았습니다. 이그레스 정책, 지원 규모와 SLO, Cloudflare 요청 속도 제한,
외부 대시보드·알림 전달 경로와 임계치가 모두 승인되고 장애 주입으로 검증되기
전에는 이 문서의 공개 Cloudflare 배포 단계를 실행하지 마세요.

Cloudflare에는 상태 경로와 인증된 모니터 경로를 구분한 요청 속도 제한을
설정하세요. 모니터 경로의 허용량은 BATON의 정상 최대 동기화량보다 높고 검증된
서버 처리량보다 낮아야 하며, 초과 요청은 터널과 WATCH에 도달하기 전에 `429`로
거부해야 합니다. 공개 상태 경로에는 별도의 더 낮은 제한을 적용합니다. 제한값,
적용 범위, 우회 목록과 `429` 외부 스모크 결과를 기록하고, 실제 BATON 요청과
상태 점검이 정상적으로 통과하는지도 함께 확인하세요. WATCH의 인증과 16 KiB
본문 제한, Tomcat 연결·대기열·워커 상한은 요청별 계약과 오리진 자원 경계일 뿐
요청 빈도 제한을 대신하지 않습니다.

## Mac 준비

배포할 정확한 커밋을 깨끗하게 체크아웃한 상태에서 실행합니다. 비밀값을 다루는
동안 셸 추적을 계속 비활성화하세요.

~~~bash
set -euo pipefail
set +x
umask 077
test -z "$(git status --porcelain)"
DEPLOY_SHA="$(git rev-parse --verify HEAD)"
test "$(printf '%s' "$DEPLOY_SHA" | wc -c | tr -d ' ')" = 40
export DEPLOY_SHA
export WATCH_IMAGE_REVISION="$DEPLOY_SHA"
export WATCH_IMAGE="baton-watch:${WATCH_IMAGE_REVISION}"
export WATCH_DATABASE_OPERATIONS_IMAGE="baton-watch-database-operations:${WATCH_IMAGE_REVISION}"
export WATCH_MIGRATION_IMAGE="baton-watch-migrations:${WATCH_IMAGE_REVISION}"
export STAGING_CONFIG_DIR="${HOME}/.config/baton-watch/staging"
export STAGING_ENV_FILE="${STAGING_CONFIG_DIR}/staging.env"
export STAGING_STATE_FILE="${STAGING_CONFIG_DIR}/staging-state.env"
export WATCH_IMAGE_ARCHIVE_DIR="${STAGING_CONFIG_DIR}/images/${DEPLOY_SHA}"
~~~

비밀값이 없는 환경 파일을 설치하고 비밀 디렉터리를 생성합니다. 여섯 파일을
모두 저장소 밖에 두세요. 별도의 상태 파일은 운영자 셸이 바뀌거나 저장소가
업데이트되어도 현재 활성 PostgreSQL 볼륨을 유지합니다. 값을 출력하거나 셸
히스토리 확장을 활성화하지 말고, 운영자가 통제하는 비밀 관리 시스템에서 비밀
파일 네 개의 값을 채우세요. WATCH API 토큰은 패딩이 아닌 RFC 6750
`token68` 문자를 32자 이상 포함하고 전체 길이는 200자 이하여야 합니다. 데이터베이스 소유자와
런타임 비밀번호는 각각 `[A-Za-z0-9._~-]` 문자 32~200개로 구성하고,
두 데이터베이스 비밀번호·WATCH API 토큰·터널 토큰은 모두 서로 다른
값으로 관리하세요.

~~~bash
install -d -m 0700 "$STAGING_CONFIG_DIR"
install -d -m 0700 "$STAGING_CONFIG_DIR/secrets"
install -m 0600 ops/staging.env.example "$STAGING_ENV_FILE"
if [[ ! -e "$STAGING_STATE_FILE" ]]; then
  STATE_TMP="$(mktemp "${STAGING_CONFIG_DIR}/staging-state.env.XXXXXX")"
  printf '%s\n' \
    'WATCH_POSTGRES_VOLUME_NAME=baton-watch-staging-postgres-data' \
    > "$STATE_TMP"
  chmod 0600 "$STATE_TMP"
  mv "$STATE_TMP" "$STAGING_STATE_FILE"
  unset STATE_TMP
fi
test -e "$STAGING_CONFIG_DIR/secrets/postgres-owner-password" || \
  install -m 0600 /dev/null "$STAGING_CONFIG_DIR/secrets/postgres-owner-password"
test -e "$STAGING_CONFIG_DIR/secrets/postgres-runtime-password" || \
  install -m 0600 /dev/null "$STAGING_CONFIG_DIR/secrets/postgres-runtime-password"
test -e "$STAGING_CONFIG_DIR/secrets/watch-api-token" || \
  install -m 0600 /dev/null "$STAGING_CONFIG_DIR/secrets/watch-api-token"
test -e "$STAGING_CONFIG_DIR/secrets/cloudflare-tunnel-token" || \
  install -m 0600 /dev/null "$STAGING_CONFIG_DIR/secrets/cloudflare-tunnel-token"
~~~

이 보호 절차는 없는 자리 표시자만 만들며 기존 비밀값을 절대 잘라내지 않습니다.
PostgreSQL 소유자 비밀번호는 데이터베이스 소유자 역할과, 런타임
비밀번호는 런타임 역할과 파일을 하나의 통제된 변경으로 함께 갱신하는
별도 절차를 통해서만 교체하세요. 파일만 바꾸면 초기화된 볼륨의 인증이
실패합니다. 두 역할의 권한과 비밀번호 교체를 하나의 공유 비밀처럼
처리하지 마세요.

비밀 관리 시스템이 각 값을 마지막 개행 문자 하나와 함께 기록한 뒤, 내용을
출력하지 않고 메타데이터와 파일이 비어 있지 않은지 검증합니다.

~~~bash
chmod 0600 "$STAGING_CONFIG_DIR/secrets/postgres-owner-password"
chmod 0600 "$STAGING_CONFIG_DIR/secrets/postgres-runtime-password"
chmod 0600 "$STAGING_CONFIG_DIR/secrets/watch-api-token"
chmod 0444 "$STAGING_CONFIG_DIR/secrets/cloudflare-tunnel-token"
test -s "$STAGING_CONFIG_DIR/secrets/postgres-owner-password"
test -s "$STAGING_CONFIG_DIR/secrets/postgres-runtime-password"
test -s "$STAGING_CONFIG_DIR/secrets/watch-api-token"
test -s "$STAGING_CONFIG_DIR/secrets/cloudflare-tunnel-token"
test -O "$STAGING_CONFIG_DIR/secrets/postgres-owner-password"
test -O "$STAGING_CONFIG_DIR/secrets/postgres-runtime-password"
test -O "$STAGING_CONFIG_DIR/secrets/watch-api-token"
test -O "$STAGING_CONFIG_DIR/secrets/cloudflare-tunnel-token"
test -r "$STAGING_CONFIG_DIR/secrets/postgres-owner-password"
test -r "$STAGING_CONFIG_DIR/secrets/postgres-runtime-password"
test -r "$STAGING_CONFIG_DIR/secrets/watch-api-token"
test -r "$STAGING_CONFIG_DIR/secrets/cloudflare-tunnel-token"
for SECRET_FILE in \
  "$STAGING_CONFIG_DIR/secrets/postgres-owner-password" \
  "$STAGING_CONFIG_DIR/secrets/postgres-runtime-password" \
  "$STAGING_CONFIG_DIR/secrets/watch-api-token" \
  "$STAGING_CONFIG_DIR/secrets/cloudflare-tunnel-token"; do
  test -f "$SECRET_FILE"
  test ! -L "$SECRET_FILE"
done
unset SECRET_FILE
test -O "$STAGING_ENV_FILE"
test -O "$STAGING_STATE_FILE"
test -r "$STAGING_ENV_FILE"
test -r "$STAGING_STATE_FILE"
stat -f '%Lp %N' "$STAGING_CONFIG_DIR/secrets/postgres-owner-password"
stat -f '%Lp %N' "$STAGING_CONFIG_DIR/secrets/postgres-runtime-password"
stat -f '%Lp %N' "$STAGING_CONFIG_DIR/secrets/watch-api-token"
stat -f '%Lp %N' "$STAGING_CONFIG_DIR/secrets/cloudflare-tunnel-token"
stat -f '%Lp %N' "$STAGING_CONFIG_DIR/secrets"
stat -f '%Lp %N' "$STAGING_ENV_FILE"
stat -f '%Lp %N' "$STAGING_STATE_FILE"
for DATABASE_SECRET_FILE in \
  "$STAGING_CONFIG_DIR/secrets/postgres-owner-password" \
  "$STAGING_CONFIG_DIR/secrets/postgres-runtime-password"; do
  DATABASE_SECRET_VALUE=
  DATABASE_SECRET_EXTRA=
  if [ ! -r "$DATABASE_SECRET_FILE" ] || ! {
    IFS= read -r DATABASE_SECRET_VALUE \
      && ! IFS= read -r DATABASE_SECRET_EXTRA \
      && [ -z "$DATABASE_SECRET_EXTRA" ]
  } < "$DATABASE_SECRET_FILE"; then
    printf '%s\n' \
      '데이터베이스 비밀 파일은 마지막 줄바꿈이 있는 정확히 한 줄이어야 합니다' >&2
    exit 1
  fi
  printf '%s' "$DATABASE_SECRET_VALUE" \
    | grep -Eq '^[A-Za-z0-9._~-]{32,200}$'
done
unset DATABASE_SECRET_FILE DATABASE_SECRET_VALUE DATABASE_SECRET_EXTRA
~~~

데이터베이스·WATCH 비밀 세 개와 환경·상태 파일의 `stat` 출력은 `600`, 터널
토큰은 `444`, 상위 비밀 디렉터리는 `700`으로 시작해야 합니다. 터널 토큰의
호스트 접근은 파일 자체가 아니라 운영자만 탐색할 수 있는 `0700` 상위 디렉터리가
차단합니다. Compose file source는 호스트 파일을 bind mount하므로 컨테이너 UID를
위한 `uid`, `gid`, `mode` 선언으로 이 권한을 대신할 수 없습니다. 환경 템플릿 파일에는
의도적으로 이미지 리비전, 파일 경로, 데이터베이스 식별자·초기 볼륨 이름,
제한이 설정된 애플리케이션 설정만 들어 있습니다. 활성 볼륨은 상태 파일의
비밀값이 아닌 할당 하나로 선택하며, 셸에 내보낸 값이 환경 템플릿의 초기 값보다
우선합니다. 두 파일 중 어느 것도 셸에서
실행하지 않고 해당 값을 읽어 검증합니다. 데이터베이스 비밀 검사는 파일
값을 터미널에 출력하지 않습니다. 각 파일은 마지막 줄바꿈이 있는 정확히 한 줄만
허용하며 빈 둘째 줄이나 마지막 줄바꿈이 없는 추가 내용도 거부합니다. 일회성 역할
초기화와 마이그레이션 스크립트도 같은 문법과 파일 경계를 다시 검증합니다.

WATCH API 토큰이나 터널 토큰을 교체할 때는 같은 `secrets` 디렉터리에 새 일반
파일을 만들고 각각 `0600`, `0444`를 적용한 뒤 원래 경로로 원자적으로 바꾸세요.
symlink나 기존 파일에 대한 제자리 덮어쓰기는 허용하지 않습니다. bind mount는 이전
inode를 계속 참조할 수 있으므로 교체 뒤에는 `restart`가 아니라
`staging_compose up -d --no-deps --force-recreate watch` 또는
`staging_compose up -d --no-deps --force-recreate cloudflared`를 실행하고 상태 점검을
다시 통과시켜야 합니다.

~~~bash
test "$(wc -l < "$STAGING_STATE_FILE" | tr -d ' ')" = 1
STATE_CONTENT="$(< "$STAGING_STATE_FILE")"
[[ "$STATE_CONTENT" =~ ^WATCH_POSTGRES_VOLUME_NAME=[a-zA-Z0-9][a-zA-Z0-9_.-]+$ ]]
WATCH_POSTGRES_VOLUME_NAME="${STATE_CONTENT#WATCH_POSTGRES_VOLUME_NAME=}"
export WATCH_POSTGRES_VOLUME_NAME
unset STATE_CONTENT
test "$(grep -Ec '^WATCH_DB_RUNTIME_USER=[a-z_][a-z0-9_]{0,62}$' \
  "$STAGING_ENV_FILE")" = 1
WATCH_DB_RUNTIME_USER="$(sed -n 's/^WATCH_DB_RUNTIME_USER=//p' \
  "$STAGING_ENV_FILE")"
export WATCH_DB_RUNTIME_USER
~~~

셸의 `WATCH_IMAGE_REVISION`은 배포할 깨끗한 커밋을 선택하고, 상태 파일은 Git에
저장하지 않은 영속 데이터베이스 볼륨을 선택합니다. 환경 파일에서 읽은
`WATCH_DB_RUNTIME_USER`는 비밀값이 아니며 아래 실행 중 권한 검증에만 사용합니다.
HikariCP 설정은 최대 풀 1~32, 최소 유휴 0~32, 연결·검증 250~30000ms,
유휴 10000~1800000ms, 최대 수명 30000~3600000ms, 생존 확인
30000~1800000ms, 초기화 실패 1~30000ms만 허용합니다. 최소 유휴는 최대 풀
이하, 검증은 연결보다 짧게 설정하세요. 가변 풀의 유휴 제한은 최대 수명보다
1000ms 이상 짧게, 생존 확인은 최대 수명보다 짧게 설정하세요. pgJDBC
연결·로그인·취소는 1~30초, 소켓은 1~120초이고
`WATCH_DB_TCP_KEEP_ALIVE`는 명확성을 위해 `true` 또는 `false`를 사용하세요.
`SPRING_DATASOURCE_URL`은 `jdbc:postgresql://` 계층형 형식으로 지정하고 URL
쿼리 매개변수를 붙이지 마세요. pgJDBC 제한은 검증되는 `WATCH_DB_*` 설정으로만
변경합니다.

외부 데이터베이스 볼륨은 한 번만 생성하고, 이후 매 배포 전에 검사합니다.

~~~bash
docker volume create "$WATCH_POSTGRES_VOLUME_NAME"
docker volume inspect "$WATCH_POSTGRES_VOLUME_NAME"
~~~

## 정확한 로컬 리비전 빌드

다이제스트로 고정된 PostgreSQL과 Cloudflare Tunnel 이미지는 Compose 정의를
정본으로 삼아 가져옵니다. Flyway를 포함한 빌드 기반 이미지는 Dockerfile의
다이제스트 고정값을 `docker build --pull`이 가져옵니다. 데이터베이스
작업·마이그레이션·WATCH 이미지는 세 개 모두 `pull_policy: never`를 사용하므로
배포 중에 로컬에서 빌드한 SHA 태그 이미지를 레지스트리 이미지로 몰래 대체할 수
없습니다.

모든 작업에서 터널 오버레이를 사용하도록 헬퍼 하나를 먼저 정의합니다.

~~~bash
staging_compose() {
  docker compose \
    --env-file "$STAGING_ENV_FILE" \
    -f compose.staging.yml \
    -f compose.staging-tunnel.yml \
    "$@"
}
~~~

~~~bash
VERIFY_RUN_COUNT="$(gh run list --repo ljkhyeong/baton-watch \
  --workflow verify.yml --commit "$DEPLOY_SHA" --status success --limit 20 \
  --json headSha --jq 'length')"
test "$VERIFY_RUN_COUNT" -ge 1
./gradlew clean test :bootstrap:verifyBootJarLicense --no-daemon --no-build-cache
staging_compose pull postgres cloudflared
docker build --pull --target database-operations \
  --build-arg "OCI_REVISION=${DEPLOY_SHA}" \
  --tag "$WATCH_DATABASE_OPERATIONS_IMAGE" \
  .
docker build --pull --target migrations \
  --build-arg "OCI_REVISION=${DEPLOY_SHA}" \
  --tag "$WATCH_MIGRATION_IMAGE" \
  .
docker build --pull --target runtime \
  --build-arg "OCI_REVISION=${DEPLOY_SHA}" \
  --tag "$WATCH_IMAGE" \
  .
EXPECTED_LICENSE_DIGEST="$(shasum -a 256 LICENSE | awk '{print $1}')"
for BUILT_IMAGE in \
  "$WATCH_DATABASE_OPERATIONS_IMAGE" \
  "$WATCH_MIGRATION_IMAGE" \
  "$WATCH_IMAGE"; do
  test "$(docker image inspect "$BUILT_IMAGE" \
    --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}')" = \
    "$DEPLOY_SHA"
  test "$(docker image inspect "$BUILT_IMAGE" \
    --format '{{ index .Config.Labels "org.opencontainers.image.source" }}')" = \
    'https://github.com/ljkhyeong/baton-watch'
  test "$(docker image inspect "$BUILT_IMAGE" \
    --format '{{ index .Config.Labels "org.opencontainers.image.version" }}')" = \
    '0.1.0-SNAPSHOT'
  test "$(docker image inspect "$BUILT_IMAGE" \
    --format '{{ index .Config.Labels "org.opencontainers.image.licenses" }}')" = \
    'Apache-2.0'
  BUILT_LICENSE_DIGEST="$(docker run --rm --entrypoint sha256sum \
    "$BUILT_IMAGE" /usr/share/licenses/baton-watch/LICENSE | awk '{print $1}')"
  test "$BUILT_LICENSE_DIGEST" = "$EXPECTED_LICENSE_DIGEST"
done
unset BUILT_IMAGE BUILT_LICENSE_DIGEST EXPECTED_LICENSE_DIGEST
./ops/tests/staging-database-operation-postgres-test.sh
./ops/staging-image-evidence.sh archive
./ops/staging-image-evidence.sh verify
~~~

이미지를 빌드하기 전에 정확한 SHA에 대한 GitHub `검증` 워크플로가 성공했고
전체 로컬 테스트 작업도 통과해야 합니다. `검증`은 공식 SHA-256으로
고정된 Gradle Wrapper, Gradle 의존성 검증 메타데이터, 전체 SHA로 고정된
GitHub Actions, 명시적 허용 라이선스와 `HIGH` 이상 취약점을 적용한 변경 의존성
검토, CodeQL, ShellCheck, PostgreSQL 통합 증거,
`staging-compose-policy-test.sh`, `staging-image-evidence-test.sh`,
`staging-database-operation-test.sh`,
`staging-event-delivery-preflight-test.sh`와 실제 PostgreSQL 역할·마이그레이션,
비루트 최종 WATCH 이미지 기동과 상태 응답 스모크를 검증합니다. 세 이미지의 OCI
레이블과 Apache-2.0 전문도 저장소 파일과 대조합니다. Trivy는 독립 부트 JAR과
세 이미지의 CycloneDX SBOM 네 개를 만들고 부트 JAR의 라이선스를 명시적 허용
목록으로 검사하며, 수정 가능한 `HIGH`·`CRITICAL`
취약점이 있으면 실패합니다. 검증 산출물은 실행 가능한 JAR, SBOM 네 개와
체크섬 목록이며 14일 동안 보관합니다. `main` 푸시에서는 이 파일 묶음에 GitHub
출처 증명을 추가합니다. 현재 워크플로는 컨테이너 이미지 자체를 출처 증명 대상으로
삼지 않으므로 이미지 증명으로 해석하지 마세요. 호스트 Gradle 실행은 전체 테스트와
부트 JAR 라이선스 검증을 소유하고, 최종 WATCH Docker 이미지 빌드는 이미지에 포함할
실행 가능한 부트 JAR 생성을 소유합니다.
로컬 실제 PostgreSQL 스모크는 Flyway V1~V4, 허용된 런타임 DML, 불변 시도·결과·
이벤트 페이로드 열 갱신 거부와 비루트 WATCH 기동을 함께 확인합니다.
Gradle·GitHub Actions·Docker 의존성은 주간 Dependabot 점검 대상이지만,
업데이트 PR은 자동 배포하지 말고 같은 검증을 통과시켜야 합니다. 변경
사항이 남아 있는 작업 트리,
`latest`, 축약 SHA 또는 다른 리비전에서 빌드한 이미지를 배포하지 마세요.
`archive`는 같은 리비전의 기존 보관 디렉터리를 덮어쓰지 않습니다. 보관된
`manifest.tsv`와 세 아카이브는 태그만으로는 증명할 수 없는 실제 이미지 ID와
체크섬을 롤백 기간 동안 유지합니다.

어떤 항목도 시작하기 전에 병합된 모델을 검증합니다.

~~~bash
staging_compose config --quiet
staging_compose config
~~~

렌더링된 `postgres`, `database-role-init`, `migrate`, `watch`, `cloudflared`
서비스를 검사합니다. 어느 서비스에도
`ports` 항목이 있어서는 안 됩니다. 렌더링된 WATCH 환경은
`WATCH_EVENT_DELIVERY_ENABLED: "false"`, `SPRING_FLYWAY_ENABLED: "false"`를
유지해야 하며, 비밀값 내용이 나타나서는 안 됩니다.

## 업데이트 전 백업

최초 빈 배포에는 백업할 내용이 없습니다. 이후 모든 업데이트 전에는 기존
데이터베이스가 정상인 동안 권한 모드 `0600` 논리 백업을 생성합니다. 이 명령은
컨테이너 내부에서 PostgreSQL 인증 정보를 읽으므로 명령줄에 비밀번호를 넣지
않습니다.

~~~bash
umask 077
BACKUP_DIR="${HOME}/baton-watch-staging-backups"
mkdir -p "$BACKUP_DIR"
BACKUP_FILE="${BACKUP_DIR}/baton-watch-$(date -u +%Y%m%dT%H%M%SZ).dump"
staging_compose exec -T postgres sh -c \
  'exec pg_dump --format=custom --no-owner --no-privileges --username="$POSTGRES_USER" "$POSTGRES_DB"' \
  > "$BACKUP_FILE"
chmod 0600 "$BACKUP_FILE"
test -s "$BACKUP_FILE"
staging_compose exec -T postgres pg_restore --list \
  < "$BACKUP_FILE" >/dev/null

verify_backup_restore() {
  local restore_database="watch_restore_test_$(date -u +%Y%m%dT%H%M%SZ)_$$"
  local restore_evidence

  staging_compose exec -T --env RESTORE_DATABASE="$restore_database" \
    postgres sh -c \
    'exec createdb --username="$POSTGRES_USER" "$RESTORE_DATABASE"'

  if ! staging_compose exec -T --env RESTORE_DATABASE="$restore_database" \
    postgres sh -c \
    'exec pg_restore --exit-on-error --single-transaction --no-owner --no-privileges --username="$POSTGRES_USER" --dbname="$RESTORE_DATABASE"' \
    < "$BACKUP_FILE"; then
    staging_compose exec -T --env RESTORE_DATABASE="$restore_database" \
      postgres sh -c \
      'exec dropdb --if-exists --username="$POSTGRES_USER" "$RESTORE_DATABASE"'
    return 1
  fi

  if ! restore_evidence="$(staging_compose exec -T \
    --env RESTORE_DATABASE="$restore_database" postgres sh -c \
    'exec psql --username="$POSTGRES_USER" --dbname="$RESTORE_DATABASE" --tuples-only --no-align --command="SELECT count(*) > 0 AND bool_and(success) FROM flyway_schema_history"')"; then
    staging_compose exec -T --env RESTORE_DATABASE="$restore_database" \
      postgres sh -c \
      'exec dropdb --if-exists --username="$POSTGRES_USER" "$RESTORE_DATABASE"'
    return 1
  fi

  staging_compose exec -T --env RESTORE_DATABASE="$restore_database" \
    postgres sh -c \
    'exec dropdb --username="$POSTGRES_USER" "$RESTORE_DATABASE"'
  test "$restore_evidence" = t
}

verify_backup_restore
unset -f verify_backup_restore
~~~

백업을 운영자의 보호된 백업 위치로 복사하고 타임스탬프, 크기, 체크섬, 배포된
SHA, 복원 테스트 결과만 기록합니다. 격리된 데이터베이스에 복원해 보지 않은
덤프는 검증된 백업이 아닙니다.

## 배포

현재 스테이징은 단일 WATCH 인스턴스의 중단 시간을 감수하는 안전 롤아웃을
사용합니다. 이전 애플리케이션을 먼저 멈춘 뒤에만 새 마이그레이션을
실행해야 합니다. Compose의 종속성만으로는 기존 WATCH가 새 스키마 적용 전에
반드시 정지된다고 보장할 수 없으므로, 이 순서를 생략하지 마세요. 최초
배포에서 기존 컨테이너가 없다면 정지 명령은 실질적으로 아무 작업도 하지 않습니다.
정지를 확인한 뒤 전체 스택을 시작합니다. `database-role-init`과 `migrate`는 종료 코드 0으로
완료되어야 하고, 그 뒤에 PostgreSQL·WATCH·Cloudflare Tunnel 상태 점검이
모두 통과해야 합니다.

~~~bash
./ops/staging-image-evidence.sh verify
staging_compose stop watch cloudflared
test -z "$(staging_compose ps --status running --services watch cloudflared)"
staging_compose up -d --no-build --wait --wait-timeout 180
staging_compose ps -a
~~~

명령이 실패하면 디버그 로깅을 활성화하지 말고 범위가 제한된 로그를 검사합니다.
비정상인 PostgreSQL, 역할 초기화, 마이그레이션, WATCH 또는 터널
의존성을 우회하지 마세요. `ps -a` 출력에서 두 일회성 서비스가
`Exited (0)`이고 나머지 서비스가 정상인지 확인하세요.

WATCH 내부에서 애플리케이션과 데이터베이스 상태를 검증합니다. 8081 포트는
호스트와 터널에서 계속 접근할 수 없어야 합니다.

~~~bash
staging_compose exec --user 10001:10001 -T watch \
  wget -q -O - http://127.0.0.1:8081/actuator/health
staging_compose exec --user 10001:10001 -T watch sh -c \
  'test "$WATCH_EVENT_DELIVERY_ENABLED" = false && test "$SPRING_FLYWAY_ENABLED" = false'
MIGRATION_EVIDENCE="$(staging_compose exec -T postgres sh -c \
  'exec psql --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" --tuples-only --no-align --command="SELECT string_agg(version, chr(44) ORDER BY installed_rank) FROM flyway_schema_history WHERE success"')"
test "$MIGRATION_EVIDENCE" = 1,2,3,4
RUNTIME_PRIVILEGE_EVIDENCE="$(
  printf '%s\n' \
    "SELECT concat_ws('|'," \
    "  has_database_privilege(:'runtime_role', current_database(), 'TEMPORARY')," \
    "  has_table_privilege(:'runtime_role', 'public.watch_monitor', 'SELECT')," \
    "  has_table_privilege(:'runtime_role', 'public.watch_monitor', 'DELETE')," \
    "  has_table_privilege(:'runtime_role', 'public.watch_attempt', 'DELETE')," \
    "  has_column_privilege(:'runtime_role', 'public.watch_attempt', 'claimed_at', 'UPDATE')," \
    "  has_table_privilege(:'runtime_role', 'public.watch_result', 'INSERT')," \
    "  has_column_privilege(:'runtime_role', 'public.watch_result', 'outcome', 'UPDATE')," \
    "  has_table_privilege(:'runtime_role', 'public.watch_health_change_event', 'DELETE')," \
    "  has_column_privilege(:'runtime_role', 'public.watch_health_change_event', 'changed_at', 'UPDATE')," \
    "  has_column_privilege(:'runtime_role', 'public.watch_health_change_event', 'delivery_status', 'UPDATE')," \
    "  has_table_privilege(:'runtime_role', 'public.flyway_schema_history', 'SELECT')," \
    "  has_table_privilege(:'runtime_role', 'public.watch_health_change_event_backlog', 'UPDATE')," \
    "  has_function_privilege(:'runtime_role', 'public.maintain_watch_health_change_event_backlog()', 'EXECUTE'));" |
  staging_compose exec -T --env WATCH_DB_RUNTIME_USER="$WATCH_DB_RUNTIME_USER" \
    postgres sh -c \
    'exec psql --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" --tuples-only --no-align --set=runtime_role="$WATCH_DB_RUNTIME_USER"'
)"
test "$RUNTIME_PRIVILEGE_EVIDENCE" = 'f|t|f|t|f|t|f|t|f|t|f|f|f'
~~~

데이터베이스 표시 항목을 포함한 상태 응답은 `UP`이어야 하며, 전달·Flyway
비활성화 검증과 V1~V4 마이그레이션 증거 검사는 종료 코드 0으로
끝나야 합니다. WATCH 상태는 런타임 역할의 데이터베이스 연결 성공을
확인하지만 세부 테이블 권한 전체를 증명하지는 않습니다. 따라서 런타임 역할의
`TEMPORARY`, 모니터 조회와 삭제 거부, 시도 보존 삭제와 불변 열 갱신 거부,
결과 삽입과 갱신 거부, 이벤트 보존 삭제와 페이로드 열 갱신 거부, 전달 상태 열
갱신, `flyway_schema_history` 조회 거부, 백로그 요약 직접 변경 거부와 보호된
함수 직접 실행 거부가 순서대로
`f|t|f|t|f|t|f|t|f|t|f|f|f`인지 직접 검증합니다.

## 데이터베이스 비밀번호 교체

데이터베이스 소유자와 런타임 비밀번호는 별도로 교체합니다. 새 값은 비밀 관리
시스템에서 생성하고 현재 소유자·런타임 값과 모두 달라야 합니다. 아래 명령은
새 값을 명령행이나 SQL 파일에 넣지 않고 `psql`의 `\password` 입력으로 전달하며,
교체 전후 자격 증명으로 실제 연결을 확인합니다. 먼저 런타임 비밀번호를 교체하고
WATCH 비밀 파일을 원자적으로 바꾼 뒤 WATCH를 강제 재생성하세요.

~~~bash
set +x
umask 077
NEW_DATABASE_SECRET="${STAGING_CONFIG_DIR}/secrets/postgres-new-password"
install -m 0600 /dev/null "$NEW_DATABASE_SECRET"
# 비밀 관리 시스템이 새 런타임 값을 마지막 개행 하나와 함께 기록합니다.
test -s "$NEW_DATABASE_SECRET"
test -f "$NEW_DATABASE_SECRET"
test ! -L "$NEW_DATABASE_SECRET"

staging_compose stop watch
staging_compose run --rm --no-deps -T \
  --entrypoint /opt/watch/run-as-database-user.sh \
  --volume "$NEW_DATABASE_SECRET:/run/secrets/postgres-new-password:ro" \
  --env WATCH_DB_NEW_PASSWORD_FILE=/run/secrets/postgres-new-password \
  database-role-init \
  70 70 /opt/watch/staging-database-operation.sh rotate-runtime-password
mv "$NEW_DATABASE_SECRET" \
  "$STAGING_CONFIG_DIR/secrets/postgres-runtime-password"
chmod 0600 "$STAGING_CONFIG_DIR/secrets/postgres-runtime-password"
staging_compose up -d --no-deps --force-recreate --wait watch
staging_compose exec --user 10001:10001 -T watch \
  wget -q -O - http://127.0.0.1:8081/actuator/health
~~~

소유자 비밀번호는 WATCH를 정지하지 않고 같은 방식으로 교체할 수 있습니다.
`database-role-init`과 `migrate`를 다시 실행해 새 소유자 자격 증명과 기존
런타임 비밀 파일이 함께 동작하는지 확인합니다.

~~~bash
install -m 0600 /dev/null "$NEW_DATABASE_SECRET"
# 비밀 관리 시스템이 새 소유자 값을 마지막 개행 하나와 함께 기록합니다.
test -s "$NEW_DATABASE_SECRET"
staging_compose run --rm --no-deps -T \
  --entrypoint /opt/watch/run-as-database-user.sh \
  --volume "$NEW_DATABASE_SECRET:/run/secrets/postgres-new-password:ro" \
  --env WATCH_DB_NEW_PASSWORD_FILE=/run/secrets/postgres-new-password \
  database-role-init \
  70 70 /opt/watch/staging-database-operation.sh rotate-owner-password
mv "$NEW_DATABASE_SECRET" \
  "$STAGING_CONFIG_DIR/secrets/postgres-owner-password"
chmod 0600 "$STAGING_CONFIG_DIR/secrets/postgres-owner-password"
staging_compose run --rm --no-deps -T database-role-init
staging_compose run --rm --no-deps -T migrate
unset NEW_DATABASE_SECRET
~~~

교체 전 비밀번호는 검증이 끝날 때까지 비밀 관리 시스템의 이전 버전으로만
보관하고 로컬 보조 파일로 남기지 마세요. 교체 또는 재생성이 실패하면 이전 값을
새 비밀번호 입력으로 선택해 같은 명령을 다시 실행하고, 원래 비밀 파일을 원자적으로
복원한 뒤 해당 서비스를 강제 재생성합니다. 데이터베이스 역할과 파일 중 한쪽만
되돌리지 마세요.

## 외부 HTTPS 스모크 테스트

가능하면 오리진 Mac 외부 네트워크에서 실행합니다. 이 요청에는 토큰이나 실제
리소스 참조가 들어 있지 않습니다.

~~~bash
PUBLIC_BASE_URL=https://watch-staging.b4ton.com
curl --proto '=https' --tlsv1.2 --noproxy '*' \
  --connect-timeout 5 --max-time 10 --max-filesize 65536 \
  --silent --show-error --fail \
  "$PUBLIC_BASE_URL/api/v1/system/status"

UNAUTHORIZED_STATUS="$(curl --proto '=https' --tlsv1.2 --noproxy '*' \
  --connect-timeout 5 --max-time 10 --max-filesize 65536 \
  --silent --show-error \
  --request PUT --header 'Content-Type: application/json' --data '{' \
  --output /dev/null --write-out '%{http_code}' \
  "$PUBLIC_BASE_URL/api/v1/resource-monitors/staging-auth-smoke")"
test "$UNAUTHORIZED_STATUS" = 401

CATCH_ALL_STATUS="$(curl --proto '=https' --tlsv1.2 --noproxy '*' \
  --connect-timeout 5 --max-time 10 --max-filesize 65536 \
  --silent --show-error \
  --output /dev/null --write-out '%{http_code}' \
  "$PUBLIC_BASE_URL/api/v1/ingress-deny-smoke")"
test "$CATCH_ALL_STATUS" = 404
~~~

상태 JSON은 `baton-watch`를 `UP`으로 식별해야 합니다. 인증하지 않고 잘못된
형식으로 보낸 모니터 요청은 `401`을 반환해야 합니다. 이 결과는 공개 경로가
WATCH에 도달하고 파싱 전에 안전하게 차단된다는 점을 입증합니다. 라우팅되지
않은 `/api/v1/**` 확인용 경로는 터널의 기타 모든 요청 처리 규칙에서 `404`를
반환해야 합니다. WATCH로 제한 없이 라우팅됐다면 안전하게 차단하는 인증 경계를
만나 `401`이 반환됩니다.

응답 헤더만 보관하면서 상태 요청을 반복합니다. 인증서가
`watch-staging.b4ton.com`에 유효하고, 어떤 리다이렉트도 호스트 이름이나
스킴을 바꾸지 않으며, `CF-Cache-Status`가 절대 `HIT`가 아닌지 확인합니다.
터널 없음, Cloudflare 엣지 오류, 캐시된 응답 또는 다른 서비스의 응답이
발생하면 스모크 테스트는 실패합니다.

## 로그 비식별화 감사

Compose는 기본적으로 각 JSON 로그를 10 MiB 파일 세 개로 제한합니다.
`cloudflared` 로그 수준은 `info`로 유지하세요. 디버그 또는 요청 헤더 로깅은
허용하지 않습니다.

권한 모드 `0700` 임시 디렉터리와 권한 모드 `0600` 로그 파일에 짧은 감사 스냅샷을
수집합니다. 정확한 비밀값 네 개, `Authorization`, Bearer 값, 대상 URL,
리소스 참조, 콜백 URL, 요청 페이로드를 로컬에서 검색합니다. 스캐너는 범주와
통과/실패 결과만 보고해야 하며, 일치한 줄이나 비밀값은 절대 보고해서는 안
됩니다. 하나라도 일치하면 배포 실패이며, 비밀값이 노출됐을 가능성이 있으면
토큰을 교체해야 합니다.

~~~bash
umask 077
AUDIT_DIR="$(mktemp -d "${TMPDIR:-/tmp}/baton-watch-log-audit.XXXXXX")"
staging_compose logs --no-color --since 15m > "$AUDIT_DIR/compose.log"
chmod 0600 "$AUDIT_DIR/compose.log"
~~~

원본 로그를 업로드하지 마세요. 범위가 제한된 결과를 기록한 뒤 파일을 삭제하고,
그다음 비어 있는 디렉터리를 삭제합니다.

~~~bash
rm "$AUDIT_DIR/compose.log"
rmdir "$AUDIT_DIR"
unset AUDIT_DIR
~~~

## 롤백

롤백에는 이전에 검증한 전체 커밋 SHA와 그 SHA로 이미 빌드하여 보관한
데이터베이스 작업·마이그레이션·WATCH 이미지 세 개를 모두 사용합니다. 다른 작업 트리에서 이전
태그를 다시 빌드하지 마세요.

~~~bash
export PREVIOUS_SHA=replace-with-previous-verified-40-character-sha
test "$(printf '%s' "$PREVIOUS_SHA" | wc -c | tr -d ' ')" = 40
export WATCH_IMAGE_REVISION="$PREVIOUS_SHA"
export WATCH_IMAGE="baton-watch:${WATCH_IMAGE_REVISION}"
export WATCH_DATABASE_OPERATIONS_IMAGE="baton-watch-database-operations:${WATCH_IMAGE_REVISION}"
export WATCH_MIGRATION_IMAGE="baton-watch-migrations:${WATCH_IMAGE_REVISION}"
export WATCH_IMAGE_ARCHIVE_DIR="${STAGING_CONFIG_DIR}/images/${PREVIOUS_SHA}"
./ops/staging-image-evidence.sh restore
./ops/staging-image-evidence.sh verify
ROLLBACK_IMAGE_REVISION="$(docker image inspect "$WATCH_IMAGE" \
  --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}')"
ROLLBACK_DATABASE_OPERATIONS_IMAGE_REVISION="$(docker image inspect "$WATCH_DATABASE_OPERATIONS_IMAGE" \
  --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}')"
ROLLBACK_MIGRATION_IMAGE_REVISION="$(docker image inspect "$WATCH_MIGRATION_IMAGE" \
  --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}')"
test "$ROLLBACK_IMAGE_REVISION" = "$PREVIOUS_SHA"
test "$ROLLBACK_DATABASE_OPERATIONS_IMAGE_REVISION" = "$PREVIOUS_SHA"
test "$ROLLBACK_MIGRATION_IMAGE_REVISION" = "$PREVIOUS_SHA"
~~~

현재 볼륨에 이전 이미지를 바로 덮어 실행하지 마세요. 현재 스키마가
이전 WATCH와 양방향 호환된다는 것, 이전 마이그레이션 이미지가 현재
`flyway_schema_history`를 성공적으로 검증한다는 것, 동일한 역할 권한 경계가
유지된다는 것을 활성 볼륨의 복제본 또는 검증 백업을 복원한 격리 환경에서
먼저 입증한 경우에만 제자리 애플리케이션 롤백을 허용합니다. 이 호환성 증거가
있을 때에만 다음을 실행하고 내부 상태, 외부 상태/401/404, 캐시, TLS,
로그 감사를 반복하세요.

~~~bash
staging_compose stop watch cloudflared
test -z "$(staging_compose ps --status running --services watch cloudflared)"
staging_compose up -d --no-build --wait --wait-timeout 180
staging_compose ps -a
~~~

새 릴리스가 스키마를 전진시켰거나 호환성을 입증하지 못했다면 이전
애플리케이션만 활성 볼륨에 연결하지 마세요. 위의 복원 테스트를 통과한
마지막 백업을 선택하여 명시적으로 이름을 지정한 새 볼륨에 복원합니다.
이 경로에서도 이전 데이터베이스 작업·마이그레이션·WATCH 이미지 세 개의
SHA 태그를 모두 검증해야 합니다.

~~~bash
export BACKUP_FILE=replace-with-last-verified-backup-file
test -s "$BACKUP_FILE"
FAILED_VOLUME="$WATCH_POSTGRES_VOLUME_NAME"
RESTORED_VOLUME="baton-watch-staging-restore-${PREVIOUS_SHA:0:12}-$(date -u +%Y%m%dT%H%M%SZ)"

staging_compose down --remove-orphans
docker volume inspect "$FAILED_VOLUME"
docker volume create "$RESTORED_VOLUME"
export WATCH_POSTGRES_VOLUME_NAME="$RESTORED_VOLUME"
staging_compose up -d --no-deps --wait --wait-timeout 120 postgres
staging_compose exec -T postgres sh -c \
  'exec pg_restore --exit-on-error --single-transaction --no-owner --no-privileges --username="$POSTGRES_USER" --dbname="$POSTGRES_DB"' \
  < "$BACKUP_FILE"
RESTORED_MIGRATION_EVIDENCE="$(staging_compose exec -T postgres sh -c \
  'exec psql --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" --tuples-only --no-align --command="SELECT count(*) > 0 AND bool_and(success) FROM flyway_schema_history"')"
test "$RESTORED_MIGRATION_EVIDENCE" = t
staging_compose up -d --no-build --wait --wait-timeout 180
staging_compose ps -a

persist_active_volume() {
  local state_tmp
  state_tmp="$(mktemp "${STAGING_CONFIG_DIR}/staging-state.env.XXXXXX")"
  if ! printf 'WATCH_POSTGRES_VOLUME_NAME=%s\n' \
    "$WATCH_POSTGRES_VOLUME_NAME" > "$state_tmp"; then
    rm -f "$state_tmp"
    return 1
  fi
  chmod 0600 "$state_tmp"
  mv "$state_tmp" "$STAGING_STATE_FILE"
}

persist_active_volume
unset -f persist_active_volume
test "$(wc -l < "$STAGING_STATE_FILE" | tr -d ' ')" = 1
grep -Fxq \
  "WATCH_POSTGRES_VOLUME_NAME=${WATCH_POSTGRES_VOLUME_NAME}" \
  "$STAGING_STATE_FILE"
~~~

신규 볼륨에서 `database-role-init`과 이전 SHA의 `migrate`가 모두 종료 코드
0이고 WATCH가 정상인지 확인하세요. 롤백을 승인하기 전에 모든 상태 점검과
외부 스모크 테스트를 반복합니다. 복구를
되돌릴 수 있도록 두 볼륨 이름을 모두 기록합니다. 복원된 서비스와 백업을 각각
독립적으로 검증할 때까지 어느 볼륨도 삭제하지 마세요. 복원이나 시작이 실패하면
새 스택을 중지하고 진단을 위해 두 볼륨을 모두 보존합니다.

## 종료 및 폐기 정리

일상적인 스테이징 종료는 컨테이너와 프로젝트 네트워크를 제거하지만 외부
데이터베이스 볼륨과 로컬 이미지는 보존합니다.

~~~bash
staging_compose down --remove-orphans
docker volume inspect "$WATCH_POSTGRES_VOLUME_NAME"
~~~

일상적인 종료 명령에는 절대 `--volumes`를 추가하지 마세요. 롤백 가능 기간이
끝난 뒤에만 이전 SHA 태그 이미지와 해당 SHA의 이미지 보관 디렉터리를 제거하며,
광범위한 이미지 정리 명령을 실행하지 말고 정확한 태그와
`${STAGING_CONFIG_DIR}/images/<full-git-sha>` 디렉터리를 지정하세요.

영구 폐기할 때는 먼저 공개 호스트 이름을 비활성화하고 원격 관리형 터널을
삭제하거나 비활성화한 뒤 Cloudflare에서 커넥터 토큰을 폐기합니다. 그런 다음
해당 토큰 파일을 삭제합니다. API/데이터베이스 비밀 파일과 외부 PostgreSQL
볼륨은 최종 검증 백업과 명시적인 데이터 보존 결정을 마친 뒤에만 제거하세요.
저장소 정리만으로는 실행 중인 터널이나 DNS 경로가 비활성화되지 않습니다.
