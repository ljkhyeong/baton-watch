---
name: baton-watch-observability
description: BATON WATCH의 로그·메트릭, Prometheus 경보, Grafana 대시보드 또는 런타임 진단을 변경할 때 사용한다.
---

# BATON WATCH 관측성

메트릭·경보는 [모니터링 절차](../../../docs/runbooks/monitoring-alerts.md), 프록시·공개 요청 감시는 [인그레스 점검](../../../docs/runbooks/ingress-monitoring.md)을 확인한다.

- 레이블은 결과 분류·프로토콜·작업 종류·상태처럼 값이 제한된 항목을 사용한다. 로그의 URL 사용자 정보·쿼리·프래그먼트를 제거하고 요청·시도 식별자로 관련 로그를 찾을 수 있게 한다.
- 필요한 실패 원인만 기존 분류에 맞춰 계측한다. 메트릭 이름·단위·의미가 바뀌면 해당 대시보드·경보 쿼리도 함께 수정한다.
- 점검 지연과 이벤트 전달 적체는 구분한다. 작업자 실행이 멈춰도 지연 지표가 갱신되는지 확인하고, 경보는 지속되는 영향과 수집 실패·지표 누락을 구분한다.
- 관리 상태·Prometheus 엔드포인트의 비공개 접근 조건을 유지한다.

변경한 계측의 레이블·민감 정보 제거·카운터·게이지 동작만 검증한다. 경보·대시보드 쿼리는 `./ops/tests/prometheus-rules-test.sh`, 프록시 감시는 `python3 ops/tests/gateway-test.py`로 관련 검사를 실행한다.
