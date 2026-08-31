# WATCH 인바운드 요청 경로 점검

## 제공 범위

NGINX의 `/health`는 프록시 프로세스가 응답하는지만 확인한다. WATCH와의 연결이
끊기거나 공개 터널 경로가 잘못되어도 이 생존 확인은 성공할 수 있다. WATCH의
내부 메트릭 수집 성공 역시 공개 요청 성공을 보장하지 않는다.

이 문서와 설정은 승인된 기존 수집 환경에 적용할 템플릿이다. WATCH 운영 Java,
관리 포트, 배포 Compose, 실제 수집기·알림 수신 경로는 변경하지 않는다.
추가 유료 서비스는 필요하지 않지만 기존 서버의 자원·네트워크 여유를 확인해야 한다.

## 설정과 경로

- [Blackbox 모듈](../../ops/blackbox/watch-probes.yml): `watch_gateway`, `watch_public`.
- [Prometheus 수집 예시](../../ops/prometheus/watch-ingress-scrape.yml): 기존 설정에
  `scrape_configs` 항목과 `rule_files` 경로를 병합한다. 기존 전역 설정을 덮어쓰지 않는다.
- [선택적 경보](../../ops/prometheus/watch-ingress-alerts.yml): 두 경로가 준비된 뒤
  수집 작업과 함께 적용한다. 기존 WATCH 경보 11개와 분리되어 있다.

| 구분 | 점검 경로 | 확인 범위 |
| --- | --- | --- |
| `gateway` | 내부 NGINX → WATCH의 `/api/v1/system/status` | 프록시와 WATCH 사이의 HTTP 요청 |
| `public` | 공개 HTTPS → 터널 → NGINX → 같은 상태 경로 | DNS·TLS·공개 인그레스와 프록시 요청 |

두 경로 모두 60초마다 GET을 한 번 수행한다. 점검은 최대 5초, 결과 수집은 최대
8초다. HTTP 200과 JSON `status: UP`을 요구하며 읽는 본문은 16 KiB로 제한한다.
리다이렉트를 따라가지 않으며 공개 모듈은 TLS를 필수로 사용하고 기본 인증서 검증을
유지한다. IPv4만 검사하므로 IPv6 가용성을 증명하지는 않는다.

상태 경로는 인증이 필요하지 않다. Bearer 토큰·실제 리소스 참조를 넣거나 PUT을
호출하지 않는다. 이 점검은 DB readiness, 모니터 API 인증·동기화, 개별 리소스 URL,
BATON 콜백 전달까지 검증하지 않는다. 429도 요청 실패로 처리하므로 단발성 제한이
아닌 지속 실패에만 경보가 발생한다. 점검 역시 NGINX 상태 경로 예산을 사용한다.

## 적용 전 필수 조건

1. 기존 Blackbox Exporter의 버전과 자원 여유를 확인한다. 템플릿은 공식
   `prom/blackbox-exporter:v0.28.0`으로 검사했으며 시험 이미지 다이제스트는
   [격리 Compose](../../ops/tests/compose.gateway-test.yml)에 고정되어 있다.
2. `https://watch.example.invalid`를 승인된 WATCH 공개 주소로, 내부 주소와
   `blackbox-exporter:9115`를 승인된 네트워크의 실제 주소로 바꾼다. 한 WATCH 배포의
   두 경로를 위한 예시다. 여러 배포에서는 고유한 고정 인스턴스 이름과 기대 대상
   목록을 별도로 정하고 경보의 누락 조건도 맞춘다.
3. Exporter는 승인된 수집기만 접근할 수 있는 비공개 주소에 바인딩한다.
   `/probe`는 호출자가 임의 목적지를 지정할 수 있으므로 인터넷·공개 터널에
   노출하지 않는다. `/config`, `/logs`, `/` 화면도 공개하지 않는다.
4. Exporter의 이그레스는 승인된 DNS와 두 WATCH 경로로 제한한다. 이 도구를
   RoleResource 검사기로 재사용하지 않는다. WATCH 검사기의 SSRF·주소 고정 정책이
   Blackbox Exporter에 적용되는 것은 아니다.
5. `--history.limit=0`으로 점검 이력을 끈다. `--log.prober=error`만으로 URL 비노출이
   보장되지는 않으므로 전용 Exporter의 로그를 저장·외부 전송하지 않는다. 컨테이너는
   `logging.driver: none`을 사용하고 `debug=true` 조회 결과를 보관하지 않는다.
   비루트·읽기 전용 실행과 CPU·메모리·프로세스 상한도 배포 환경에서 적용한다.
