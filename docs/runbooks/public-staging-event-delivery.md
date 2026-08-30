# 공개 스테이징 상태 변경 전달 검증

상태: 운영자용 실행 절차서이며, 실제 실행 완료를 의미하지 않음

최종 수정일: 2026-08-29

## 목적

전용 공개 스테이징 환경에서 구현된 WATCH-BATON 전달 경계를 검증합니다. 이
실습은 최초 전달, 동일한 이벤트 ID를 사용한 응답 유실 후 재전달, 백로그
해소를 검증합니다. 운영 배포를 승인하는 절차는 아닙니다.

## 필수 환경

- `curl`과 Python 3을 사용할 수 있는 운영자 실행 호스트
- 공개 DNS 이름과 443 포트의 유효한 HTTPS를 사용하는 배포된 WATCH 인스턴스와
  호환되는 BATON 수신기
- 정확한 BATON 콜백과 URL에 안전한 32~200자 Bearer 토큰을 사용해 전달이
  활성화된 WATCH. 전달 토큰은 WATCH 모니터 API 토큰뿐 아니라 모든 BATON
  운영자 또는 워크스페이스 비밀값과 달라야 합니다.
- BATON과 WATCH가 공유하는 전용 스테이징 소스 네임스페이스
- WATCH PostgreSQL, BATON MySQL, WATCH 관리 포트, 두 서비스의 로그에 대한
  운영자 전용 접근 권한. 관리 포트는 비공개로 유지해야 합니다.
- 안정적으로 성공 응답을 반환할 수 있는 통제된 공개 점검 대상
- BATON 수신기 앞에 배치한 스테이징 전용 장애 주입 인그레스. 요청 하나를
  BATON이 커밋할 때까지 전달한 뒤, 해당 응답만 버리거나 WATCH의 전체 제한
  시간 5초를 넘도록 지연할 수 있어야 합니다. 업스트림 요청 재시도, 리다이렉트
  추적, 요청 본문이나 인증 정보 기록을 해서는 안 되며, 별도 인증 없이 제어
  영역을 공개해서도 안 됩니다.

실습을 통과시키기 위해 `/etc/hosts`, IP 리터럴, 비공개 콜백, 기본값이 아닌
HTTPS 포트 또는 완화된 WATCH 대상 정책을 사용하지 마세요.

## 안전한 사전 점검

`https://watch-staging.b4ton.com`은 선택된 WATCH 공개 스테이징 기본 URL입니다.
DNS 레코드, 유효한 HTTPS 라우팅, 배포된 인스턴스, 외부 상태 응답이 모두
검증되기 전까지는 의도한 설정값으로만 취급하세요.

셸 추적을 활성화하지 않은 상태에서 스테이징 비밀 저장소의 값을 불러옵니다.

~~~bash
(
  set +x
  export WATCH_PUBLIC_BASE_URL=https://watch-staging.b4ton.com
  export WATCH_EVENT_DELIVERY_ENABLED=true
  export WATCH_EVENT_DELIVERY_ENDPOINT=https://baton.staging.example.com/api/v1/internal/resource-health-events
  read -r -s -p 'WATCH API token: ' WATCH_API_TOKEN && export WATCH_API_TOKEN
  read -r -s -p 'WATCH delivery token: ' WATCH_EVENT_DELIVERY_TOKEN && export WATCH_EVENT_DELIVERY_TOKEN
  trap 'unset WATCH_API_TOKEN WATCH_EVENT_DELIVERY_TOKEN' EXIT
  ./ops/staging-event-delivery-preflight.sh
)
~~~

사전 점검은 호환되는 토큰 형식과 토큰 간 분리를 검증하고, 공개 WATCH 상태
엔드포인트를 확인하며, 인증 정보 없이 의도적으로 잘못된 JSON 콜백 요청을
전송합니다. 수신기는 반드시 `401`을 반환해야 합니다. 대신 파서 수준의 4xx가
반환되면 인증이 JSON 역직렬화보다 먼저 수행됐음을 입증하지 못했으므로 사전
점검은 실패합니다. 스크립트는 두 토큰을 어느 것도 전송하지 않으며 응답 본문을
버립니다. 공통 URL 정책은 IP 리터럴과 정수·8진수·16진수·축약 IPv4 표기를
요청 전에 거부합니다. 실행 가능한 테스트는 콜백 메서드, 콘텐츠 유형, 잘못된
본문, 사용자 curl 설정 격리, HTTPS 전용 프로토콜, 프록시 미사용 동작, TLS 최저
버전, 제한 시간, 출력 폐기를 고정합니다. 입력 검증이 실패하면 curl을 호출하지
않고, WATCH 상태 확인이 실패하면 BATON 수신기를 호출하지 않으며, 수신기
검증까지 진행한 경우에만 두 번째 curl 호출을 허용합니다. 스크립트는 토큰 형식과
분리를 확인한 직후 두 토큰을 하위 Python·curl 환경에서 제거합니다. 사전 점검
통과는 도달 가능성과 외부에서 관찰한 수신기 인증 경계만 입증합니다. 유효한 인증 정보의
수락, 배포된 WATCH 설정, 전달 동작을 입증하지는 않습니다.

