# WATCH 경보 규칙

`ops/prometheus/watch-alerts.yml`은 기존 메트릭을 사용하는 경보 예시다. 외부
메트릭 전송, 수집기 배포, Alertmanager 연결, 외부 알림 수신자 설정은 포함하지
않는다. 아래 임계치는 운영 승인을 받기 전의 시작값이며 SLO가 아니다.

## 규칙과 대응

| 경보 | 조건 | 유지 시간 | 우선 확인 |
| --- | --- | --- | --- |
| `WatchScrapeFailed` | 수집 실패 | 2분 | WATCH 상태, 승인된 내부 수집 경로 |
| `WatchScrapeTargetMissing` | `baton-watch` 수집 대상 전체 누락 | 5분 | 수집 대상 설정과 서비스 발견 |
| `WatchScheduledFailures` | 예약 작업 오류가 최근 5분에 3회 이상 | 2분 | DB 연결·점유 실패, 유지보수 실패 |
| `WatchFinalizationFailures` | 점검·전달 완료 실패가 최근 5분에 합계 3회 이상 | 2분 | DB 잠금·연결 제한, 확정 트랜잭션 |
| `WatchLeaseRecoveryBurst` | 만료 리스 회수가 최근 5분에 합계 3회 이상 | 2분 | 작업자 중단과 DB 지연 |
| `WatchCheckScheduleDelayed` | 최근 점검 배치의 최대 일정 지연이 120초 초과 | 5분 | 대상 지연과 처리할 모니터 수 |
| `WatchDatabaseClockOffset` | JVM과 DB 시계 편차 절댓값이 1초 초과 | 5분 | 호스트 시간 동기화와 DB 왕복 지연 |
| `WatchEventDeliveryDelayed` | 전달 활성 환경의 미전달 이벤트가 있고 가장 오래된 이벤트가 900초 초과 | 5분 | 콜백 상태·인증과 재시도 백로그 |

개별 대상의 일시적 HTTP 오류에는 경보를 걸지 않는다. 카운터가 아직 만들어지지
않은 시작 상태나 프로세스 재시작으로 0으로 돌아가는 경우도 장애로 취급하지
않는다. 일정 지연은 **최근 완료된 배치**의 값이므로 전체 대기 작업의 최장 지연이나
멈춘 작업자 감시를 대신하지 않는다. 수집 대상 하나만 사라지고 다른 인스턴스가
남으면 전체 누락 경보는 발생하지 않으므로 실제 배포의 기대 인스턴스 수도 별도로
감시해야 한다.

## 적용 전 조건

- Prometheus 수집 작업의 `job_name`을 `baton-watch`로 지정한다.
- 콜백 전달을 켠 대상에만 수집 레이블 `event_delivery_enabled: "true"`를 붙인다.
  이 레이블은 WATCH가 내보내는 설정값이 아니다. 전달이 꺼진 환경과 레이블이 없는
  환경에서는 전달 지연 경보를 평가하지 않는다. 배포 설정과 레이블을 함께 관리한다.
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
전달 비활성, 카운터 초기화와 수집 대상 누락을 검사한다. 이미지가 없으면 최초
실행 시 내려받으며 검사 컨테이너는 `--network none`으로 실행한다. 이 검사는
실제 메트릭 수집이나 외부 알림 도착을 증명하지 않는다.

규칙 문법과 검사 형식은 Prometheus 공식
[경보 규칙](https://prometheus.io/docs/prometheus/latest/configuration/alerting_rules/)과
[규칙 단위 테스트](https://prometheus.io/docs/prometheus/latest/configuration/unit_testing_rules/)를 따른다.
