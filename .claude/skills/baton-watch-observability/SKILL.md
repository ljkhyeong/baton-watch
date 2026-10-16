---
name: baton-watch-observability
description: BATON WATCH의 로그·메트릭 계측, 민감 정보가 남는 로거 차단, Prometheus 경보 규칙·promtool 시나리오, Grafana 대시보드, Alertmanager 알림, NGINX·Blackbox 감시를 바꿀 때 사용한다. `MonitoringMetrics`, `Metered*` 래퍼, `RuntimeSafetyEnvironmentPostProcessor`의 고정 로거가 대상이다.
---

# BATON WATCH 관측성

메트릭·경보는 [모니터링 절차](../../../docs/runbooks/monitoring-alerts.md), 프록시·공개 요청 감시는 [인그레스 점검](../../../docs/runbooks/ingress-monitoring.md)을 확인한다.
로그·메트릭 요구사항은 [PRD-0003 이력 보존과 로그·메트릭](../../../docs/PRD/0003_monitoring-mvp/spec.md)과 [PRD-0004 로그·메트릭](../../../docs/PRD/0004_health-change-event-delivery/spec.md)이 기준이다.

## 위치

| 역할 | 위치 |
| --- | --- |
| 메트릭 | `bootstrap/.../MonitoringMetrics`, `MeteredUrlChecker`, `MeteredHealthChangeEventSender`, `MeteredCheckWorkPersistence`, `MeteredHealthChangeEventDeliveryPersistence` |
| 고정 로거 | `bootstrap/.../RuntimeSafetyEnvironmentPostProcessor` |
| 로그 테스트 | `InboundLoggingIntegrationTest`, `ApacheHttpLoggingConfigurationTest`, `ScheduledTaskObservationTest` |
| 경보·시나리오 | `ops/prometheus/*-alerts.yml`과 짝을 이루는 `*-test.yml` |
| 대시보드·알림 | `ops/grafana/*.json`, `ops/alertmanager/` |
| 프록시·외부 감시 | `ops/nginx/watch-gateway.conf`, `ops/blackbox/watch-probes.yml` |
| 운영 로그 감사 | `ops/staging-log-redaction-audit.sh` |

## 규칙

- 레이블에는 결과 분류·프로토콜·작업 종류·상태처럼 값이 제한된 항목만 사용한다. URL·호스트·IP·리소스 참조·이벤트 ID·예외 메시지는 넣지 않는다.
- 로그에서 URL 사용자 정보·쿼리·프래그먼트를 제거하고 응답 본문·토큰·쿠키·인가 헤더를 남기지 않는다. 요청·시도 식별자로 관련 로그를 찾을 수 있게 한다.
- 프레임워크·라이브러리 로거가 원문을 남기면 `RuntimeSafetyEnvironmentPostProcessor` 고정 목록에 추가한다. 외부 설정이 상위 범주나 개별 로거를 TRACE로 바꿔도 차단되는지 확인한다.
- 경보에 쓰는 카운터는 시작 시 0으로 등록해 첫 실패의 증가량이 빠지지 않게 한다. 메트릭 등록 오류가 작업자 실행을 막지 않게 기존 격리를 유지한다.
- 필요한 실패 원인만 기존 분류에 맞춰 계측한다. 메트릭 이름·단위·의미가 바뀌면 대시보드·경보 쿼리와 시나리오도 함께 수정한다.
- 점검 지연과 이벤트 전달 적체는 구분한다. 작업자가 멈춰도 지연 지표가 갱신되는지 확인하고, 경보는 지속되는 영향과 수집 실패·지표 누락을 구분한다.
- 관리 상태·Prometheus 엔드포인트의 비공개 접근 조건을 유지한다. 외부 알림 수신·Grafana 가져오기는 직접 확인한 범위만 보고한다.

## 검증

- 계측은 레이블 집합·민감 정보 제거·카운터 초기값·게이지 갱신만 검증한다. 로그는 합성 입력으로 원문이 출력되지 않음을 확인한다.

```bash
python3 ops/run-validation.py run --label <주제>-metrics -- \
  ./gradlew :bootstrap:test --tests '*Metric*' --tests '*Metered*'
python3 ops/run-validation.py run --label <주제>-alerts -- ./ops/tests/prometheus-rules-test.sh
python3 ops/run-validation.py run --label <주제>-alertmanager -- python3 ops/tests/alertmanager-config-test.py
python3 ops/run-validation.py run --label <주제>-gateway -- python3 ops/tests/gateway-test.py
```

경보 검사는 Grafana 대시보드 쿼리(`grafana-dashboard-test.py`)도 함께 확인한다. 위 ops 검사 3개는 Docker를 사용한다.
새 경보에는 발생·대기·해제·수집 실패·지표 누락 시점을 promtool 시나리오로 추가한다.
