# BATON WATCH 인계

최종 수정일: 2026-09-12

## 현재 작업

- `878b3a0`에서 공개 상태 JSON의 중복 필드가 마지막 정상 값으로 덮여 검사가 통과하는 문제를 수정했다.
  공개 스모크·이벤트 전달 사전 검사의 공통 파서에서 중복을 거부하고 후속 요청을 중단한다.
  스모크 테스트 대역이 지정한 JSON에 `}`를 추가하던 오류도 수정했다. 두 배포 런북에 반영했으며
  추가 의존성·비용은 없다. 실제 공개 HTTPS·수신기 연결·운영 배포는 미실행이다.
- `6efdaca`에서 공급망 검사 명령이 성공했지만 이미지 SBOM 보고서가 없어도 완료로 처리하는 문제를 수정했다.
  JAR·이미지 검사 직후 보고서 파일과 내용 유무를 확인하고, 기존 검사 도구의 실패도 그대로 전파한다.
  후속 검사·실패 보고서 보존을 유지하며 불완전한 결과에는 완료 체크섬·배포용 JAR을 만들지 않는다.
  [배포 검사 절차](docs/runbooks/staging-deployment.md)와 README에 반영했다. 검사 인자·이미지·정책·추가 비용은 그대로이며 운영 배포는 미실행이다.
- `9f91333`에서 Tomcat이 잘못된 요청을 거부하면서 주소·쿼리를 INFO 로그에 남기는 문제를 수정했다.
  Spring 진입 전의 요청 파싱 로거를 기존 고정 설정에 추가했다. HTTP 400 거부, 정상 API 처리와
  WATCH의 오류 종류 로그는 유지한다. [점검 기준](docs/PRD/0003_monitoring-mvp/spec.md)과
  [로그 감사 절차](docs/runbooks/staging-deployment.md)에 반영했다. 추가 의존성·비용은 없으며 운영 배포는 미실행이다.
- `6a3ba2c`에서 Spring 상세 로그에 요청 URL·토큰·응답의 리소스 참조·예외 원문이 기록되는 문제를 수정했다.
  실제 요청에서 원문을 출력한 MVC·Security 로거 6개를 기존 고정 설정에 추가했다. 외부 상세 설정보다 우선하며
  WATCH의 오류 종류 로그와 인증·응답 동작은 유지한다. [점검 기준](docs/PRD/0003_monitoring-mvp/spec.md)과
  [로그 감사 절차](docs/runbooks/staging-deployment.md)에 보호 범위를 반영했다. 추가 의존성·비용은 없으며 실제 운영 배포는 미실행이다.
- `689332e`에서 로그가 비거나 일부 비밀 파일에 값이 없어도 로그 감사가 통과하는 문제를 수정했다.
  빈 로그·공백뿐인 로그와 개별 비밀 파일의 값 누락을 종료 코드 1로 거부한다. 오류에 파일 경로·비밀값을 출력하지 않는다.
  [로그 감사 절차](docs/runbooks/staging-deployment.md)에 입력 기준을 반영했다.
  추가 의존성·비용과 애플리케이션 동작 변경은 없으며 실제 운영 로그 감사는 미실행이다.
- `2d9c0a0`에서 검증 도구 취소 후 하위 명령이 남고 결과 기록이 누락되는 문제를 수정했다.
  `SIGINT`·`SIGTERM`·`SIGHUP`를 실행한 명령의 프로세스 그룹에 전달하고, 종료를 최대 5초 기다린 뒤 남은 명령을 강제 종료한다.
  하위 명령이 0으로 종료해도 중단 결과는 실패로 기록한다. [개발 검증 절차](docs/runbooks/development-validation.md)에
  종료 코드와 정리 범위를 반영했다. 추가 의존성·비용과 애플리케이션 동작 변경은 없다.
- `c3919a2`에서 첫 실패 뒤 생성되는 카운터의 증가량이 경보에서 빠지는 문제를 보완했다.
  점검·전달 완료 실패와 리스 회수 카운터 4개를 시작 시 0으로 등록하고 기존 메트릭 오류 격리를 사용한다.
  경보식·레이블·시계열 최대 개수는 그대로다. [경보 절차](docs/runbooks/monitoring-alerts.md)에 실패 전 0의 수집 조건을 명시했다.
  추가 의존성·비용은 없으며 실제 운영 수집·외부 알림·배포는 미실행이다.
- `99f8f2a`에서 진단·백업의 SQL·셸·복원 Compose만 바뀌면 영속성 테스트가 생략되는 문제를 수정했다.
  테스트가 읽는 운영 파일 5개를 Gradle 입력으로 등록했다. 해당 파일 변경은 검사에 반영하고 문서 변경은 기존 결과를 재사용한다.
  [개발 검증 절차](docs/runbooks/development-validation.md)에 새 운영 파일의 입력 등록 기준을 정리했다.
  추가 의존성·비용과 애플리케이션 동작 변경은 없다.
- `3e9f43a`에서 복구 도구가 응답 JSON의 마지막 중복 필드만 사용해 정상 조회·복구 성공으로 오판하는 문제를 수정했다.
  입력 파일의 중복 검사 함수를 응답 파서에도 적용한다. 모호한 GET 뒤에는 PUT을 보내지 않고,
  모호한 PUT 응답은 실패로 기록한 뒤 나머지 항목을 처리한다. [복구 절차](docs/runbooks/baton-snapshot-recovery.md)에 반영했다.
  추가 의존성·비용은 없으며 실제 서버 연동·운영 복구는 미실행이다.
- `e5baa5a`에서 [읽기 전용 진단](docs/runbooks/check-control-and-diagnostics.md)에 `deliveryProgress`를 추가했다.
  미전달 이벤트의 예약·대기·전달 중과 전달 완료를 구분하며, DB 조회 시각과 기존 일정·점유를 사용한다.
  기존 `deliveryStatus`·전달 처리·스키마는 유지한다. 작업자 중단·전달 비활성 여부는 이 상태만으로 판단하지 않는다.
  추가 의존성·비용은 없으며 운영 배포·실제 콜백 전달은 미실행이다.
- `b500466`에서 공개 상태 API의 `HEAD`를 지원했다. 토큰 없이 HTTP 200과 상태 응답 헤더를
  확인하며 본문은 받지 않는다. Spring MVC 기본 처리를 사용하고 보안 설정에서 정확한 상태 경로의
  `GET`·`HEAD`만 공개한다. 모니터·하위 경로의 인증과 기존 GET 본문 점검은 유지한다.
  [API 계약](docs/PRD/0002_api-contract/spec.md)에 반영했다. 추가 의존성·비용은 없으며 운영 배포·공개 HTTPS 점검은 미실행이다.
- `54ffc79`에서 이미 중단된 호출이 DNS·HTTP 실행기에 새 작업을 제출하던 문제를 수정했다.
  진입 시 `isInterrupted()`로 확인해 작업 제출 전에 `INTERNAL_FAILURE`를 반환한다.
  HTTP 요청은 취소 상태로 표시하고 호출자의 중단 표시를 유지한다. 정상 호출과 제출 후 취소는 기존대로 처리한다.
  [점검 기준](docs/PRD/0003_monitoring-mvp/spec.md)과 [전달 기준](docs/PRD/0004_health-change-event-delivery/spec.md)에 반영했다.
  추가 의존성·비용은 없으며 실제 운영 종료·배포는 미실행이다.
- `cc34bad`에서 이벤트 전달 사전 검사가 HTTP 200만으로 WATCH 정상 상태를 판단하던 문제를 수정했다.
  공개 스모크와 공통 Python JSON 판정을 사용해 `baton-watch`·`UP`을 확인한다. 응답 크기는
  64 KiB로 제한하며 실패 시 후속 수신기 요청을 중단한다. 임시 본문 삭제와 실패 종료 코드를 유지하고
  HTTP 상태값의 한글 오류 안내도 수정했다. [전달 검증 절차](docs/runbooks/public-staging-event-delivery.md)에 반영했다.
  추가 요청·의존성·비용은 없으며 실제 공개 HTTPS·수신기 인증·이벤트 전달은 미검증이다.
- `2e39398`에서 공개 TLS 인증서 만료 7일 전 경보를 Prometheus 템플릿에 추가했다.
  기존 Blackbox 점검의 만료 시각을 사용하고, 2분간 조건이 유지되면 알린다. 갱신 시 해제하며
  수집 실패·만료 지표 누락은 만료 예고로 처리하지 않는다. TLS 실패는 기존 요청 실패 경보로 확인한다.
  [인바운드 점검 절차](docs/runbooks/ingress-monitoring.md)에 감시 범위를 반영했다.
  추가 요청·의존성·비용은 없으며 실제 수집기 적용·공개 TLS·알림 수신은 미검증이다.
- `5e29b75`에서 루트 경로·전체 URL·`//호스트/...` 리다이렉트에 `.`·`..`가 남는 오류를 수정했다.
  `/docs/../guide`는 `/guide`로 점검하고 같은 페이지로 돌아오면 추가 연결 전에 거부한다.
  기존 점 구간 함수를 공통 적용하며 연속 슬래시·인코딩·쿼리·목적지 재검증·IP 고정은 유지한다.
  [점검 기준](docs/PRD/0003_monitoring-mvp/spec.md)에 반영했다. 추가 의존성·비용은 없으며 운영 배포는 미실행이다.
- `6cf53b4`에서 복구 도구가 401·403 응답의 본문 수신 실패를 일반 통신 실패로 처리하던 문제를 수정했다.
  본문 크기 초과·전송 중단·시간 초과에도 인증·접근 거부를 먼저 판단해 후속 요청을 중단한다.
  나머지 응답의 전송 성공·크기 검사는 유지한다. [복구 절차](docs/runbooks/baton-snapshot-recovery.md)에 반영했다.
  추가 의존성·비용은 없으며 실제 서버 인증·운영 복구는 미실행이다.
- `188ea9f`에서 등록 API와 복구 파일의 중복 JSON 필드가 처리 단계까지 통과하는 문제를 수정했다.
  API는 HTTP 400 `INVALID_REQUEST`, 복구 도구는 모든 통신 전에 종료 코드 1로 거부한다.
  값이 같거나 이름을 이스케이프로 적어도 검사하며 Jackson 설정·Python 표준 파서를 사용한다.
  [API 계약](docs/PRD/0002_api-contract/spec.md)과 [복구 절차](docs/runbooks/baton-snapshot-recovery.md)에 반영했다.
  추가 의존성·비용은 없으며 실제 배포·운영 복구는 미실행이다.