6. 수집 예시의 `instance`는 URL이 아니라 `watch-gateway`, `watch-public`이다.
   URL은 내부 요청 매개변수로만 사용한다. 허용하는 측정값도 `probe_success`,
   `probe_http_status_code`, `probe_duration_seconds` 세 개로 제한한다.
   Prometheus가 자동으로 만드는 `up` 등 수집 자체의 지표는 유지된다.
7. 경보 수신·해제와 주기를 승인한 뒤 적용한다. 수집기·Exporter·Alertmanager의
   장애까지 같은 점검이 보장하지 않으므로 기존 관측 시스템의 운영 기준도 유지한다.

## 경보와 대응

| 경보 | 조건·유지 시간 | 우선 확인 |
| --- | --- | --- |
| `WatchIngressRequestFailed` | 수집 대상이 있지만 정상 응답을 2분간 확인하지 못함 | 수집 실패 보조 경보와 경로별 DNS·TLS·터널·NGINX·WATCH 응답 |
| `WatchIngressProbeUnavailable` | Exporter 수집 실패 또는 성공 지표 누락이 2분간 지속 | Exporter·수집 주소·모듈·허용 지표 설정 |
| `WatchIngressTargetMissing` | 내부 또는 공개 수집 대상 자체가 5분간 없음 | 수집 작업·고정 경로 레이블 |

주 경보는 같은 대상의 `up == 1`과 `probe_success == 1`이 함께 확인된 경우만
정상으로 제외한다. `up`의 고정 레이블을 사용하므로 요청 실패·수집 실패·성공 지표
누락이 교차해도 2분 대기 시간이 초기화되지 않는다. 정상 응답이 확인되면 해제되고,
이후 새 실패에는 다시 2분을 기다린다. `up` 자체가 사라지면 주 경보가 아니라
대상 누락 경보의 5분 조건으로 확인한다.

수집 실패 보조 경보가 주 경보와 함께 발생하면 Exporter·수집 설정부터 확인한다.
주 경보만으로 WATCH의 장애가 확정되는 것은 아니다. 수집이 정상인데 공개 경로만
실패하면 공개 DNS·TLS·터널을, 두 경로가 함께 실패하면 NGINX와 WATCH 연결을
우선 확인한다. 이는 조사 순서이며 원인 확정은 아니다. 경보의 시각에는 수집·평가
간격이 추가된다. 생존 확인을 DB나 공개 네트워크 상태에 종속시키거나 실패를
우회하려고 관리 포트를 공개하지 않는다.

## 검증과 원복

```bash
./ops/tests/prometheus-rules-test.sh
python3 -B ops/tests/gateway-test.py
```

첫 명령은 Prometheus 설정 문법과 장애·복구·수집 실패·대상 누락 경보를 검사한다.
실패 종류가 교차해도 주 경보가 유지되는지, 정상 응답 후 해제되는지, 복구 직후의
짧은 오류에는 다시 경보가 발생하지 않는지도 확인한다.
두 번째는 실제 NGINX·Blackbox Exporter와 HTTP 대역만 격리 실행한다. 정상 요청,
공개 모듈의 평문 거부, 리다이렉트 비추적, 잘못된 본문, 백엔드 중단·복구와 프록시
생존 확인의 차이를 확인하고 시험 환경을 제거한다. 실제 WATCH·공개 TLS·Cloudflare·
알림 도착을 시험한 것은 아니다.

적용 전에는 실제 Exporter의 `--config.check`와 기존 Prometheus의 `promtool check
config`를 통과시킨다. 승인된 장애·복구 시험으로 수신·해제를 확인해야 운영 검증이
완료된다. 원복 시 기존 수집 설정·규칙 파일을 함께 복원하고 재검증한다. 점검 대상만
제거하고 누락 경보를 남기지 않는다. 기존 WATCH 경보와 생존 확인은 유지한다.

모듈과 실행 옵션은 공식 [Blackbox Exporter](https://github.com/prometheus/blackbox_exporter/tree/v0.28.0),
[HTTP 모듈 설정](https://github.com/prometheus/blackbox_exporter/blob/v0.28.0/CONFIGURATION.md)을 따른다.
주 경보의 시계열 유지·제외 조건은 Prometheus의
[경보 규칙](https://prometheus.io/docs/prometheus/latest/configuration/alerting_rules/)과
[집합 연산자](https://prometheus.io/docs/prometheus/latest/querying/operators/#logical-set-binary-operators)를 따른다.