## 이벤트 전달 오버레이 활성화

사전 점검이 통과한 뒤에만 스테이징 배포 런북에서 정의한
`STAGING_CONFIG_DIR`, `STAGING_ENV_FILE`, `staging_compose`를 사용합니다. 전달
토큰 파일에는 값 하나와 마지막 줄바꿈만 기록하고 저장소·명령행·환경 변수에는
토큰을 넣지 않습니다. 환경 파일에는 검증한 콜백 URL과 토큰 파일 경로만 둡니다.

~~~bash
chmod 0600 "$STAGING_CONFIG_DIR/secrets/watch-event-delivery-token"
test -s "$STAGING_CONFIG_DIR/secrets/watch-event-delivery-token"
test -O "$STAGING_CONFIG_DIR/secrets/watch-event-delivery-token"
test -f "$STAGING_CONFIG_DIR/secrets/watch-event-delivery-token"
test ! -L "$STAGING_CONFIG_DIR/secrets/watch-event-delivery-token"
grep -Eq '^WATCH_EVENT_DELIVERY_ENDPOINT=https://[^[:space:]]+$' "$STAGING_ENV_FILE"
staging_compose config --quiet
staging_compose up -d --force-recreate watch cloudflared
~~~

헬퍼는 콜백 URL이 있을 때만 `compose.staging-event-delivery.yml`을 추가합니다.
오버레이는 전달을 명시적으로 활성화하고 토큰을 Compose secret과 Spring
`configtree`로 주입합니다. 렌더링된 환경이나 `docker inspect`에 토큰이 보이면
실패입니다. 재기동 뒤 공개 상태와 내부 상태를 다시 확인하고 나서 아래 전달 실습을
시작합니다. 이 활성화만으로 실제 전달 성공을 주장해서는 안 됩니다.

## 증거 수집 규칙

초기 전달 백로그가 0인 전용 환경에서 실습을 실행하세요. 보관 보고서에는
범위가 제한된 개수, 상태, 타임스탬프만 기록합니다. 이벤트 ID는 데이터베이스
행을 연관 짓기 위해 일시적으로 사용할 수 있지만, 대상 URL, 콜백 URL, 리소스
참조, 페이로드, 토큰, 헤더, 예외 메시지 또는 원본 로그는 증거로 보관하지
마세요.

아래에서 사용하는 Prometheus 이름은 다음과 같습니다.

- `baton_watch_event_delivery_backlog`
- `baton_watch_event_delivery_claimed_total`
- `baton_watch_event_delivery_attempts_total{outcome="..."}`
- `baton_watch_event_delivery_finalizations_total{status="..."}`
- `baton_watch_event_delivery_lease_recoveries_total`

백로그 게이지는 1분 주기의 유지보수 일정에 따라 갱신되므로, 게이지가 수렴하기를
기다리는 동안에는 PostgreSQL 행 상태를 기준으로 판단합니다. 카운터 시계열이
없다면 최초 증가 전 값은 0을 의미합니다.

스케줄러의 `replayed` 로그 필드는 BATON 수신함 재전달 증거가 아닙니다. 이미
전달된 이벤트를 발견한 WATCH 완료 처리 횟수를 셉니다.

장애 주입 인그레스는 최초 BATON `202` 수신 결과와 재전달 수신 결과를 메모리에서
비교하고 다음과 같이 범위가 제한된 집계 증거만 노출해야 합니다. 요청 수, 고유
이벤트 수, 수신기 삽입/재전달/충돌 수, 유실된 응답 수, `sameReceipt` 불리언 값.
원본 수신 결과, 이벤트 ID, 리소스 참조, 페이로드 또는 인증 값을 보관하거나
노출해서는 안 됩니다.

## 1단계: 최초 전달

1. 스테이징 장애 주입 인그레스를 그대로 전달하는 모드로 설정하고 제한된
   카운터를 초기화합니다.
2. `baton-manager:<namespace>:role-resource:<uuid>` 형식의 고유한 정규 참조를
   만들고, 통제된 공개 대상을 향하는 소스 리비전 1의 ACTIVE 모니터를
   동기화합니다. 이 전달 경계 실습에서는 WATCH 직접 동기화를 허용합니다.
   더 넓은 통합을 검증할 때는 정상적인 BATON 커밋 후 동기화 경로를 사용하세요.
3. 모니터가 UNKNOWN에서 HEALTHY로 바뀌고 관련 이벤트가 DELIVERED 상태가 될
   때까지 기다립니다.
4. 콜백 요청 1건, BATON 수신함 삽입 1건, 충돌 없음, WATCH 전달 완료 처리 1건,
   백로그 기준값 0으로의 복귀를 확인합니다.

## 2단계: 응답 유실 후 재전달

1. 새로운 정규 참조를 사용하고 정확히 한 번의 `ACK_THEN_DROP` 동작이 수행되도록
   인그레스를 준비합니다. 요청을 BATON으로 전달하고 수신기의 2xx 응답을 관찰한
   뒤 해당 응답을 WATCH에 전달하지 않거나 지연해야 합니다.
