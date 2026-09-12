# Grafana Cloud 무료 메트릭 연결

기존 WATCH 메트릭을 Prometheus의 `remote_write`로 Grafana Cloud Free에 보낸다.
[수집·전송 설정](../../ops/prometheus/watch-cloud-free.yml)과
[기존 대시보드](../../ops/grafana/watch-overview.json)를 사용한다.
계정 생성, 수집기 설치, 외부 전송과 알림 수신은 아직 설정하지 않았다.
홈서버 전체 중단 감지는 메트릭 전송과 별개다.
[외부 상태 점검](grafana-public-check.md)은 Grafana의 공개 점검 위치에서 실행하며 클라우드에서 알림을 보낸다.

## 비용과 전송 범위

2026-09-07 확인한 [공식 요금](https://grafana.com/pricing/)은 Free 월 0원,
메트릭 1만 개, 보관 14일이며 결제 카드가 필요 없다. **무료 제공량이 있는 Pro와
Free는 다른 플랜이다.** 계정의 Billing에서 Free를 확인하고 유료 전환을 하지 않는다.
수집기에는 기존 서버의 자원과 네트워크를 사용한다. 서버·네트워크 비용까지 0원이라는 뜻은 아니다.

- WATCH 작업만 60초마다 수집한다. 다른 서비스의 지표는 이 전송 연결에서 제외한다.
- 기존 대시보드와 작업자 경보에 필요한 WATCH·예약 실행·가동 시간·DB 연결 지표만 수집한다.
  로그·트레이스·대상 URL·응답 본문은 전송 대상이 아니다.
- 한 번에 수집하는 본문은 1MB, 필터 적용 후 샘플은 2,000개로 제한한다.
  상한 초과 시 해당 수집이 실패한다. 샘플 상한은 월간 사용량이나 계정 전체의 한도를 보장하지 않는다.
- 전송 제한(HTTP 429)은 Prometheus가 재시도한다. 재시도 대기 간격은 1초부터 최대 1분까지
  늘리며 전송 동시성은 1개다. 1시간이 지난 샘플은 전송 대상에서 제외한다.
- Billing/Usage에서 계정 전체의 시계열 수와 수집량을 확인한다. 무료 한도에 가까워지면
  전송 대상을 줄이거나 이 `remote_write`를 제거하고 기존 수집만 유지한다.
- 유료 URL 점검·웹훅 대행·관리형 DB·Cloudflare 유료 제한 기능은 추가하지 않는다.

## 연결

1. Grafana Cloud Free 계정에서 Prometheus의 전송 주소와 Metrics 사용자 ID를 확인한다.
   해당 스택에 `metrics:write`만 허용하는 토큰을 만들고 로컬 비밀 파일에 저장한다.
   토큰을 Git, 채팅, 명령 인자나 Prometheus YAML에 넣지 않는다.
2. 설정 예시의 `url`과 `username`을 계정 값으로 바꾼다. 비밀 파일은 수집기 안의
   `/run/secrets/grafana-cloud-metrics-token`에 읽기 전용으로 연결하고 수집기 사용자만 읽게 한다.
3. 기존 Prometheus 설정에 `scrape_configs`와 `remote_write` 항목을 합친다.
   같은 `job_name`이나 전송 연결이 이미 있다면 수정하고 중복 등록하지 않는다.
   기존 전역 설정과 경보 설정을 유지한다.
4. 이 수집 작업의 `127.0.0.1:8081`은 WATCH와 같은 네트워크 네임스페이스에서 접근한다.
   별도 Docker 수집기라면 WATCH 컨테이너의 네트워크를 공유해야 한다.
   관리 포트를 호스트·공개 터널에 노출하지 않는다. WATCH가 재생성되면 수집기도 함께 재생성한다.
5. `instance`는 호스트명 대신 고정된 비식별 이름을 사용한다. 점검·전달 활성 레이블은
   실제 `WATCH_CHECK_ENABLED`와 `WATCH_EVENT_DELIVERY_ENABLED`에 맞춘다.
   여러 인스턴스는 서로 다른 `instance`를 사용하며 계정 사용량을 합산해 확인한다.
6. 실행 환경의 `promtool check config`로 비밀 파일까지 검사한 뒤 Prometheus를 재시작한다.
   Grafana Explore에서 `up{job="baton-watch"}`와 `baton_watch_check_schedule_delay_seconds`를 확인한다.
7. 기존 대시보드 JSON을 가져오고 Grafana Cloud Prometheus 데이터 소스를 선택한다.
   경보는 [기존 규칙과 활성 조건](monitoring-alerts.md)을 적용하고 알림 수신·해제를 별도로 확인한다.

수집기 설치나 계정 설정이 없으면 전송은 시작되지 않는다. 저장소에는 실제 토큰·계정 주소가 없으며,
기본 URL의 `.invalid`는 실제 서비스에 연결되지 않는 자리표시자다.

429가 계속되면 계정 사용량을 확인하고 전송 지표를 줄인다. 재시도는 무료 한도를 늘리지 않는다.
401·403은 재시도로 해결되지 않으므로 Metrics 사용자 ID·토큰 권한·만료 여부를 확인한다.
재시도 설정은 [Prometheus 기본 기능](https://prometheus.io/docs/prometheus/latest/configuration/configuration/#remote_write)을 사용한다.

## 검증과 원복

```bash
./ops/tests/prometheus-rules-test.sh
```

설정 문법, 기존 대시보드 쿼리와 경보 규칙을 검사한다. 실제 계정 인증·메트릭 수신·알림은
검사하지 않는다. 외부 전송을 중단할 때는 `grafana-cloud-free` 전송 항목만 제거하고
Prometheus를 재시작한다. 기존 로컬 수집·경보를 계속 사용할 수 있다.

공식 [Prometheus 연결](https://grafana.com/docs/grafana-cloud/observe-and-act/send-data/metrics/metrics-prometheus/prometheus-config-examples/integration-guide/)과
[사용량 제한](https://grafana.com/docs/grafana-cloud/platform/pricing-and-usage/usage-limits/)을 기준으로 계정 설정을 확인한다.
