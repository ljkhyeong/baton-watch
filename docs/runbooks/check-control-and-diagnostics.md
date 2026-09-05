# 점검 중지·재개, 수동 재점검과 읽기 전용 장애 진단

이 문서는 운영자가 직접 실행할 절차다. 저장소에 도구가 있다는 사실은 운영
배포나 실제 대상 점검·BATON 콜백 전달의 성공을 의미하지 않는다.

## 점검만 중지하거나 재개하기

`WATCH_CHECK_ENABLED`는 기본 `true`이며 인스턴스 시작 시 적용한다. `false`이면
대상 점검 예약만 등록하지 않는다. 전체 점검을 실시간으로 중지·재개하는 API는 제공하지 않는다.
설정 바인딩과 예약 조건은 Spring의 같은 불리언 변환을 사용한다.
`on`·`yes`·`1`은 활성, `off`·`no`·`0`은 비활성으로 해석하며 대소문자와 앞뒤
공백을 허용한다. 운영 설정에는 `true`·`false`를 사용하고, 그 밖의 해석할 수 없는
값으로 시작에 실패하면 설정을 바로잡는다.

- 모니터의 `ACTIVE`/`INACTIVE`, 원본 리비전, 일정과 리스를 바꾸지 않는다.
- API와 DB 연결은 유지하며 오래된 상태 처리, 이력 정리, 시계 편차 측정은 계속한다.
- 이벤트 전달은 별도의 `WATCH_EVENT_DELIVERY_ENABLED` 설정을 따른다.
- 확정 결과가 오래되면 기존 규칙대로 `UNKNOWN`으로 바뀌고 변경 이벤트를 기록할 수 있다.
- 점검이 꺼져도 토큰·시간·자원 상한 등 시작 시 설정 검증은 유지한다.

1. 현재 이미지 리비전과 설정을 기록한다. 다중 인스턴스이면 중지할 인스턴스를
   모두 확인한다. 한 인스턴스만 끄면 다른 인스턴스는 계속 점검한다.
2. 환경 파일의 `WATCH_CHECK_ENABLED`를 `false`로 바꾼다. 셸에서 같은 변수를
   내보낸 경우 Compose 환경 파일보다 우선하므로 셸 값도 확인한다.
3. 현재 배포와 같은 이미지·볼륨·Compose 오버레이를 사용해 WATCH만 재생성한다.
   재생성 중에는 해당 인스턴스의 API도 잠시 중단된다. DB를 재생성하지 않는다.
4. Prometheus 수집 대상에 `check_enabled: "false"`를 설정하고 규칙을 다시 읽힌다.
   [경보 적용 조건](monitoring-alerts.md)을 따른다. 수집·유지보수·활성 전달 경보는 유지한다.
5. 내부 상태 점검과 인증된 조회를 확인한다. 점검 예약 완료 지표는 더 증가하지
   않아야 하고 유지보수 지표는 계속 증가해야 한다.

로컬 Compose 예시:

```bash
# .env를 수정한 뒤 실행한다.
docker compose up -d --no-deps --force-recreate watch
```

스테이징에서는 [배포 절차](staging-deployment.md)의 현재 이미지·볼륨·오버레이를
선택한 `staging_compose` 함수를 사용한다.

```bash
# STAGING_ENV_FILE을 수정한 뒤, 현재 배포 리비전을 선택한 셸에서 실행한다.
staging_compose up -d --no-deps --force-recreate watch
```

`restart`만으로는 바뀐 컨테이너 환경 변수를 반영하지 못한다. 재개할 때는 값을
`true`로 되돌리고 재생성한 뒤 수집 레이블도 `"true"`로 바꾸거나 제거한다.
기존 리스가 만료되고 일정이 도래한 항목부터 기존 배치·시간·동시성 상한으로
처리한다. 누적 점검을 한꺼번에 실행하거나 이력을 초기화하지 않는다.

## 복구 후 수동 재점검

`POST /api/v1/resource-monitors/{resourceReference}/check-requests`를 기존 Bearer 서비스
인증으로 본문 없이 호출한다. 운영자의 기존 안전한 API 호출 경로를 사용하며 토큰을
터미널 기록·명령행·로그에 남기지 않는다. BATON의 원본 리비전을 인위적으로 올리거나
`INACTIVE`/`ACTIVE`를 왕복시킬 필요가 없다.

202의 `SCHEDULED`는 일정 앞당김, `ALREADY_SCHEDULED`는 대기 작업 합류,
`IN_PROGRESS`는 현재 실행 중인 점검 합류다. 요청만으로 상태나 실패 횟수가 초기화되지
않으며 점검 결과는 기존 GET의 `lastCheckedAt`과 `lastOutcome`으로 확인한다.
합류한 점검이 요청 직전 시작됐을 수 있으므로 별도 새 네트워크 호출을 보장하지 않는다.

실행할 활성 작업자가 없으면 요청은 대기한다. 비활성 모니터는 409로 거부한다.
새 수동 예약은 직전 예약 후 30초부터 가능하며 429이면 `Retry-After`만큼 기다린다.
본문 필드, 오류와 응답 의미는 [API 계약](../PRD/0002_api-contract/spec.md)을 따른다.

