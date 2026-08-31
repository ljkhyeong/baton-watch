# WATCH 경보 규칙

## 대시보드 템플릿

[watch-overview.json](../../ops/grafana/watch-overview.json)은 기존 Prometheus를
조회하는 Grafana 대시보드다. [Grafana 가져오기](https://grafana.com/docs/grafana/latest/visualizations/dashboards/build-dashboards/import-dashboards/)에서
JSON 파일을 올리고 Prometheus 데이터 소스와 인스턴스를 선택한다. 별도 플러그인은
필요하지 않으며 이 저장소는 Grafana·수집기·외부 계정을 배포하지 않는다.

12개 패널은 수집 상태, 일정 지연, 미전달 이벤트 수·경과 시간, 결과별 시도율,
평균 HTTP 소요 시간, 완료 실패, 리스 회수, 예약 완료 횟수, 시계 편차, DB 연결 풀,
진행 중인 외부 요청을 보여준다. 임계치는 운영 승인 없이 자동 알림으로 설정하지 않는다.

- 같은 DB의 이벤트 적체를 인스턴스 수만큼 합산하지 않는다.
- 평균 HTTP 시간은 타이머 합계/횟수이며 p95·p99가 아니다.
- 존재하지 않는 카운터·수집 누락·시도 없는 평균은 데이터 없음으로 남긴다.
- 점검·전달 비활성 상태에서도 이력·게이지를 표시할 수 있다. 장애 판단에는 아래
  수집 레이블과 경보의 활성 조건을 함께 적용한다.
- 데이터 소스의 `job_name`은 `baton-watch`여야 한다. 관리 포트 공개나 WATCH의
  외부 메트릭 전송 기능을 추가하지 말고 승인된 내부 수집 경로를 사용한다.

## 경보 범위

프록시와 공개 HTTPS의 실제 요청 경로는 [인바운드 점검 절차](ingress-monitoring.md)의
Blackbox Exporter 수집 템플릿과 선택적 경보 3개로 별도 확인한다. 아래 WATCH 내부
지표 11개 규칙만으로는 프록시 경로 장애를 감지할 수 없다.

`ops/prometheus/watch-alerts.yml`은 기존 메트릭을 사용하는 경보 예시다. 외부
메트릭 전송, 수집기 배포, Alertmanager 연결, 외부 알림 수신자 설정은 포함하지
않는다. 아래 임계치는 운영 승인을 받기 전의 시작값이며 SLO가 아니다.

## 규칙과 대응

| 경보 | 조건 | 유지 시간 | 우선 확인 |
| --- | --- | --- | --- |
| `WatchScrapeFailed` | 수집 실패 | 2분 | WATCH 상태, 승인된 내부 수집 경로 |
| `WatchScrapeTargetMissing` | `baton-watch` 수집 대상 전체 누락 | 5분 | 수집 대상 설정과 서비스 발견 |
| `WatchScheduledFailures` | 예약 작업 오류가 최근 5분에 3회 이상 | 2분 | DB 연결·점유 실패, 유지보수 실패 |
| `WatchCheckWorkerStalled` | 시작 후 10분이 지난 수집 정상·점검 활성 인스턴스에서 최근 5분간 점검 배치 완료 없음 | 2분 | 점검 스케줄러 정지·장시간 실행, 지표 누락 |
| `WatchEventDeliveryWorkerStalled` | 위 조건에서 전달 활성 인스턴스의 전달 배치 완료 없음 | 2분 | 전달 스케줄러와 콜백 대기 |
| `WatchMaintenanceStalled` | 위 조건에서 유지보수 다섯 메서드 중 하나라도 실행 완료 없음 | 2분 | 유지보수 스케줄러, DB 대기, 백로그 지표 갱신 |
| `WatchFinalizationFailures` | 점검·전달 완료 실패가 최근 5분에 합계 3회 이상 | 2분 | DB 잠금·연결 제한, 확정 트랜잭션 |
| `WatchLeaseRecoveryBurst` | 만료 리스 회수가 최근 5분에 합계 3회 이상 | 2분 | 작업자 중단과 DB 지연 |
| `WatchCheckScheduleDelayed` | 점검 활성 인스턴스에서 최근 점검 배치의 최대 일정 지연이 120초 초과 | 5분 | 대상 지연과 처리할 모니터 수 |
| `WatchDatabaseClockOffset` | JVM과 DB 시계 편차 절댓값이 1초 초과 | 5분 | 호스트 시간 동기화와 DB 왕복 지연 |
| `WatchEventDeliveryDelayed` | 전달 활성 환경의 미전달 이벤트가 있고 가장 오래된 이벤트가 900초 초과 | 5분 | 콜백 상태·인증과 재시도 백로그 |

개별 대상의 일시적 HTTP 오류에는 경보를 걸지 않는다. 미실행 경보는
`process_uptime_seconds > 600`일 때만 평가하여 시작·재시작 직후를 제외한다.
오류 카운터가 아직 없는 상태와 카운터 초기화 자체를 장애로 취급하지 않는다.
완료 실패와 리스 회수는 점검·전달 카운터의 증가량을 각각 계산한 뒤
합산한다. 계산 중의 `watch_worker` 레이블은 이름이 제거된 시계열의 충돌을
막기 위한 것으로, 최종 경보에는 남기지 않는다. 한쪽 카운터만 존재해도 평가하며
두 카운터가 함께 존재하고 합계만 임계치를 넘는 사례도 테스트한다.
일정 지연은 **최근 완료된 배치**의 값이므로 전체 대기 작업의 최장 지연을
나타내지 않는다. 작업 중단은 별도의 미실행 경보로 감시한다. 수집 대상 하나만 사라지고 다른 인스턴스가
남으면 전체 누락 경보는 발생하지 않으므로 실제 배포의 기대 인스턴스 수도 별도로
감시해야 한다.

## 미실행 경보의 판단 기준

Spring의 `tasks_scheduled_execution_seconds_count` 증가량을 사용하며 별도
감시 스레드·DB 조회·자체 heartbeat 지표는 추가하지 않는다. 할 일이 없는 빈 배치도
정상 완료 횟수를 남기므로 유휴 상태에서는 경보가 발생하지 않는다. 성공·실패
레이블을 먼저 합산하여 오류로 종료된 작업을 미실행으로 분류하지 않으며, 반복
실패는 기존 `WatchScheduledFailures`가 담당한다.

점검과 전달은 각각 `checkDueMonitors`, `deliverPendingEvents`를 확인한다.
유지보수는 `markStaleProjections`, `purgeAttemptHistory`,
`updateDatabaseClockOffset`, `purgeDeliveredEventHistory`,
`refreshEventDeliveryBacklog` 다섯 메서드 모두의 증가량이 있어야 정상이다.
메서드별로 합산한 뒤 개수를 세므로 한 작업의 성공·오류 시계열이 다른 작업의
중단을 가리지 않는다.

앞의 세 유지보수 메서드는 `MonitoringMaintenanceScheduler`, 뒤의 두 메서드는
`EventDeliveryMaintenanceScheduler`의 `code_namespace`로 구분한다. 점검 중지 중에도
유지보수 다섯 메서드와 활성화된 이벤트 전달의 미실행 경보는 계속 평가한다.

`up == 1`인 인스턴스를 기준으로 정상 실행 시계열을 제외하므로 타이머가 한 번도
생성되지 않았거나 사라진 경우도 감지한다. 프로세스 가동 시간 지표는 필수이며,
그 지표까지 없는 환경은 이 규칙의 시작 유예를 평가할 수 없다. 수집 실패는
기존 수집 경보가 담당한다. 기본 설정에서 마지막 관측 증가 후 약 5분과 유지
시간 2분이 지나면 경보가 발생하며 실제 지연에는 수집·평가 간격이 더해진다.

## 적용 전 조건

- Prometheus 수집 작업의 `job_name`을 `baton-watch`로 지정한다.
- `WATCH_CHECK_ENABLED=false`로 재시작한 대상에는 수집 레이블
  `check_enabled: "false"`를 붙인다. 이때만 점검 미실행·일정 지연 경보를 제외한다.
  레이블이 없거나 `"true"`이면 점검 활성으로 취급한다. 이 레이블은 WATCH가
  내보내는 설정값이 아니므로 중지·재개 때 배포 설정과 함께 변경한다.
- 미실행 규칙은 기본 점검·전달 폴링 1초와 유지보수 1분을 기준으로 한다.
  예약 주기를 늘리면 `[5m]` 조회 구간을 실제 주기·실행 예산·수집 간격보다
  충분히 크게 늘리고 시작 유예 `600`초와 유지 시간도 함께 검토한다.
  WATCH 설정에서 규칙으로 주기를 자동 전달하지 않는다.
- `process_uptime_seconds`와 예약 실행 메트릭의 `code_namespace`,
  `code_function`, `outcome` 레이블을 수집 과정에서 제거하지 않는다. 유지보수
  메서드를 추가·삭제·변경하면 규칙의 메서드 목록과 기대 개수 `5`를 같이 갱신한다.
- 콜백 전달을 켠 대상에만 수집 레이블 `event_delivery_enabled: "true"`를 붙인다.
  이 레이블은 WATCH가 내보내는 설정값이 아니다. 전달이 꺼진 환경과 레이블이 없는
  환경에서는 전달 지연·전달 미실행 경보를 평가하지 않는다. 배포 설정과 레이블을 함께 관리한다.
- 관리 포트 `8081`은 컨테이너 루프백을 유지한다. 예시 적용을 위해 호스트에
  포트를 공개하거나 인증 없는 원격 접근을 허용하지 않는다. 승인된 내부 수집
  경로를 먼저 마련한다.
- 기존 Prometheus의 `rule_files`에 규칙을 추가하고 승인된 수신 경로를 연결한다.
  저장소 스크립트는 이 작업을 실행하지 않는다.
- 실제 장애·복구 시험으로 임계치, 경보 지연과 수신·해제를 확인한 뒤 배포 증거를
  남긴다. 메트릭이나 알림 레이블에 대상 URL·호스트·리소스 참조·이벤트 ID·예외
  메시지를 추가하지 않는다.

## 로컬 검증

```bash
./ops/tests/prometheus-rules-test.sh
```

고정된 공식 Prometheus 이미지의 `promtool`로 문법, 지속 장애 감지, 복구 해제,
전달 비활성, 카운터 초기화와 수집 대상 누락을 검사한다.
`watch-workers-test.yml`은 시작 유예, 타이머 누락, 유휴 배치, 다중 인스턴스,
일부 유지보수 중단과 복구, 점검 중지 중의 전달·유지보수 경보 유지도 확인한다. 이미지가 없으면 최초
실행 시 내려받으며 검사 컨테이너는 `--network none`으로 실행한다. 이 검사는
실제 메트릭 수집이나 외부 알림 도착을 증명하지 않는다.

같은 명령은 대시보드 JSON에서 실제 쿼리를 읽어 `promtool`로 평가한다. 두
인스턴스의 시계열 유지, DB 적체 중복 합산 방지와 수집 누락 시 빈 결과를 확인한다.
Grafana 서버 배포나 실제 데이터 소스 연결을 검증한 것은 아니다.

규칙 문법과 검사 형식은 Prometheus 공식
[경보 규칙](https://prometheus.io/docs/prometheus/latest/configuration/alerting_rules/)과
[규칙 단위 테스트](https://prometheus.io/docs/prometheus/latest/configuration/unit_testing_rules/)를 따른다.
증가량 계산과 카운터 초기화 처리는 공식
[조회 함수](https://prometheus.io/docs/prometheus/latest/querying/functions/#increase)를 따른다.