- `80822e5`에서 모니터 등록의 숫자 상태값과 문자열 리비전이 자동 변환되는 문제를 수정했다.
  `monitoringState`의 `0`·`1`·`"0"`·`"1"`, `sourceRevision`의 `"42"` 같은 입력을 처리 전에
  HTTP 400 `INVALID_REQUEST`로 거부한다. Spring Boot JSON 설정 두 항목을 사용하며
  정상 정수·상태 문자열·인증 우선순위를 유지한다. [API 계약](docs/PRD/0002_api-contract/spec.md)에 반영했다.
  추가 검증 클래스·의존성·비용은 없으며 운영 배포는 미실행이다.
- `a078dab`에서 상대 리다이렉트를 해석할 때 연속 슬래시가 줄어드는 오류를 수정했다.
  `/docs//page`의 `next` 이동은 `/docs//next`를 유지한다. JDK·Apache의 경로 정규화가 빈 구간을
  제거하므로 상대 경로 병합만 보완했다. 인코딩·쿼리 보존, 목적지 재검증·IP 고정·순환 거부는 유지한다.
  [점검 기준](docs/PRD/0003_monitoring-mvp/spec.md)에 반영했다. 추가 의존성·비용은 없으며 운영 배포는 미실행이다.
- `977de2c`에서 [읽기 전용 진단](docs/runbooks/check-control-and-diagnostics.md)에 `monitor.checkStatus`를 추가했다.
  기존 일정·점유와 DB 조회 시각으로 예약·대기·진행 중·비활성을 구분한다. API와 같은 판단 규칙을 사용하며
  리스 토큰·만료 시각은 출력하지 않는다. 추가 의존성·스키마 변경·비용은 없으며 운영 배포는 미실행이다.