## 리소스별 최근 장애 확인

Docker와 DB 운영 권한을 이미 가진 운영자만 사용하는 도구다. HTTP 조회 경로나
새 DB 계정을 만들지 않는다. PostgreSQL 18 컨테이너 안의 `psql`과 기존
`POSTGRES_USER`·`POSTGRES_DB` 설정, 승인된 로컬 소켓 인증을 사용한다.
로컬 소켓 인증이 변경되어 접속할 수 없으면 실패하며 원격 접속으로 우회하지 않는다.
비밀번호를 호스트로 가져오거나 인자로 넘기지 않는다.

```bash
WATCH_DIAGNOSTIC_CONTAINER="$(staging_compose ps -q postgres)"
./ops/staging-monitor-diagnostics.sh "$WATCH_DIAGNOSTIC_CONTAINER" resource-123
# 각 이력을 최근 20건으로 제한한다.
./ops/staging-monitor-diagnostics.sh "$WATCH_DIAGNOSTIC_CONTAINER" resource-123 20
```

로컬에서는 `staging_compose ps -q postgres` 대신 `docker compose ps -q postgres`를
사용한다. 컨테이너를 명시적으로 선택하며 도구가 운영 환경을 자동 탐색하지 않는다.

출력은 한 JSON 객체다. 별도 보기 도구를 설치하지 않고도 파일이나 터미널에서
확인할 수 있으며 기존 `jq`가 있으면 `| jq`로 정렬할 수 있다.

| 항목 | 내용과 해석 |
| --- | --- |
| `observedAt`, `readOnly` | DB 조회 시각과 실제 읽기 전용 트랜잭션 여부 |
| `monitor` | 원본 리비전, 활성 상태, 도출 상태, 연속 실패 수, 마지막 결과·확정 시각과 다음 점검 시각 |
| `checks` | 시도 ID·시각, 결과 분류, HTTP 상태, 소요 초, 응답 바이트 수와 리다이렉트 횟수 |
| `deliveries` | 이벤트 ID·변경 시각, 상태, 전달 완료 여부, 전달 시도 횟수, 마지막 결과·HTTP 상태, 다음 재시도·완료 시각 |

- 점검은 시도 시각, 이벤트는 상태 변경 시각의 내림차순이다. 같은 시각은 ID 내림차순이다.
- 두 이력을 **각각** 기본 50건, 최대 100건 반환한다. 전체 건수나 모든 과거 이력을 뜻하지 않는다.
- 결과가 없는 시도는 결과 관련 필드가 `null`이다. 실행 중·만료·오래된 리비전 등
  원인을 이 사실만으로 확정하지 않는다. 모니터가 없으면 `monitor: null`과 빈 배열을 반환한다.
- 이벤트 한 행에는 누적 시도 횟수와 마지막 전달 결과만 있다. 개별 전달 시도 전체 이력은 아니다.
- 시각은 UTC, `durationSeconds`는 소수 초다. 원본 URL·호스트·본문·리소스 참조·리스 토큰은 출력하지 않는다.
  시도·이벤트 ID는 운영 상관관계 확인용이며 메트릭 레이블로 옮기지 않는다.
- 새 점검의 응답 바이트 수 0은 본문을 읽지 않았다는 뜻이다. 이전 버전의 본문 소비 이력은 원래 값을 유지한다.
- 보존 기한이 지난 점검·전달 완료 이력은 표시되지 않을 수 있다. 미전달 이벤트 보존은 변경하지 않는다.

DB 연결은 5초, SQL은 5초, 잠금 대기는 1초, 트랜잭션은 10초,
트랜잭션 내 유휴는 5초로 제한한다. 한 SELECT에서 조회하며 DB 행을 바꾸지 않는다.
SQL과 커밋이 모두 성공한 경우에만 JSON을 출력한다. 실패하면 원본 SQL·접속 오류를
노출하지 않고 고정 오류 문구와 0이 아닌 종료 코드를 반환한다. Docker 데몬 자체의
응답 시간은 이 DB 제한의 대상이 아니다.

조회 값은 `psql`의 `\bind`로 SQL 본문과 분리한다. 진단 트랜잭션에서만
`log_parameter_max_length=0`과 `log_parameter_max_length_on_error=0`을 적용하므로,
PostgreSQL 표준 SQL·오류 로그는 유지하면서 리소스 참조는 기록하지 않는다.
접속 역할에 `log_parameter_max_length` 설정 권한이 없으면 조회 전에 실패한다.
도구가 권한을 추가하거나 서버 전체·다른 연결의 로그 설정을 변경하지는 않는다.

이 제한은 PostgreSQL의 [클라이언트 연결 설정](https://www.postgresql.org/docs/18/runtime-config-client.html)을
사용한다. 로그 보호는 [매개변수 로그 설정](https://www.postgresql.org/docs/18/runtime-config-logging.html#GUC-LOG-PARAMETER-MAX-LENGTH)을
따른다. 자동 재시도·스케줄 등록·대상 재점검·이벤트 재전달은 하지 않는다.