2. 소스 리비전 1의 다른 ACTIVE 모니터를 동기화하고 최초 상태 변경을 기다립니다.
3. 장애를 제거하기 전에 BATON이 수신함 행 하나를 커밋했지만 WATCH 이벤트는
   `delivery_attempt = 1`인 PENDING 상태임을 확인합니다. WATCH의 범위가 제한된 결과 코드는
   인그레스가 응답을 유실한 전송 단계에 따라 CONNECT_TIMEOUT, READ_TIMEOUT 또는
   NETWORK_FAILURE일 수 있습니다.
4. 인그레스를 그대로 전달하는 모드로 되돌리고 영속 재시도를 기다립니다.
5. 인그레스가 동일한 `Idempotency-Key`를 최소 두 번 관찰했고, 수신기 삽입 1건,
   정확한 재전달 1건, 충돌 없음, `sameReceipt = true`를 보고하는지 확인합니다.
   BATON 수신함에 여전히 정확히 한 행만 있고, WATCH가
   `delivery_attempt >= 2`와 DELIVERED를 보고하며, 백로그가 0으로 돌아오는지
   확인합니다. 충돌, 두 번째 수신함 행, 서로 다른 수신 결과 중 하나라도 있으면
   실습은 실패합니다.

운영자 전용 PostgreSQL 세션에서 실행할 수 있는 WATCH 증거 조회 쿼리는 다음과
같습니다.

~~~sql
SELECT event_id,
       delivery_status,
       delivery_attempt,
       last_delivery_outcome,
       last_http_status_code,
       next_attempt_at,
       delivered_at,
       delivery_lease_token
FROM watch_health_change_event
WHERE resource_reference = :'resource_reference'
ORDER BY changed_at DESC, event_id DESC
LIMIT 1;
~~~

반환된 이벤트 ID는 보호된 BATON MySQL 세션에서만 사용하세요.

~~~sql
SELECT COUNT(*) AS inbox_rows
FROM watch_health_event_inbox
WHERE event_id = UUID_TO_BIN(?);
~~~

단일 행 조회만으로는 재전달 자체가 아니라 영속 중복 제거만 입증됩니다. 인그레스의
두 차례 업스트림 관찰과 `sameReceipt` 비교가 재전달 증거를 제공합니다.

## 3단계: 백로그 해소

1. 장애 주입 인그레스가 전달하지 않고 `503`을 반환하는 모드로 설정합니다.
2. 고유한 ACTIVE 모니터를 세 개 더 만들고 PostgreSQL에 PENDING 이벤트 3개가
   생기며 HTTP_SERVER_ERROR 시도 카운터가 증가할 때까지 기다립니다.
3. 그대로 전달하는 모드를 복원합니다. 이벤트 세 개가 모두 DELIVERED가 되고,
   BATON에 정확히 세 개의 고유 수신함 행이 추가되며, PostgreSQL과 지연된
   Prometheus 게이지가 모두 기준값 0으로 돌아오는지 확인합니다.
4. 백로그가 정상으로 보이게 하려고 보류 중인 이벤트를 삭제하거나 다시 쓰거나
   강제로 전달하지 마세요.

## 로그 및 비밀값 감사

로그는 권한 모드 `0600` 임시 디렉터리에만 수집하세요. 두 서비스와 인그레스에서 전달
토큰, 모니터 API 토큰, 콜백 URL, 리소스 참조, 요청 페이로드 필드를 검색합니다.
일치 항목이 하나라도 있으면 실패로 처리하되, 일치한 줄을 출력하거나 원본 로그를
업로드하지 마세요. 인그레스는 요청 수, 고유 이벤트 수, 수신기
삽입/재전달/충돌 수, 유실된 응답 수, 중복 수신 결과의 일치 여부처럼 범위가
제한된 집계 증거만 보관할 수 있습니다.

## 정리 및 롤백

장애 주입 인그레스는 항상 그대로 전달하는 모드로 복원하세요. 모든 임시 모니터를
더 높은 INACTIVE 리비전으로 동기화하고, 그로 인해 생성된 이벤트가 모두 처리될
때까지 기다립니다. 수신기나 인그레스가 계속 비정상이면 새로운 전달 작업 선점을
비활성화하고 보류 중인 행을 유지하세요. 삭제해서는 안 됩니다. 터미널 기록이나
로그에 나타난 비밀값은 모두 교체하세요.

전달을 비활성화할 때는 `WATCH_EVENT_DELIVERY_ENDPOINT=`를 비우고
`staging_compose up -d --force-recreate watch cloudflared`를 실행해 기본
비활성 Compose로 되돌립니다. 토큰 파일은 감사와 롤백 판단이 끝난 뒤 비밀 관리
절차로 폐기하거나 교체합니다.

최초 전달, 동일 이벤트 재전달, BATON 단일 행 중복 제거, 백로그 해소, 로그/비밀값
감사가 모두 통과해야만 실습에 성공합니다. 실제 실행 후에만 실행 날짜와 범위가
제한된 결과를 HANDOFF.md에 기록하세요.
