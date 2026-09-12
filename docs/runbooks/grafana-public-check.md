# Grafana Cloud 외부 상태 점검

홈서버 전체 중단은 외부에서 감지해야 한다. Grafana Cloud Synthetic Monitoring의
공개 점검 위치와 Grafana Cloud 알림을 사용한다. 홈서버에 점검 프로그램을 설치할 필요는 없다.
현재는 [등록 요청 예시](../../ops/grafana/watch-public-check.json)를 준비한 상태이며,
계정 등록·실제 점검·알림 수신은 실행하지 않았다.

## 점검 범위와 비용

| 항목 | 설정 |
| --- | --- |
| 점검 이름 | `baton-watch-public` |
| 주소 | `https://watch.b4ton.com/api/v1/system/status` |
| 방식 | HTTP GET, 공개 점검 위치 1곳, 5분 간격, 제한 시간 5초 |
| 정상 조건 | HTTP 200, 응답의 `service: baton-watch`와 `status: UP` 문자열 |
| TLS | HTTPS 필수, 인증서 검증 유지, 리다이렉트 금지 |
| 캐시 | `_watch_probe` 쿼리를 매번 변경. 공개 상태 경로의 CDN 캐시 우회도 유지 |
| 수집 | 기본 메트릭만 사용. 계정의 메트릭·로그 사용량에 합산 |
| 초기 상태 | 비활성, 점검 위치 미지정. 연결 정보를 채운 뒤 활성화 |

2026-09-12 [공식 요금](https://grafana.com/pricing/) 기준 Free는 월 API 점검 100,000회를 제공한다.
위 설정은 30일 8,640회, 31일 8,928회다. 같은 조건의 서비스 3개는 31일 26,784회다.
다른 점검의 사용량도 합산되므로 Billing에서 **Free 요금제**와 계정 전체 사용량을 확인한다.
무료 제공량이 있는 Pro 요금제로 전환하지 않는다. 점검 위치나 빈도를 늘리면 사용량도 늘어난다.

공개 상태 응답을 외부 서비스가 읽는다. 자료 URL·리소스 참조·API 토큰은 넣지 않는다.
문자열 검사는 JSON 전체 구조를 검증하지 않으며 DB readiness·자료 점검·BATON 콜백도 확인하지 않는다.
설정 항목은 [Grafana HTTP 점검](https://grafana.com/docs/grafana-cloud/observe-and-act/testing/synthetic-monitoring/create-checks/checks/http/)을 따른다.

## 연결 절차

1. Grafana Cloud에서 Synthetic Monitoring을 열고 기존 `baton-watch-public` 점검이 있는지 확인한다.
   있으면 수정하고 중복 등록하지 않는다.
2. API 주소와 전용 access token은 Synthetic Monitoring의 Config에서 확인한다.
   이 토큰은 메트릭 전송용 토큰과 다르다. `Authorization: Bearer` 헤더로 전달하고
   토큰은 비밀 파일에서 읽는 API 도구를 사용한다. 저장소·명령 인자·로그에 넣지 않는다.
3. 사용할 **공개** 점검 위치 하나를 선택한다. API로는 `GET /api/v1/probe`에서
   `public: true`, `online: true`인 항목의 ID를 확인한다. 홈서버의 비공개 점검 위치는 사용하지 않는다.
4. JSON을 작업용 파일로 복사하고 `probes`를 선택한 ID 하나가 든 배열로 바꾼다.
   `enabled: false`를 유지한 채 스택의 Synthetic Monitoring API에 `POST /api/v1/check`로 등록한다.
   API를 사용하지 않으면 화면에서 위 표와 같은 HTTP 점검을 만들 수 있다.
5. 등록한 점검의 대상·5분 간격·공개 위치 1곳을 확인한 뒤 활성화한다. 다른 서비스에 적용할 때는
   그 서비스의 실제 상태 경로와 정상 응답을 먼저 확인한다. CAL 등에 WATCH 경로를 그대로 복사하지 않는다.

API 요청 형식은 [현재 API 명세](https://synthetic-monitoring-api.grafana.net/api/v1/openapi)와
[API 안내](https://grafana.com/docs/grafana-cloud/observe-and-act/testing/synthetic-monitoring/api-reference/)를 따른다.
토큰 확인 방법은 [리소스 연결 안내](https://grafana.com/docs/grafana-cloud/observe-and-act/testing/synthetic-monitoring/set-up/provision-synthetic-monitoring-resources/)에 있다.
예시의 빈 `probes`는 자리표시자이며, 실제 점검 위치를 지정하기 전에는 등록할 수 없다.

## 장애·복구와 인증서 만료 알림

Grafana Cloud의 점검 편집 화면에서 `Alerting → Per-check alerts`를 사용한다.

- 실패 횟수: 최근 15분에 2회 이상. 위치 1곳·5분 간격에서는 장애 감지까지 대략 5~10분과 경보 평가 시간이 필요하다.
- TLS 만료: 남은 기간 7일 이하. Cloudflare 앞단을 사용하면 공개 엣지 인증서를 확인하며 내부 인증서를 검사하지 않는다.
- 알림 정책: `namespace=synthetic_monitoring`, `job=baton-watch-public`에 해당하는 경보만 선택한다.
- 수신자: **Grafana Cloud의** Slack 또는 Telegram contact point를 연결하고 복구 알림도 받는다.
  홈서버의 Alertmanager를 경유하면 서버 전체 중단 중 알림을 보낼 수 없다.

임계치는 시작값이다. 지속 장애·복구 때 실제 알림을 확인하고 운영 요구에 맞춘다.
복구 알림은 최근 15분의 실패 횟수가 임계치 아래로 내려간 뒤 발생하므로 바로 해제되지 않을 수 있다.
점검 미실행·Grafana 자체 장애는 HTTP 실패 횟수와 별개이므로 이 설정이 모든 감시 장애를 보장하지는 않는다.
[공식 점검별 경보](https://grafana.com/docs/grafana-cloud/observe-and-act/testing/synthetic-monitoring/configure-alerts/configure-per-check-alerts/)를 기준으로 설정한다.

## 검증과 중지

JSON 형식과 현재 OpenAPI의 필드·타입을 확인한다. 인증키와 점검 위치가 없으면
실제 등록·외부 요청·경보 발생을 검증한 것으로 보고하지 않는다.
운영 연결 후 정상 응답, 점검 사용량, 장애·복구 알림을 확인한다.
중지할 때는 Grafana에서 이 점검을 비활성화한다. 기존 내부 메트릭 수집과 알림은 유지된다.