- `d9eb4dd`에서 [PR #43](https://github.com/ljkhyeong/baton-watch/pull/43)의 런타임 부하 검사가
  DB 연결 부족에 예전 500 응답을 기대하던 문제를 수정했다. 현재 계약인 503·`SERVICE_UNAVAILABLE`과
  `Retry-After: 5`를 검증하며 [검증 절차](docs/runbooks/runtime-load-test.md)도 맞췄다.
  운영 코드는 변경하지 않았다.
- `75fdde4`에서 복구 도구가 HTTP 401·403 이후 반복 요청을 중단하도록 개선했다.
  거부된 요청의 항목은 `ACCESS_DENIED`, 나머지는 `NOT_ATTEMPTED`로 기록한다.
  이전 결과·파일 순서·건수 요약을 보존하며 종료 코드는 2다. 일반 통신 실패는 계속 처리한다.
  [복구 절차](docs/runbooks/baton-snapshot-recovery.md)에 반영했다. 실제 서버 인증·운영 복구는 미실행이다.
- `c7a4948`에서 연속 슬래시가 있는 경로의 정상 리다이렉트를 순환으로 거부하던 문제를 수정했다.
  순환 비교에서 경로 정규화를 제거해 `/docs//page`와 `/docs/page`를 구분한다.
  같은 주소의 순환 거부·호스트 대소문자·기본 포트 비교와 DNS 재검증은 유지한다.
  [점검 기준](docs/prd/0003_monitoring-mvp/spec.md)에 반영했다. 실제 공개 대상 점검·배포는 미실행이다.
- `addfb76`에서 스냅샷 복구 도구가 API의 정상 토큰을 거부하던 문제를 수정했다.
  API와 같이 `+`·`/`·끝의 `=`를 허용하고 패딩 제외 32자 이상·전체 200자 이하를 적용한다.
  파일 권한·토큰 비노출·기존 대조 및 재전송 동작은 유지한다.
  [복구 절차](docs/runbooks/baton-snapshot-recovery.md)에 입력 기준을 정리했다. 실제 운영 복구는 미실행이다.
- `a4f7180`에서 등록 API의 소수 리비전이 정수로 잘리는 문제를 수정했다.
  Spring Boot JSON 설정으로 `-0.5`·`42.9`·`42.0`·`4.2e1`을 동기화 전에
  HTTP 400 `INVALID_REQUEST`로 거부한다. 정상 정수 범위와 인증 우선순위는 유지한다.
  [API 계약](docs/prd/0002_api-contract/spec.md)에 입력 기준을 명시했다. 실제 배포는 미실행이다.
- `389e677`에서 터널 이미지의 gRPC를 1.83.2, 필수 의존성 `x/net`을 0.58.0으로 갱신했다.
  GitHub 경보 #1의 CVE-2026-84445 수정 버전을 적용하고 표시 버전을 `2026.8.3-watch.2`로 올렸다.
  모듈 체크섬·라이선스 버전 고정을 함께 갱신했으며 [빌드 입력](ops/cloudflared/README.md)에 근거를 정리했다.
  작업 브랜치만 수정했으며 `main` 병합·실제 터널 연결·배포·xDS 공격 재현은 미실행이다.
- 2026-09-12 GitHub API로 공개 저장소의 비밀값 탐지·푸시 차단·Dependabot 보안 업데이트가
  모두 활성화된 것을 확인했다. [외부 API 검토](docs/runbooks/external-api-options.md)에 실제 상태와
  남은 Telegram·Slack·Grafana 연결을 반영했다. 추가로 적용할 무료 연동은 확인되지 않았다.
  저장소 설정·애플리케이션 코드·외부 연결은 변경하지 않았다.
- [GitHub 개발 알림](docs/runbooks/github-slack.md)에 공식 Slack 앱의 검증·PR·리뷰·IANA 검사
  구독 명령을 추가했다. 저장소의 실제 작업 이름과 `main` 대상 PR·푸시·정기·수동 실행을 반영했다.
  앱 설치·계정 연결·채널 구독·메시지 전송은 미실행이다. 문서만 바꿨으며 발송 코드·CI·런타임 설정은 그대로다.
- `codex/free-api-integrations`의 `cd53727`에서 외부 점검 결과가 15분간 없는 경우의 경보를 추가했다.
  Grafana Cloud가 기존 Synthetic Monitoring 지표로 평가하며 추가 API 점검은 없다.
  실패 결과 수신과 결과 누락을 구분하고, 경보 레이블에서 URL·점검 위치를 제외한다.
  [외부 상태 점검](docs/runbooks/grafana-public-check.md)에 일시정지 상태의 규칙 가져오기,
  첫 결과 확인 후 활성화와 중지 절차를 정리했다. 실제 Grafana 연결·알림과 원격 CI는 미확인이다.
- `codex/free-api-integrations`의 `002b1f8`에서 Grafana 메트릭 전송의 HTTP 429 재시도를 켰다.
  Prometheus 기본 기능을 사용하며 재시도 대기는 1초부터 최대 1분, 전송 동시성은 1개다.
  기존 1시간 샘플 제외 기준·수집 범위·주기는 유지한다. 계정 인증·실제 전송과 운영 적용은 미실행이다.
- `codex/free-api-integrations`의 `123837a`에서 Grafana Cloud 외부 HTTP 점검 등록 예시를 추가했다.
  `watch.b4ton.com`의 공개 상태를 위치 1곳·5분 간격으로 확인하는 설정이며 기본값은 비활성이다.
  [외부 상태 점검](docs/runbooks/grafana-public-check.md)에 서버 전체 중단·공개 TLS 만료 감지와
  Grafana Cloud에서 Slack·Telegram으로 직접 알리는 절차를 정리했다. 31일 예상 사용량은 8,928회다.
  실제 Free 계정·API 인증·공개 점검 위치·알림은 미설정이며 등록·점검·서버 설치는 미실행이다.
- `codex/free-api-integrations`의 `8734153`에서 Slack Incoming Webhook 연결을 추가했다.
  Slack은 웹훅 주소 파일 하나로 설정하며 WATCH 경보만 묶어 장애·복구를 알린다.
  Telegram과 공통 문구를 사용하도록 `watch-notification.tmpl`로 이름을 변경했다.
  기존 Telegram 연결을 적용했다면 설정과 템플릿 경로를 함께 갱신한다.
  [채널별 연결 방법](docs/runbooks/external-api-options.md)에 Slack 발급 절차와 Free 앱 한도를 정리했다.
  실제 Slack 인증·수신과 원격 CI는 미확인이다. 앱·서버 설치와 메시지 전송은 실행하지 않았다.
- 작업 브랜치 `codex/free-api-integrations`의 `aaa559e`에서 Alertmanager의 Telegram Bot API
  연결 설정과 한글 알림 문구를 추가했다. WATCH 경보만 선택하고 같은 경보를 묶어 장애·복구를 알린다.
  봇 토큰·채팅 ID는 파일로 읽으며 자료 URL·내부 주소·리소스 참조는 알림에 넣지 않는다.
  [무료 외부 API 검토](docs/runbooks/external-api-options.md)에 CAL 구독·RELAY 전송·Grafana 연결의
  재사용 판단과 공휴일 API 적용 위치를 정리했다. 애플리케이션 코드·서버 설치·DNS는 변경하지 않았다.
  실제 봇 인증·수신, Prometheus 연결과 원격 CI는 미확인이다.
- 작업 브랜치 `codex/architecture-feedback-loop`의 `034f240`에서 파일 작성 직후 검사와
  작업 종료 전 구조 검사를 추가했다. `ops/check-feedback.py file`은 파일 형식·문법·Java 컴파일,
  `finish --base`는 작업 시작 이후 변경·새 파일과 전체 계층 의존성을 검사한다.
  ArchUnit은 계층 역방향·어댑터 간 의존과 웹의 서비스 구현체·출력 포트 사용을 차단한다.
  파일 검사는 AGENTS의 에이전트 실행 규칙, 구조 검사는 기존 테스트·CI에 연결했다.
  테스트 전용 의존성이며 실행 JAR·서비스 동작은 바꾸지 않는다. 원격 CI는 미실행이다.
- 작업 브랜치 `codex/cancelled-queue-cleanup`의 `b33ad1c`에서 취소된 DNS·HTTP 요청이
  대기열에 남아 새 요청을 막던 문제를 수정했다. 시간 초과·호출자 인터럽트로 취소한 작업을
  JDK `ThreadPoolExecutor.purge()`로 제거한다. 실행 중인 작업이 끝나기 전에도 대기열을
  다시 사용할 수 있으며 기존 연결 취소·스레드 수·대기열 상한은 유지한다.
  추가 의존성·비용은 없으며 운영 배포는 미실행이다.
- 작업 브랜치 `codex/recovery-revision-report`의 `584304e`에서 복원 성공 후에도 이전 리비전을
  표시하던 오류를 수정했다. `REPLAYED`의 `remoteRevision`은 PUT 성공 응답으로 갱신하고,
  PUT 실패·충돌은 `null`로 보고한다. 조회만 한 항목은 GET에서 확인한 값을 유지한다.
  원격 리비전 `0`도 WATCH API 계약에 맞게 비교하며 음수·64비트 범위 초과는 조회 실패로 처리한다.
  입력 파일·재전송 본문·상위 리비전 보호 규칙은 유지한다. 실제 운영 복원은 미실행이다.
- 작업 브랜치 `codex/gateway-retry-after`의 `89ccbb5`에서 NGINX 자체 요청 제한 429에
  `Retry-After: 1`을 추가했다. WATCH가 반환한 수동 재점검·DB 장애의 대기 시간은 그대로 전달한다.
  실제 NGINX로 헤더 추가·보존과 요청 중단 후 두 경로의 복구를 확인했다.
  요청량·버스트·이미지·의존성 변경과 추가 비용은 없으며 운영 배포·공개 HTTPS 검증은 미실행이다.
- 작업 브랜치 `codex/batch-snapshot-audit`의 `7093ee6`에서 복원 도구의 조회 모드를
  최대 20개씩 묶어 대조하도록 개선했다. 1만 건의 조회 요청은 1만 번에서 500번으로 줄어든다.
  응답 순서와 무관하게 파일 순서대로 결과를 남기며, 참조 누락·중복·요청 밖 참조는 조회 실패로 처리한다.
  묶음 응답 상한은 32KiB이며 재전송 모드의 항목별 GET·PUT과 8KiB 상한은 유지한다.
  묶음 조회 API 지원 서버가 필요하다. 추가 의존성·비용은 없으며 실제 운영 대조·복구는 미실행이다.
- 작업 브랜치 `codex/query-redirect-resolution`의 `27fa973`에서 쿼리 전용 리다이렉트의
  경로 해석을 수정했다. `/docs/page?old=1`에서 `?page=2`로 이동하면 `/docs/page`를 유지한다.
  기존 Apache HttpClient 함수를 사용하며 인코딩 보존·DNS 재검증·IP 고정·순환 감지를 확인했다.
  추가 의존성·DB 변경·비용은 없으며 운영 배포는 미실행이다.
- 작업 브랜치 `codex/postgres-error-translation`의 `2a07f8b`에서 실제 DB 잠금 시간 초과가
  500으로 반환되던 오류를 수정했다. 공용 JDBC에 Spring의 PostgreSQL용 오류 변환기를 적용해
  잠금 오류(`55P03`)를 `503 SERVICE_UNAVAILABLE`·`Retry-After: 5`로 처리한다.
  실제 행 잠금 중 데이터 보존과 잠금 해제 후 동일 요청의 성공을 확인했다.
  추가 의존성·DB 변경·비용은 없으며 운영 배포는 미실행이다.
- 작업 브랜치 `codex/api-temporary-failures`의 `dc83cf7`에서 일시적 DB 장애를 `503 SERVICE_UNAVAILABLE`로 구분했다.
  단건·묶음 조회, PUT·재점검 POST에 `Retry-After: 5`를 제공한다. Spring 표준 예외로 분류하고
  SQL 원인이 없는 트랜잭션 생성 실패·데이터 제약 위반·코드 오류는 500을 유지한다.
  오류 원문은 노출하지 않고 서버 내부 재시도는 추가하지 않았다. 기존 런타임의 `spring-tx 7.0.9`를
  웹 모듈에 명시했으며 라이브러리 버전·DB 변경·추가 비용은 없다. 운영 배포는 미실행이다.
- 작업 브랜치 `codex/batch-monitor-query`의 `1baac29`에서 최대 20개 대상의 묶음 조회 API를 추가했다.
  인증된 `GET /api/v1/resource-monitors?resourceReference=...`는 한 번의 DB 조회로 처리하고,
  요청 순서대로 중복을 제거한다. 미등록 대상은 `missingResourceReferences`로 구분한다.
  기존 단건 조회·인증·민감정보 제외 규칙을 유지한다. 추가 의존성·DB 변경·비용은 없다.
  BATON의 묶음 조회 전환과 운영 배포는 미실행이며 [연동 준비](docs/runbooks/baton-integration-review.md)에 조건을 기록했다.
- 작업 브랜치 `codex/check-progress-status`의 `45e518a`에서 모니터 PUT·GET에 `checkStatus`를 추가했다.
  예약(`SCHEDULED`)·대기(`QUEUED`)·진행 중(`IN_PROGRESS`)·비활성(`INACTIVE`)을 기존 일정·리스와
  주입된 `Clock`으로 판단한다. 기존 응답 필드·점검 실행 규칙은 유지하며 내부 리스 정보는 노출하지 않는다.
  추가 의존성·DB 마이그레이션·비용은 없다. BATON 소비자 연동과 운영 배포는 미실행이다.
- 작업 브랜치 `codex/callback-retry-after`의 `ec521db`에서 콜백 429·503의 `Retry-After`를 반영했다.
  정수 초·HTTP 날짜를 다음 재시도 시각에 적용하며 기존 백오프·상한을 유지한다.
  잘못된 헤더는 무시하고 원문은 저장하지 않는다. 추가 의존성·DB 마이그레이션은 없다.
  실제 BATON 콜백 연결과 운영 배포는 미실행이다.
- `a71c1ad` 이후 장애 진단·복구·부하 시험 문서와 Java 주석의 추상적인 표현을 추가로 정리했다.
  재점검 응답 3종은 표로 구분했다. 실행 코드·API 응답·명령·수치는 유지한다.
- `codex/clear-copy-20260911`에서 문서·API 오류·운영 안내를 간결한 한국어로 정리했다.
  기준은 9월 11일 확인한 `origin/main`의 `e2ad4b0`이다. `42fd631`은 API 오류 제목,
  메트릭·대시보드 설명과 셸 안내 문구만 바꿨다. 오류 코드·상태·처리 로직·쿼리·단위는 유지한다.
  README·PRD·ADR·운영 절차·보안 정책의 긴 문장도 나누고 추상적인 표현을 구체화했다.
- `304bd0c`에서 필수 CI를 막던 배포 의존성·이미지 취약점을 수정했다. Tomcat은 11.0.25,
  PostgreSQL의 OpenSSL은 3.5.8-r0, libuuid는 2.42.3-r1로 고정하고 gosu를 su-exec으로 교체했다.
  NGINX는 수정된 공식 다이제스트를 사용한다. cloudflared는 공식 2026.8.3 소스를
  당시 Go 1.26.6·x/crypto 0.55.0·gRPC 1.83.1로 빌드했다. 현재 버전은 [빌드 입력](ops/cloudflared/README.md)을 참고한다.
  자체 이미지 5개의 보관·복원과 OCI·LICENSE 검증을 CI·배포 절차에 반영했다.
- `454360b`에서 V6 마이그레이션으로 점검 결과·전달 결과·전달 완료 상태의 DB 제약 3개를 보강했다.
  수정 전에는 필수 값이 `NULL`인 잘못된 조합 9가지가 저장됐다. 이제 `CASE`와 `IS TRUE`로 거부한다.
  운영 DB 적용은 미실행이다. 기존 불완전한 이력이 있으면 V6 적용이 실패하며, 이력을 자동 수정·삭제하지 않는다.
- 이전 작업 브랜치: `codex/grafana-cloud-free`. `3739149`에서
  [Grafana Cloud Free 연결 설정](ops/prometheus/watch-cloud-free.yml)을 추가했다.
  WATCH 지표만 60초마다 전송하며 한 번의 수집은 1MB·2,000개 샘플로 제한한다.
- 실제 Free 계정·전송 주소·비밀 파일·수집기는 미설정이다. 외부 전송과 계정 인증은 실행하지 않았다.
  [무료 연결 절차](docs/runbooks/grafana-cloud-free.md)에 따라 준비한 뒤 수신을 확인한다.
- `8bf0546`에서 공용 HTTP의 미사용 실패 바이트 집계와 본문 폐기 함수의 반환값·콜백·0바이트 분기를 제거했다.
  콜백 본문 상한, HTTP 취소·타임아웃 분류는 유지한다.
- `d3aa13a`에서 점검 바이트 계산·전용 테스트 2개, 미사용 `ClaimedCheck.scheduledAt`과
  스케줄러 메트릭 주입을 제거했다. 기존 설정 검증·저장 필드, DB 예약·리스·지연 측정은 유지한다.
- `15bd9b8`에서 지시·스킬을 정리했고, `d1763d0`에서 [검증 기록 도구](ops/run-validation.py)와
  [개발 검증 절차](docs/runbooks/development-validation.md)를 추가했다.
  기록은 `.gradle/agent-validation/`에 남기며 `status` 명령으로 최근 결과와 파일 변경 여부를 확인한다.
- 스킬은 API·영속성·운영·관측성·문서 5개다. 공통 규칙은 [AGENTS.md](AGENTS.md)를 따른다.
  새 작업마다 전체 검증 이력이나 모든 스킬을 읽을 필요는 없다.

## 최근 검증

| 대상 | 결과와 재사용 범위 |
| --- | --- |
| 공개 상태 중복 필드 `878b3a0` | 변경 전 사전 검사와 대역을 고친 공개 스모크에서 잘못된 성공 재현. 변경 후 각 20개 사례·변경 셸 2개의 ShellCheck 통과. 상태·서비스 중복, 같은 값·이스케이프 이름의 중복 거부와 후속 요청 중단 확인. 지정한 정상 JSON의 성공도 확인. HTTP 대역을 사용했으며 요청 인자·애플리케이션·DB·이미지 변경이 없어 실제 외부 연결·Java·DB·이미지 검사는 미실행 |
| 공급망 보고서 누락 `6efdaca` | 변경 전 도구가 0으로 종료하고 이미지 보고서를 만들지 않아도 완료 처리되는 오류 재현. 변경 후 공급망 스크립트 검사·라이선스 정책 6개·변경 셸 2개의 ShellCheck 통과. 보고서 누락·빈 JAR 보고서·도구 실패의 실패 전파, 후속 검사와 실패 보고서 보존, 완료 체크섬·배포용 JAR 미생성 확인. 검사 도구 대역을 사용했으며 Docker 호출 인자·이미지·정책은 바꾸지 않아 실제 Trivy 재검사·이미지 빌드·Java·DB 검사는 반복하지 않음 |
| Tomcat 요청 거부 로그 `9f91333` | 변경 전 실제 TCP 요청으로 잘못된 주소의 HTTP 400과 INFO·DEBUG 원문 노출 재현. 변경 후 로그 통합 2개·bootstrap 전체 125개 통과, 실패·건너뜀 없음. 개별 Tomcat DEBUG 설정의 차단, 요청 거부·주소와 쿼리 비노출, 기존 정상·미인증·DB 장애 요청과 오류 종류 로그 유지 확인. 합성 입력만 사용했으며 DB·외부 통신·이미지 동작 변경이 없어 해당 검사는 반복하지 않음 |
| 인바운드 원문 로그 보호 `6a3ba2c` | 변경 전 실제 Tomcat HTTP 요청에서 요청 경로·쿼리·본문 URL·인증 토큰·응답 참조·예외 원문 노출 재현. 변경 후 관련 3개·bootstrap 전체 124개 통과, 실패·건너뜀 없음. 상위 범주와 개별 로거의 TRACE 재정의 차단, 401·200·503과 WATCH의 오류 종류 로그 유지 확인. 합성 입력과 DB 예외 대역을 사용했으며 DB·외부 통신·이미지 동작 변경이 없어 해당 검사는 반복하지 않음 |
| 로그 감사 입력 누락 `689332e` | 변경 전 빈 로그·첫 번째 비밀 파일만 비어 있는 입력이 통과하는 오류 재현. 변경 후 감사 테스트 12개·변경 셸 2개의 ShellCheck 통과. 정상 로그·6종 노출 탐지·빈 로그·공백 로그·개별 비밀 파일·금지 값 파일 누락과 원문·경로 비노출 확인. 합성 입력만 사용했으며 애플리케이션·DB·이미지 변경이 없어 해당 검사는 반복하지 않음 |
| 검증 취소 처리 `2d9c0a0` | 변경 전 하위 명령 잔존·결과 누락 재현. 변경 후 실행 도구 9개·파일 검사 도구 9개 통과. 실제 임시 프로세스로 신호 3종의 전달·하위 명령 종료·실패 기록과 취소 무시 시 강제 종료 확인. 애플리케이션·DB·이미지 변경이 없어 해당 검사는 반복하지 않음 |
| 경보 카운터 초기 등록 `c3919a2` | 변경 전 4개 카운터 부재 재현. 변경 후 계측 18개·bootstrap 전체 123개 통과, 실패·건너뜀 없음. 초기 0·실제 증가·메트릭 오류 격리·제한된 레이블 확인. 기존 promtool 전체 검사에 첫 사건 시나리오를 추가해 사전 0이 있는 경우만 경보 발생, 2분 유지·정상 기간·해제 확인. DB·외부 통신·부하·이미지 검사는 변경 범위 밖이므로 반복하지 않음 |
| DB 운영 파일의 테스트 입력 `99f8f2a` | 수정 전 SQL에 임시 주석을 추가해도 영속성 테스트가 `UP-TO-DATE`인 누락 재현. 입력 등록 후 검사 실행, SQL 주석 제거만으로 재실행 확인. 최종 SQL의 실제 PostgreSQL 포함 84개 통과, 실패·건너뜀 없음. 이후 문서만 수정하면 `UP-TO-DATE`로 재사용됨을 확인. 강제 재실행 옵션은 사용하지 않았으며 임시 SQL 변경은 모두 제거함 |
| 복구 응답의 중복 JSON `3e9f43a` | 수정 전 묶음 조회 3개·GET/PUT 2개 조건에서 잘못된 결과·후속 PUT 재현. 수정 후 복구 도구 전체 22개 통과. 중첩 객체·같은 값·이스케이프 이름의 중복 거부, 결과 순서·다음 묶음·항목 처리와 기존 401·403 중단 확인. DNS·HTTP 응답은 대역으로 검증했으며 Java·DB·이미지 변경이 없어 해당 검사는 반복하지 않음 |
| 전달 진단 진행 상태 `e5baa5a` | 실제 PostgreSQL 진단 테스트 21개와 영속성 모듈 전체 84개 통과, 실패·건너뜀 없음. 일정·점유 조합 5개에서 표시 상태와 실제 작업자의 실행 대상 일치, 완료 상태·읽기 전용·이력 제한·정보 제외 확인. 전달 처리·스키마·API·외부 통신은 변경하지 않아 전체 Java·부하·이미지 검사는 반복하지 않음 |
| 공개 상태 HEAD `b500466` | 변경 전 토큰 없음·잘못된 토큰의 HEAD가 401을 반환하는 사례 2개 확인. 변경 후 실제 Tomcat 인증 통합 44개, 웹 전체 47개·bootstrap 전체 122개 통과, 실패·건너뜀 없음. HEAD 200·빈 본문·JSON 헤더·캐시 금지, 다른 세 경로의 HEAD 인증과 기존 POST 인증 유지 확인. MockMvc는 핸들러 연결, 실제 HTTP는 본문 생략을 검사. 프록시·외부 통신·DB 동작은 변경하지 않아 관련 검사는 반복하지 않음 |
| 중단된 외부 호출 `54ffc79` | 수정 전 DNS·HTTP 회귀 시험 2개에서 작업자 생성 재현. 수정 후 관련 21개·외부 통신 모듈 전체 274개 통과, 실패·건너뜀 없음. 중단 표시 유지·HTTP 취소·작업 미제출과 이후 정상 호출, 기존 실행 중 취소·대기열 복구·시간 제한 확인. 단일 모듈 변경으로 전체 Java·DB·부하·이미지 검사는 반복하지 않음 |
| 사전 검사의 WATCH 확인 `cc34bad` | 수정 전 잘못된 본문의 HTTP 200 통과 재현. 수정 후 사전 검사 17개·공개 스모크 16개와 변경 셸 3개의 ShellCheck 통과. 다른 서비스·비정상 상태·필드 누락·배열·빈 본문·잘못된 JSON·curl 실패의 후속 요청 중단, 원문 비노출·임시 파일 삭제 확인. HTTP·크기 초과·시간 초과는 대역으로 검증했으며 Java·DB·이미지는 변경하지 않아 해당 검사는 반복하지 않음 |
| 공개 TLS 만료 경보 `2e39398` | `./ops/tests/prometheus-rules-test.sh` 통과. 설정 문법·인바운드 경보 4개와 기존 WATCH·공개 점검·작업자 경보·대시보드 쿼리 확인. 새 시나리오 3개에서 7일 경계·2분 대기·갱신 해제·수집 실패·지표 누락·경로 구분·TLS 실패를 8개 시점별 기대 결과로 검사. Java·DB·프록시·Blackbox 모듈은 변경하지 않아 해당 검사는 반복하지 않음 |
| 이동 경로의 점 구간 `5e29b75` | 수정 전 주소 계산·순환 시험 29개 중 새로 추가한 10개에서 실패 재현. 수정 후 점검 엔진 61개·외부 통신 모듈 전체 272개 통과, 실패·건너뜀 없음. 루트 경로·전체 URL·호스트 기준 이동, 연속 슬래시·인코딩·쿼리 보존, 잘못된 주소·사용자 정보·프래그먼트 거부 확인. 단일 모듈 변경으로 전체 Java·DB·부하·이미지 검사는 재실행하지 않음 |
| 복구 응답 실패의 인증 판정 `6cf53b4` | 수정 전 401·403 × 본문 초과·전송 중단·시간 초과 × 대조·재전송의 12개 조건에서 후속 요청 재현. 수정 후 복구 도구 전체 20개 통과. 200 응답의 전송 실패·연결·TLS 실패 거부도 확인. 실제 curl 8.7.1과 로컬 임시 HTTP 서버의 6개 조건에서 종료 코드 63·18·28과 HTTP 상태 보존 확인. Java·DB·이미지 변경이 없어 해당 검사는 재실행하지 않음 |
| 중복 JSON 필드 `188ea9f` | 수정 전 API·복구 파일 각각 5가지 중복 입력의 통과 재현. 수정 후 인증 통합 38개·bootstrap 전체 116개·복구 도구 18개 통과, 실패·건너뜀 없음. 변경 없는 웹 계약 46개 결과 재사용. 인증 우선순위·정상 입력·뒤쪽 행의 중복에 대한 전체 통신 중단·오류의 민감정보 제외 확인. 이미지·외부 통신·부하 검사는 변경 범위 밖이므로 재실행하지 않음 |
| 등록 JSON 타입 `80822e5` | 실제 HTTP에서 잘못된 타입 7가지가 변환되어 처리 단계까지 전달되는 문제 재현. 수정 후 인증 통합 33개와 bootstrap 전체 111개 통과, 실패·건너뜀 없음. 변경 없는 웹 계약 46개 결과 재사용. 타입 거부·정수 경계·인증 우선순위·공통 오류·본문 제한 확인. 이미지·외부 통신·부하 검사는 변경 범위 밖이므로 재실행하지 않음 |
| 상대 리다이렉트 경로 `a078dab` | 수정 전 경로·쿼리 사례 15개 중 10개에서 잘못된 주소 재현. 수정 후 같은 15개와 외부 통신 모듈 전체 257개 통과, 실패·건너뜀 없음. 빈 경로 구간·점 구간·인코딩·쿼리 보존, 상대 주소의 순환 거부, DNS 재검증·IP 고정과 실제 HTTP 전송 확인. 단일 모듈 변경으로 전체 Java·DB·부하·이미지 검사는 재실행하지 않음 |
| 진단 진행 상태 `977de2c` | 실제 PostgreSQL 진단 테스트 16개 후 영속성 모듈 전체 79개 통과, 실패·건너뜀 없음. 일정·점유 조합 7개의 상태와 같은 조회 시각의 도메인 판단 일치, 읽기 전용·정보 제외·이력 제한·잠금 시간 초과 확인. 점검 실행·API·외부 통신·스키마 변경이 없어 전체 Java·부하·이미지 검사는 재실행하지 않음 |
| 런타임 부하 검사 `d9eb4dd` | [원격 검사](https://github.com/ljkhyeong/baton-watch/actions/runs/34675952314)에서 실제 503과 예전 기대값 500의 불일치 확인. 수정 후 `:bootstrap:runtimeLoadTest -PwatchRuntimeLoadMonitors=25` 통과, 테스트 1개·실패·건너뜀 없음. 실제 PostgreSQL의 풀 고갈·복구, 503·재시도 안내, 25개 점검·전달 복구와 최종 미전달 0건 확인. 외부 점검·콜백은 테스트 대역이며 운영 코드·이미지 변경은 없음 |
| 복구 인증 실패 중단 `75fdde4` | 수정 전 401·403의 후속 요청 10개 조건 재현. 수정 후 복구 도구 16개와 추가 CLI 검사 1개, 총 17개 통과. JSON·HTML·빈 본문, 묶음 조회와 재전송 GET·PUT 중단, 이전 결과·전체 항목·요약·종료 코드 2와 비밀값 비노출 확인. DNS·curl 응답을 모의했으며 실제 서버 인증·운영 복구는 미실행. Java·DB·이미지 변경이 없어 관련 검사는 반복하지 않음 |
| 리다이렉트 경로 비교 `c7a4948` | 실제 로컬 HTTP에서 연속 슬래시 전송을 확인하고, 점검 엔진이 서로 다른 두 경로의 이동을 거부하는 오류 재현. 수정 후 외부 어댑터 전체 243개 통과, 실패·건너뜀 없음. 양방향 이동 허용·실제 순환 거부·DNS 재검증·IP 고정 확인. 해당 모듈만 변경해 전체 Java·DB·이미지·배포 검사는 재실행하지 않음 |
| 복구 토큰 호환성 `addfb76` | 수정 전 정상 토큰 2종 거부 재현. 수정 후 복구 도구 14개 테스트 통과. 새 회귀 검사에서 정상 4종·잘못된 값 7종, 길이·패딩·CRLF와 출력 비노출 확인. 기존 전달 검사에도 `+`·`/`·`=` 토큰을 사용해 명령 인자 제외·표준 입력 전달·IP 고정·리다이렉트 금지 확인. 모의 HTTP 응답을 사용했으며 실제 서버 인증·운영 복구는 미실행. Java·DB·이미지 변경이 없어 관련 검사는 반복하지 않음 |
| 소수 리비전 거부 `a4f7180` | 실제 로컬 HTTP에서 잘못된 숫자 4종이 동기화까지 전달되는 문제 재현. 수정 후 인증 통합 26개·ArchUnit 3개 통과, 웹 계약 46개는 변경 없는 성공 결과 재사용. 소수·지수 거부, 0·최대 정수 허용, 인증 우선순위·공통 오류 응답 확인. YAML 구문 통과. DB·이미지·외부 통신 동작은 변경하지 않아 관련 검사는 반복하지 않음 |
| 터널 gRPC 패치 `389e677` | linux/arm64 빌드와 CLI·RPC 테스트 3개 패키지 통과. 최종 이미지의 비루트·네트워크 차단 실행, gRPC 1.83.2·x/net 0.58.0 포함, 라이선스 원문 확인. Trivy 0.74.0과 2026-09-12 갱신 DB로 수정 가능한 HIGH·CRITICAL 0건. 첫 빌드에서 하위 의존성 누락을 검출해 Go 도구로 보완 후 재검사. Java·DB·다른 이미지는 변경하지 않아 관련 검사는 반복하지 않음 |
| GitHub 기본 기능 활성 상태 | `gh api repos/ljkhyeong/baton-watch`의 공개 여부와 `security_and_analysis`만 조회. 비밀값 탐지·푸시 차단·Dependabot 보안 업데이트의 `enabled` 확인. 실제 탐지 시험·개별 보안 경보 해결 검증은 미실행. 문서만 변경해 애플리케이션 검사는 재실행하지 않음 |
| GitHub Slack 연결 안내 | 공식 구독 문법·필터와 실제 워크플로 2개의 이름·실행 조건 대조, 문서 링크·형식 검사 통과. 실제 앱·채널 수신은 미확인. 문서만 변경해 Java·PromQL·Alertmanager 검사는 재실행하지 않음 |
| 외부 점검 결과 누락 `cd53727` | 공식 `promtool`로 새 규칙의 5개 시나리오·7개 시점 검사와 기존 경보·대시보드 검사 통과. 정상·실패 결과 수신, 15분 경계·복구, 다른 점검 제외, 최초 결과 없음 확인. ShellCheck 통과. 실제 Grafana 가져오기·평가·수신은 미확인. Java·Alertmanager·배포 설정은 변경하지 않아 관련 동작 검사는 재실행하지 않음 |
| Grafana 전송 제한 재시도 `002b1f8` | 공식 Prometheus `v3.13.1`의 `promtool check config --syntax-only` 통과. 네트워크를 차단해 검사했으며 실제 429 응답·전송 재시도·계정 인증은 미확인. Java·PromQL·Alertmanager 설정은 변경하지 않아 관련 동작 검사는 재실행하지 않음 |
| 외부 상태 점검 등록 예시 `123837a` | JSON 형식과 공식 OpenAPI `1.16.0`의 등록·점검 위치 조회 경로, 요청 필드·타입 22개 항목 대조 통과. 31일 사용량 8,928회 확인. 명세 SHA-256은 검증 로그에 기록. 실제 점검 위치의 유효성·계정 인증·등록·실행·알림은 미확인. Java·PromQL·Alertmanager·배포 설정은 그대로여서 해당 동작 검사는 재실행하지 않음 |
| Slack 연결·공통 문구 `8734153` | 기존 3개 테스트를 두 채널로 확장해 통과. 공식 `amtool`로 설정 2개·수신 경로 8개·공통 장애 및 복구 문구 2개 검사. 가짜 자격 증명과 네트워크 차단 사용. YAML 문법·문서 링크 검사 통과. Java·PromQL·배포 구성이 바뀌지 않아 해당 검사는 재실행하지 않음 |
| 무료 Telegram 알림 설정 `aaa559e` | 공식 Alertmanager `v0.31.1`의 `amtool`을 사용한 3개 테스트 통과. 설정 파싱, WATCH 내부·인바운드 경보 수신 경로와 다른 서비스 제외, 다중 인스턴스 장애·복구 문구 및 민감정보 미노출 확인. 가짜 자격 증명과 네트워크 차단 사용. 워크플로·설정 YAML 문법 통과. Java·기존 PromQL·배포 구성이 바뀌지 않아 해당 동작 검사는 재실행하지 않음 |
| 단계별 검증 루프 `034f240` | 검사 도구 9개 통과. 커밋·스테이징·미커밋·새 파일·삭제·이름 변경, 문서만 변경 시 Java 생략, 검사 실패 전파 확인. Controller·Domain·Service의 저장소 구현체 직접 의존을 임시 코드로 넣어 모두 검출하고 제거. 정상 구조 규칙 3개 통과. `./gradlew test :bootstrap:verifyBootJarLicense` 통과: Java 534개 중 bootstrap 98개 실행·436개 결과 재사용, 실패·건너뜀 없음. 실행 JAR에 ArchUnit·임시 코드가 없음을 확인. 운영 코드·이미지 변경이 없어 부하·복구·배포 검사는 반복하지 않음 |
| 취소된 대기열 정리 `b33ad1c` | 실제 실행기의 작업자·대기열을 채운 회귀 시험 4개에서 새 작업 거부를 재현하고 수정 후 같은 4개 통과. `:adapter-out-external:test` 전체 239개 실행·통과, 실패·건너뜀 없음. DNS·HTTP의 시간 초과·호출자 인터럽트 후 대기열 재사용, 기존 연결 취소·종료·시간 제한 확인. 외부 통신 모듈만 변경해 전체 Java·DB·배포 검사는 재실행하지 않음. 런타임 부하 시험은 외부 어댑터를 대역으로 교체하므로 이 대기열을 검증하지 않아 미실행 |
| 복원 리비전 보고 `584304e` | 관련 시험 4개에서 잘못된 리비전 표시·원격 범위 판정을 재현하고 수정 후 같은 4개 통과. 복원 도구 전체 13개도 통과. 누락·0·낮은 리비전의 복구 성공 값, PUT 실패·충돌의 확인 불가 표시, 높은 원격 리비전 보존과 잘못된 원격 값 거부 확인. HTTP 대역을 사용했으며 실제 운영 복원·공개 HTTPS 연동은 미실행. Java·DB·배포 설정 변경이 없어 해당 검사는 반복하지 않음 |
| 프록시 재시도 안내 `89ccbb5` | `python3 ops/tests/gateway-test.py` 통합 시험 2개 통과. 격리 Compose 구문·실제 NGINX 기동·상태/모니터 429의 1초 안내·백엔드 429/503의 원래 헤더·요청 중단 후 복구 확인. 기존 독립 요청 예산·인증 헤더 전달·관리 경로 차단·로그 비노출·Blackbox 장애 감지도 통과. 시험 컨테이너·네트워크 정리 완료. Java·DB·이미지 변경이 없어 해당 검사는 반복하지 않았고 실제 운영 배포·공개 HTTPS 검증은 미실행 |
| 스냅샷 묶음 대조 `7093ee6` | `python3 ops/tests/baton-snapshot-recovery-test.py` 13개 실행·통과. 20개 경계·역순 응답·미등록 구분·잘못된 응답·실패 후 다음 묶음 처리 확인. 기존 재전송의 동일 본문·리비전 보존·충돌 처리와 토큰 보호·주소 고정·응답 상한도 확인. HTTP 호출은 대역으로 검증했으며 실제 운영 복원·공개 HTTPS 연동은 미실행. Java·DB·배포 설정 변경이 없어 해당 검사는 재실행하지 않음 |
| 쿼리 리다이렉트 `27fa973` | 기존 점검 엔진 시험에서 경로 손실·순환 감지 실패 5개를 재현하고 수정 후 해당 5개 통과. `:adapter-out-external:test` 전체 235개 실행·통과, 실패·건너뜀 없음. 쿼리 교체·빈 쿼리·인코딩·순환·문자 거부와 기존 DNS 고정·TLS·시간 제한·콜백 처리 확인. 외부 어댑터의 주소 해석만 변경해 전체 Java·DB·이미지·배포 검사는 재실행하지 않음 |
| PostgreSQL 잠금 오류 `2a07f8b` | 실제 행 잠금 시험에서 503 대신 500이 반환되는 오류를 재현했다. 수정 후 실패한 1개부터 재검증하고 `./gradlew test` 통과. 전체 520개 중 bootstrap 98개 실행·변경 없는 422개 결과 재사용, 최종 실패·건너뜀 없음. 잠금 중 503·5초 안내·기존 데이터 조회와 잠금 해제 후 동일 PUT의 200 응답 확인. 의존성·스키마 변경이 없어 이미지·공급망 검사는 미실행. DB 네트워크 단절·부하·운영 배포는 별도 실행하지 않음 |
| 일시적 DB 장애 응답 `dc83cf7` | web 전체 46개·bootstrap 인증 20개, 총 66개 실행·통과. 실패·건너뜀 없음. 모의 DB 예외와 실제 로컬 HTTP로 503·5초 안내, 기존 500 분류, 인증 우선순위·민감정보 제외·서버 내부 재시도 없음 확인. `:bootstrap:verifyBootJarLicense` 통과, 실행 JAR의 `spring-tx 7.0.9` 확인. HTTP 오류 응답만 바꿔 전체 Java·이미지·DB 강제 중단·실제 배포 검사는 미실행 |
| 여러 대상 조회 `1baac29` | 관련 59개와 `./gradlew test` 확인. 전체 503개 중 480개 실행·변경 없는 domain 23개 결과 재사용, 최종 실패·건너뜀 없음. 첫 API 테스트의 배열 단언 2개를 수정해 해당 범위부터 재검증했다. 실제 PostgreSQL의 요청 대상 선택·중복 제거·리스 매핑과 HTTP의 입력 1~20개·최대 길이·인증 우선순위·미등록 구분 확인. 이미지·공급망·실제 BATON 연동·배포는 변경 범위 밖이거나 연결 정보가 없어 미실행 |
| 점검 진행 상태 `45e518a` | 관련 60개와 `./gradlew test` 통과. 전체 492개 중 466개 실행·앞선 web 26개 결과 재사용, 실패·건너뜀 없음. 고정 시계로 예약·리스 만료 경계, 실제 PostgreSQL의 점유·완료·비활성 전환과 HTTP 응답·인증·내부 정보 미노출 확인. 의존성·이미지 변경이 없어 이미지 빌드·공급망·배포 검사는 미실행 |
| 콜백 재시도 `ec521db` | 관련 89개와 `./gradlew test` 통과. 전체 476개 중 464개 실행·도메인 12개 결과 재사용, 실패·건너뜀 없음. 실제 로컬 HTTP의 초·날짜·잘못된 헤더, PostgreSQL의 예약 시각·재점유·페이로드 보존 확인. 마지막 한국어 오류 문구 수정은 모델 테스트 11개만 재검사. 의존성·이미지 변경이 없어 이미지 빌드·공급망·배포 검사는 미실행 |
| 추가 문구 정리 | Java 9개 파일은 주석만 변경됨을 확인. 문서의 코드 예제·기술 식별자·수치 보존, 로컬 링크·제목 참조·형식 검사 통과. 문서·주석만 변경해 Java·배포 검사는 재실행하지 않음 |
| 문구 정리 `42fd631` | web 전체 22개와 bootstrap 인증·메트릭 19개, 총 41개 테스트 통과. 실패·건너뜀 없음. 이미지 보관·공개 상태 검사 스크립트의 기존 테스트 2종 통과. Java·셸은 안내 문구만 변경, 대시보드는 제목·설명 외 쿼리·단위·설정 동일 확인. 변경 문서 링크 72개·코드 예제 보존·형식 확인. 전체 Java·이미지 빌드·실제 배포 검사는 문구 변경 범위에 해당하지 않아 미실행 |
| 보안 수정 `304bd0c` | `./gradlew test :bootstrap:verifyBootJarLicense` 통과. 447개 중 web·bootstrap 113개 실행, 나머지 334개 기존 결과 재사용. 실패·건너뜀 없음. 실제 JAR의 Tomcat 세 개 모두 11.0.25 확인 |
| 최종 배포 이미지 | linux/arm64 이미지 5개 빌드, OCI·LICENSE·cloudflared 실행·CLI/RPC 테스트 통과. 기존 정책으로 JAR·이미지 SBOM 7개에서 수정 가능한 HIGH·CRITICAL 0건. gosu 완전 삭제 후 바뀐 PostgreSQL·DB 작업 이미지 2개만 재검사하고 나머지 이미지 ID·아카이브 일치 확인 |
| 운영 검증 | Compose 정책·이미지 보관/복원·NGINX 검사 통과. 실제 PostgreSQL V1~V6 적용, 최소 권한, 비밀번호 교체·원복, WATCH 기동·재시작 통과. Docker Desktop 파일 공유 캐시 때문에 비밀 파일 덮어쓰기 대신 단계별 파일 경로를 사용하는 테스트로 수정 |
| Go 라이선스 분류 보정 | 사용하지 않는 테스트 모듈 5개 제거 후 빌드·CLI/RPC 통과. BSD 본문과 Go PATENTS를 확인한 모듈 5개는 Dependency Review 메타데이터 예외와 별도 버전·원문 체크섬 검사 적용. 정상 입력 통과, 버전·체크섬 변경 거부 확인. 최종 cloudflared 공급망 검사 통과 |
| DB 결과 정합성 `454360b` | 회귀·V5 업그레이드 테스트 11개 통과. `./gradlew test` 447개 중 159개 실행·288개 기존 결과 재사용, 실패·건너뜀 없음. 정상 이력 보존과 불완전한 이력의 적용 거부 확인 |
| 중복 코드 정리 `d6b0ca1` | 관련 테스트 69개 통과. `./gradlew test` 436개 중 424개 실행·domain 12개 기존 결과 재사용. 마지막 보조 클래스 통합 후 `:bootstrap:test` 91개 재검증 통과. 실패·건너뜀 없음. 메트릭 시작·기록 실패 시 전달 성공 유지, 리다이렉트 후 오류의 시간·횟수 보존 확인 |
| 이전 문구 정리 | 대시보드 JSON 정상, 제목 외 쿼리·단위·설정 동일 확인. 변경 문서 링크·형식과 `git diff --check` 통과. 문구만 바뀌어 Java·PromQL·배포 검사는 반복하지 않음 |
| 무료 메트릭 연결 `3739149` | `./ops/tests/prometheus-rules-test.sh` 통과. 새 설정 문법·기존 대시보드·경보 검사. 계정·수집기 정보가 없어 실제 인증·전송·수신은 미실행. Java·배포 이미지 변경이 없어 해당 검사는 반복하지 않음 |
| 테스트 준비 코드 정리 | `WorkerExecutionBudgetTest`·`ApacheHttpHopTransportTest`·`ApacheEventDeliveryTransportTest` 15개 실행·통과. 실패·건너뜀 없음. 테스트 보조 코드만 바뀌어 관련 검사만 실행 |
| 공용 HTTP 정리 `8bf0546` | 당시 `:adapter-out-external:test` 210개 실행·통과, 실패·건너뜀 없음. 본문·헤더 제한, TLS·DNS 고정, 취소·종료·타임아웃 확인 |
| 점검 코드 정리 `d3aa13a` | 당시 `./gradlew test` 통과. 435개 중 423개 실행·domain 12개 기존 결과 재사용, 실패·건너뜀 없음. 실제 PostgreSQL 검사 포함 |
| 검증 기록 도구 | Python 테스트 5개 통과. 성공·실패 종료 코드, 도구 부재, 실행 중 파일 변경, 시그널 종료 확인 |
| 개발 검증 절차·CI 등록 | 문서 링크 74개·워크플로 YAML·기존 필수 Gradle 명령 유지·이전 인계 본문 보존 확인 |
| 지시·스킬 정리 `15bd9b8` | 스킬 5개 형식·표시 정보와 문서 링크 44개 통과. 당시 문서 전용 검증 |
| WATCH 용량·복구·공급망 | 이번 코드 정리에서 별도 재실행하지 않음. 이전 결과와 차단 상태는 아래 표·이전 인계 기록 참조 |
| BATON 문구 `ef39f1fc` | 타입·빌드·정책 113개와 실행 대상 브라우저 512개 통과, 기기별 제외 43개. WATCH Java 검증과 구분 |

검증 소스가 바뀌지 않은 문서 수정은 링크·형식만 확인한다. 환경·의존성·원격 상태가 바뀌면
이전 성공을 새 실행 결과로 보고하지 않는다. 긴 검사는 실행 도구로 로그를 남기고 종료 코드를 확인한다.
공개 상태 중복 필드의 작업 기준은 `8003409`이며 검증한 코드·테스트는 `878b3a0`과 같다.
`.gradle/agent-validation/`의 `*-status-duplicates-preflight-reproduction/`·`*-status-duplicates-smoke-reproduction-fixed-fixture/`에 변경 전 실패,
`*-status-duplicates-smoke-tests/`·`*-status-duplicates-preflight-tests/`·`*-status-duplicates-shellcheck/`에 변경 후 성공,
`*-status-duplicates-complete/`에 종료 검사를 기록한다. 최초 스모크 재현 결과는 대역 오류로 유효하지 않아 재사용하지 않는다.

공급망 보고서 확인의 작업 기준은 `4807b30`이며 검증한 코드·테스트는 `6efdaca`와 같다.
`.gradle/agent-validation/`의 `*-supply-reports-reproduction/`에 변경 전 실패,
`*-supply-reports-tests/`·`*-supply-reports-license-policy/`·`*-supply-reports-shellcheck/`에 변경 후 성공,
`*-supply-reports-complete/`에 종료 검사를 기록한다.
Tomcat 요청 거부 로그 수정의 작업 기준은 `3934100`이며 검증한 코드·테스트는 `9f91333`과 같다.
`.gradle/agent-validation/`의 `*-rejected-logs-reproduction/`에 변경 전 실패,
`*-rejected-logs-tests/`·`*-rejected-logs-bootstrap/`에 변경 후 성공,
`*-rejected-logs-complete/`에 종료 검사를 기록한다.
인바운드 로그 보호의 작업 기준은 `b288d41`이며 검증한 코드·테스트는 `6a3ba2c`와 같다.
`.gradle/agent-validation/`의 `*-inbound-logs-reproduction/`에 변경 전 실패,
`*-inbound-logs-tests/`·`*-inbound-logs-bootstrap/`에 변경 후 성공,
`*-inbound-logs-complete/`에 종료 검사를 기록한다.
로그 감사 입력 수정의 작업 기준은 `35474ac`이며 검증한 코드·테스트는 `689332e`와 같다.
`.gradle/agent-validation/`의 `*-log-audit-inputs-reproduction/`에 변경 전 실패,
`*-log-audit-inputs-tests/`·`*-log-audit-inputs-shellcheck/`에 변경 후 성공,
`*-log-audit-inputs-complete/`에 종료 검사를 기록한다.
검증 취소 수정의 작업 기준은 `9b1b53d`이며 검증한 코드·테스트는 `2d9c0a0`과 같다.
`.gradle/agent-validation/`의 `*-validation-cancel-reproduction/`에 변경 전 실패,
`*-validation-cancel-tests/`·`*-validation-cancel-feedback-tests/`에 변경 후 성공,
`*-validation-cancel-complete/`에 종료 검사를 기록한다.
카운터 초기 등록의 작업 기준은 `0012e52`이며 검증한 코드·테스트는 `c3919a2`와 같다.
`.gradle/agent-validation/`의 `*-initial-counters-reproduction/`에 변경 전 실패,
`*-initial-counters-metrics/`·`*-initial-counters-bootstrap/`에 변경 후 성공,
`*-initial-counters-alerts/`에 경보·대시보드 검사, `*-initial-counters-complete/`에 종료 검사를 기록한다.
DB 테스트 입력 수정의 작업 기준은 `9687268`이며 최종 검증한 설정은 `99f8f2a`와 같다.
`.gradle/agent-validation/`의 `*-database-inputs-baseline/`·`*-database-inputs-reproduction/`에 변경 전 생략,
`*-database-inputs-registered/`·`*-database-inputs-sql-change/`에 변경 감지와 실행,
`*-database-inputs-document-reuse/`에 문서 변경 후 재사용, `*-database-inputs-complete/`에 종료 검사를 기록한다.
복구 응답 JSON 수정의 작업 기준은 `a59a79d`이며 검증한 코드·테스트는 `3e9f43a`와 같다.
`.gradle/agent-validation/`의 `*-recovery-response-json-reproduction/`에 재현 실패,
`*-recovery-response-json-tests/`에 수정 후 성공, `*-recovery-response-json-complete/`에 종료 검사를 기록한다.
전달 진단의 작업 기준은 `2b3d0e6`이며 검증한 SQL·테스트는 `e5baa5a`와 같다.
`.gradle/agent-validation/`의 `*-delivery-progress-diagnostics/`·`*-delivery-progress-persistence/`에 동작 검사,
`*-delivery-progress-complete/`에 종료 검사를 기록한다.
공개 상태 HEAD의 작업 기준은 `2128202`이며 검증한 코드·테스트는 `b500466`과 같다.
`.gradle/agent-validation/20260912T111835253888Z-public-head-reproduction/`에 변경 전 인증 응답,
`*-public-head-security/`·`*-public-head-web-bootstrap/`에 변경 후 성공,
`*-public-head-complete/`에 종료 검사를 기록한다. 첫 재현 검사의 MockMvc 본문 기대는 실제 Tomcat 검사로 옮겼다.
중단된 외부 호출 수정의 작업 기준은 `8c65f5f`이며 검증한 코드·테스트는 `54ffc79`와 같다.
`.gradle/agent-validation/`의 `*-interrupted-admission-reproduction/`에 재현 실패,
`*-interrupted-admission-cases/`·`*-interrupted-admission-external/`에 수정 후 성공,
`*-interrupted-admission-complete/`에 종료 검사를 기록한다.
사전 검사 개선의 작업 기준은 `cbbf31b`이며 검증한 코드·테스트는 `cc34bad`와 같다.
`.gradle/agent-validation/`의 `*-preflight-identity-reproduction/`에 재현 실패,
`20260912T104743781694Z-preflight-identity-cases/`에 최종 사전 검사 성공,
`*-preflight-identity-smoke/`·`*-preflight-identity-shellcheck/`에 공개 스모크·정적 검사 성공,
`*-preflight-identity-complete/`에 종료 검사를 기록한다. 중간 실패는 같은 사례의 진단과 오류 안내를 보완해 해결했다.
공개 TLS 만료 경보의 작업 기준은 `6fac970`이며 검증한 설정·테스트는 `2e39398`과 같다.
`.gradle/agent-validation/`의 `*-public-tls-alert-rules/`에 Prometheus 검사 성공,
`*-public-tls-alert-complete/`에 종료 검사를 기록한다.
이동 경로의 점 구간 수정 기준은 `9de365e`이며 검증한 코드·테스트는 `5e29b75`와 같다.
`.gradle/agent-validation/`의 `*-redirect-dot-reproduction/`에 재현 실패,
`*-redirect-dot-cases/`·`*-redirect-dot-external/`에 수정 후 성공,
`*-redirect-dot-complete/`에 종료 검사를 기록한다.
복구 응답 상태 수정의 작업 기준은 `804be51`이며 검증한 코드·테스트는 `6cf53b4`와 같다.
`.gradle/agent-validation/`의 `*-recovery-http-status-reproduction/`에 재현 실패,
`*-recovery-http-status-tests/`에 수정 후 성공, `*-recovery-http-status-curl/`에 실제 curl 확인,
`*-recovery-http-status-complete/`에 종료 검사를 기록한다.
중복 JSON 필드 수정의 작업 기준은 `c67539e`이며 검증한 코드·설정·테스트는 `188ea9f`와 같다.
`.gradle/agent-validation/`의 `*-duplicate-json-*-reproduction/`에 재현 실패,
`*-duplicate-json-http/`·`*-duplicate-json-recovery/`·`*-duplicate-json-web-bootstrap/`에 수정 후 성공,
`*-duplicate-json-complete/`에 종료 검사를 기록한다.
등록 JSON 타입 수정의 작업 기준은 `910984c`이며 검증한 설정·테스트는 `80822e5`와 같다.
`.gradle/agent-validation/`의 `*-monitor-input-reproduction/`에 재현 실패,
`*-monitor-input-http/`와 `*-monitor-input-web-bootstrap/`에 수정 후 성공,
`*-monitor-input-complete/`에 종료 검사를 기록한다.
상대 리다이렉트 수정의 작업 기준은 `11dde34`이며 검증한 코드·테스트는 `a078dab`과 같다.
`.gradle/agent-validation/`의 `*-relative-redirect-reproduction/`에 재현 실패,
`*-relative-redirect-cases/`와 `*-relative-redirect-external/`에 수정 후 성공,
`*-relative-redirect-complete/`에 종료 검사를 기록한다.
진단 진행 상태의 작업 기준은 `697e60b`이며 검증한 SQL·테스트는 `977de2c`와 같다.
`.gradle/agent-validation/`의 `*-diagnostic-check-status-tests/`와
`*-diagnostic-check-status-persistence/`에 동작 검사, `*-diagnostic-check-status-complete/`에 종료 검사를 기록한다.
PR #43 병합 작업 기준은 `9e7f647`이며 검증한 런타임 부하 테스트는 `d9eb4dd`와 같다.
수정 후 검사는 `.gradle/agent-validation/`의 `*-main-merge-runtime-load/`,
전체 변경의 종료 검사는 `*-main-merge-runtime-complete/`에 기록한다.
복구 인증 실패 중단의 작업 기준은 `d4d6370`이며 검증한 코드·테스트는 `75fdde4`와 같다.
`.gradle/agent-validation/`의 `*-recovery-access-reproduction/`에 재현 실패,
`*-recovery-access-tests/`와 `*-recovery-access-cli/`에 수정 후 성공,
`*-recovery-access-complete/`에 종료 검사를 기록한다.
리다이렉트 경로 수정의 작업 기준은 `83eb031`이며 검증한 코드·테스트는 `c7a4948`과 같다.
`.gradle/agent-validation/`의 `*-redirect-path-reproduction/`에 오류 재현,
`*-redirect-path-external/`에 수정 후 성공, `*-redirect-path-complete/`에 종료 검사를 기록한다.
복구 토큰 수정의 작업 기준은 `4cbb2a2`이며 검증한 코드·테스트는 `addfb76`과 같다.
`.gradle/agent-validation/`의 `*-recovery-token-reproduction/`에 재현 실패,
`*-recovery-token-tests/`에 수정 후 성공, `*-recovery-token-complete/`에 종료 검사를 기록했다.
소수 리비전 수정의 작업 기준은 `3966198`이며 검증한 설정·테스트는 `a4f7180`과 같다.
`.gradle/agent-validation/`의 `*-revision-float-reproduction/`에 재현 실패,
`*-revision-float-api/`에 수정 후 성공, `*-revision-float-complete/`에 종료 검사를 기록했다.
터널 gRPC 패치의 작업 기준은 `f559e28`이며 검증한 빌드 입력은 `389e677`과 같다.
빌드·공급망 로그는 `.gradle/agent-validation/`의 `*-cloudflared-grpc-build-fixed/`,
`*-cloudflared-grpc-supply-chain/`, 종료 검사는 `*-cloudflared-grpc-complete/`에 있다.
이미지 정보·실행 파일 의존성·SBOM·검사 DB 시각은 `.gradle/security-grpc-20260912/`에 보관했다.
GitHub 기본 기능 확인 작업 기준은 `2dac22f`다. 전체 변경의 종료 검사는
`.gradle/agent-validation/` 아래 `*-native-integrations-complete/`에서 확인한다.
GitHub Slack 안내 작업 기준은 `2b1ec98`이다. 전체 변경의 종료 검사는
`.gradle/agent-validation/` 아래 `*-github-slack-complete/`에서 확인한다.
외부 점검 결과 누락 검증 로그는 `.gradle/agent-validation/20260912T041309925019Z-public-check-missing-rules/`에 있다.
검증한 규칙·테스트는 `cd53727`과 같다. 작업 기준은 `b31ed1a`이며 전체 변경의 종료 검사는
같은 로그 디렉터리 아래 `*-public-check-missing-complete/`에서 확인한다.
Grafana 전송 설정 검증 로그는 `.gradle/agent-validation/20260912T035306515542Z-grafana-rate-limit-config/`에 있다.
검증한 설정은 `002b1f8`과 같다. 작업 기준은 `bde45f5`이며 전체 변경의 종료 검사는
같은 로그 디렉터리 아래 `*-grafana-rate-limit-complete/`에서 확인한다.
외부 상태 점검 검증 로그는 `.gradle/agent-validation/20260912T030437964435Z-grafana-public-contract/`에 있다.
검증한 JSON은 `123837a`와 같으며 이후 변경은 문서뿐이다. 작업 기준은 `1d137df`이며
전체 변경의 종료 검사는 같은 로그 디렉터리 아래 `*-grafana-public-complete/`에서 확인한다.
Slack 검증 로그는 `.gradle/agent-validation/20260912T024935880668Z-slack-notification/`에 있다.
검증한 설정·테스트는 `8734153`과 같으며 이후 변경은 문서뿐이다. 작업 기준은 `94de4c1`이며
전체 변경의 종료 검사는 같은 로그 디렉터리 아래 `*-slack-complete/`에서 확인한다.
Telegram 검증 로그는 `.gradle/agent-validation/20260912T024438902062Z-telegram-notification-final/`에 있다.
검증한 설정·테스트는 `aaa559e`와 같으며 이후 변경은 문서뿐이다. 작업 기준은 `40fb0fd`이며
전체 변경의 종료 검사는 같은 로그 디렉터리 아래 `*-free-api-complete/`에서 확인한다.
단계별 검증 로그는 `.gradle/agent-validation/` 아래 `20260912T005612580061Z-architecture-rejection/`,
`20260912T005742162507Z-feedback-final/`, `20260912T005808323738Z-feedback-tool-final/`,
`20260912T005830097914Z-feedback-gradle/`에 있다. 순서대로 위반 검출, 정상 구조 복원,
도구 시험, 전체 Java·JAR 검사다. 검증한 코드는 `034f240`과 같으며 이후 변경은 지시·문서뿐이다.
작업 기준은 `5b4da6d`이며 최종 문서까지 포함한 종료 기록은 `*-feedback-complete/`에서 확인한다.
취소된 대기열 로그는 `.gradle/agent-validation/20260911T234158295875Z-cancelled-queue-reproduction/`,
`20260911T234335433094Z-cancelled-queue-fixed/`, `20260911T234352926584Z-cancelled-queue-external/`에 있다.
뒤 경로도 같은 로그 디렉터리 아래다. 순서대로 오류 재현, 관련 재검사, 외부 통신 모듈 전체 검사이며
검증한 코드는 `b33ad1c`와 같다. 이후 변경은 PRD·ADR·HANDOFF뿐이다.
복원 리비전 로그는 `.gradle/agent-validation/20260911T233605582270Z-recovery-revision-reproduction/`,
`20260911T233617767594Z-recovery-revision-fixed/`, `20260911T233622307527Z-recovery-revision-full/`에 있다.
뒤 경로도 같은 로그 디렉터리 아래다. 순서대로 오류 재현, 관련 재검사, 도구 전체 검사이며
검증한 코드는 `584304e`와 같다. 이후 변경은 복원 절차와 HANDOFF뿐이다.
프록시 재시도 안내 로그는 `.gradle/agent-validation/20260911T232814871343Z-gateway-retry-after/`에 있다.
검증한 설정·테스트는 `89ccbb5`와 같으며 이후 변경은 API 계약·ADR·요청 제한 절차·HANDOFF뿐이다.
스냅샷 묶음 대조 로그는 `.gradle/agent-validation/20260911T231828038994Z-batch-snapshot-audit/`에 있다.
검증한 코드는 `7093ee6`과 같으며 이후 변경은 README·복원 절차·HANDOFF뿐이다.
쿼리 리다이렉트 로그는 `.gradle/agent-validation/20260911T230430192421Z-query-redirect-reproduction/`,
`20260911T230449293636Z-query-redirect-fix/`, `20260911T230513623240Z-query-redirect-external/`에 있다.
뒤 경로도 같은 로그 디렉터리 아래다. 순서대로 오류 재현, 실패한 테스트 재검사, 외부 통신 모듈 전체 검사이며
검증한 코드는 `27fa973`과 같다. 이후 변경은 문서뿐이므로 링크·형식만 확인한다.
실제 DB 잠금 검증 로그는 `.gradle/agent-validation/20260911T225022148209Z-db-lock-recovery/`,
`20260911T225218714946Z-postgres-error-translation-recovery/`,
`20260911T225238896558Z-postgres-error-translation-full/`에 있다. 뒤 경로도 같은 로그 디렉터리 아래다.
순서대로 오류 재현, 수정 후 실패한 테스트 재검사, 전체 회귀 검사 결과이며 검증한 코드는 `2a07f8b`와 같다.
이후 변경은 문서뿐이므로 링크·JSON 예제·형식을 확인하고 코드 검증은 재실행하지 않는다.
일시적 장애 응답은 `./gradlew :adapter-in-web:test :bootstrap:test --tests '*MonitorApiSecurityIntegrationTest' :bootstrap:verifyBootJarLicense`로 검증했다.
로그는 `.gradle/agent-validation/20260911T224050182451Z-api-temporary-failures/`에 있다.
검증한 코드·의존성 선언은 `dc83cf7`과 같고 이후 변경은 문서뿐이다.
변경 문서 4개의 로컬 링크 58개·API JSON 예제 7개와 형식 검사도 통과했다.
묶음 조회 로그는 `.gradle/agent-validation/20260911T222255884891Z-batch-query-targeted/`,
`20260911T222331145986Z-batch-query-response-tests/`, `20260911T222352483361Z-batch-query-full/`에 있다.
뒤 경로도 같은 로그 디렉터리 아래다. 전체 검사 중 문서 5개를 수정했으나 코드·테스트·설정은
변경하지 않았다. 문서만 검사 시작 버전으로 치환해 재계산한 전체 파일 지문이 시작 기록과
일치하며, 근거는 마지막 디렉터리의 `source-reuse.json`에 있다. 검증한 코드는 `1baac29`와 같다.
변경 문서 6개의 로컬 링크 59개·API JSON 예제 6개와 형식 검사도 통과했다.
점검 진행 상태 로그는 `.gradle/agent-validation/20260911T221018717905Z-check-progress-targeted/`와
`20260911T221049789749Z-check-progress-full/`에 있다. 뒤 경로도 같은 로그 디렉터리 아래에 있으며
각 `result.json`에 명령·기준 리비전·파일 지문을 기록했다. 검증한 코드는 `45e518a`와 같고,
이후 변경 문서 6개의 로컬 링크 58개와 형식 검사를 통과했다. 코드 검증은 재실행하지 않았다.
콜백 재시도 로그는 `.gradle/agent-validation/20260911T215456538064Z-callback-retry-after-targeted/`,
`20260911T215700426341Z-callback-retry-after-full/`, `20260911T215917253215Z-callback-retry-after-model-final/`에 있다.
뒤의 두 경로도 같은 로그 디렉터리 아래에 있으며 각 `result.json`에 명령·리비전·파일 지문을 기록했다.
이후 변경은 README·PRD·ADR·HANDOFF뿐이며 위 코드 검증 결과를 재사용한다.
추가 문구 검사는 `a71c1ad`와 비교했으며 `.gradle/agent-validation/`의 `clear-copy-followup` 기록에
검증 명령·파일 지문·종료 코드를 남겼다.
앞선 API 문구 변경은 `./gradlew :adapter-in-web:test :bootstrap:test --tests '*MonitorApiSecurityIntegrationTest' --tests '*MonitoringMetricsTest'`로 검증했다.
로그는 `.gradle/agent-validation/20260911T141447754615Z-clear-copy-api/`에 있다.
운영 스크립트 로그는 `20260911T141443213796Z-clear-copy-image-messages/`와
`20260911T141443256900Z-clear-copy-public-messages/`, 동작 보존 확인은
`20260911T141428892410Z-clear-copy-structure/`에 있으며 모두 같은 로그 디렉터리 아래에 있다.
이후 문서 수정에는 링크·형식 검사만 적용하고 위 코드 검증 결과를 재사용한다.
점검 코드 정리의 전체 로그는 `.gradle/agent-validation/20260905T132821057159Z-cleanup-full/`에 있다.
공용 HTTP 모듈 로그는 `.gradle/agent-validation/20260905T134006130498Z-http-module/`에 있다.
테스트 준비 코드 정리 로그는 `.gradle/agent-validation/20260905T135604532497Z-test-support-cleanup/`에 있다.
무료 메트릭 연결 로그는 `.gradle/agent-validation/20260906T154649360265Z-grafana-cloud-free/`에 있다.
문구 검증 로그는 `.gradle/agent-validation/20260907T225732881196Z-clear-copy/`에 있다.
중복 코드 정리 로그는 `.gradle/agent-validation/20260908T010228367490Z-cleanup-full/`,
최종 bootstrap 로그는 `.gradle/agent-validation/20260908T010354287577Z-cleanup-bootstrap-final/`에 있다.
DB 결과 정합성 검증 로그는 `.gradle/agent-validation/20260908T011910849207Z-result-integrity-full/`에 있다.
보안 수정의 Gradle 로그는 `.gradle/agent-validation/20260908T123100953206Z-security-gradle/`,
최종 DB 로그는 `.gradle/agent-validation/20260908T124041614696Z-security-postgres-secret-paths/`에 있다.
공급망 로그는 `.gradle/agent-validation/20260908T123327906934Z-security-supply-chain/`과
수정한 이미지 2개의 `.gradle/agent-validation/20260908T123845279539Z-security-postgres-rescan/`에 있다.
로컬 검증 이미지는 `e9d8e03`에 미커밋 수정을 반영한 후보였다. 배포용 이미지는 최종 커밋에서
새로 빌드·검증한다. 원격 linux/amd64 결과는 [PR #37](https://github.com/ljkhyeong/baton-watch/pull/37)에서 확인한다.

## 검증 환경

- 기본·번들 Python에는 `yaml`이 없다. YAML 검사는 PyYAML 6.0.3이 설치된
  `/private/tmp/baton-watch-skill-validation-20260905/bin/python`으로 통과했다. 다음 사용 전 경로 존재 여부를 확인한다.
- `actionlint`·ShellCheck는 PATH에 없었다. 로컬에서는 YAML 구문과 등록 명령을 확인했다.
  원격 CI 결과는 아래 병합 기록에 별도로 남겼다.

## 차단 상태와 다시 확인할 조건

| 항목 | 마지막 확인과 다음 조건 |
| --- | --- |
| WATCH 원격 병합 | 9월 8일 [필수 CI](https://github.com/ljkhyeong/baton-watch/actions/runs/34228797671) 통과 후 PR #37을 `main`에 병합했다. 병합 커밋은 `e2ad4b0`이며 당시 로컬·원격 main이 일치함을 확인했다 |
| 공식 cloudflared 후보 | 2026.8.3 공식 이미지에는 필요한 의존성 수정이 없어 공식 소스를 패치 버전의 의존성으로 빌드한다. 필요한 수정이 포함된 공식 이미지가 나오면 같은 공급망 검사를 통과한 뒤 별도 빌드를 제거할 수 있다 |
| 공식 이미지 패치 확인 | 기존 `MVP 이후 우선순위 정리` 작업에 매일 오전 9시 확인이 설정돼 있음. 새 자동화 추가 전 기존 설정을 조회하며, 같은 후보의 다운로드·검사를 중복 수행하지 않음 |
| 공개 스테이징 | `watch-staging.b4ton.com` DNS·스테이징 환경과 실제 연동 설정이 준비되면 [공개 검증 절차](docs/runbooks/baton-resource-health-verification.md) 재개 |
| BATON 원격 `main` | 9월 8일 `a9d1feb9` 병합·푸시와 원격 참조 확인. 최신 화면·문구와 WATCH 상태 조회·재점검을 함께 유지. 원본 체크아웃의 별도 작업은 보존 |

현재 상태 재확인 요청이 없다면 위 조건이 그대로인 작업은 재시도하지 않는다.
이 기록은 당시 확인 결과이며 현재 원격 상태나 배포 완료를 보장하지 않는다.

## 구현과 후속 작업

- 대상 GET은 헤더만 확인하고 본문을 읽지 않는다. 인증된 수동 재점검 API는 기존 일정·리스를 유지하며
  새 예약에 30초 간격을 적용한다. V5 마이그레이션이 필요하다.
- BATON 자료 상태 표시·재점검과 WATCH 독립 복원은 [연동 확인](docs/runbooks/baton-integration-review.md),
  [스냅샷 복원](docs/runbooks/baton-snapshot-recovery.md)에 구현·검증 범위를 기록했다.
- 공개 배포에는 이그레스 정책, 지원 규모·SLO, 공개 HTTPS 제한 검증, 대시보드·알림,
  암호화 백업·보존·복구 담당자와 Cloudflare·BATON 설정이 필요하다.
  [스테이징 배포](docs/runbooks/staging-deployment.md)와 [공개 이벤트 전달](docs/runbooks/public-staging-event-delivery.md) 절차를 따른다.
- 과거 커밋별 검증과 판단 근거는 [이전 인계 기록](docs/history/handoff-2026-09-05.md)에 보관했다.
