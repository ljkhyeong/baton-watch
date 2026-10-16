# BATON WATCH 인계

최종 수정일: 2026-10-16

## 현재 작업

- `d91cc9e`에서 NGINX 갱신 뒤 PR #57의 공급망 검사가 cloudflared 바이너리의 새 HIGH 취약점 3건으로 다시 실패한 문제를 수정했다.
  Go 표준 라이브러리 1.26.6과 `x/net` 0.58.0의 CVE-2026-78667·CVE-2026-78669·CVE-2026-97031이다. 별도 빌드의 Go 이미지를
  같은 계열 1.26.9로, `x/net`을 0.60.0으로 올리고 Go 도구가 함께 요구한 `x/crypto`·`x/sync`·`x/sys`·`x/term`·`x/text`를 반영했다.
  표시 버전은 `2026.8.3-watch.3`이다. 공식 소스·테스트 범위·라이선스 원문은 그대로이며 [빌드 입력](ops/cloudflared/README.md)에 반영했다.
  추가 비용은 없으며 운영 배포는 미실행이다.
- `dff37b0`에서 공식 NGINX 이미지의 `pcre2` CVE-2026-103111(HIGH)로 `verify` 공급망 검사가 실패하던 문제를 해결했다.
  10월 15일 다시 빌드된 공식 `1.30.6-alpine-slim`이 `pcre2` 10.49-r0과 OpenSSL 3.5.9-r0을 포함해, 정한 대로 자체 빌드·예외 없이
  `compose.staging-tunnel.yml`의 다이제스트만 같은 stable 계열로 갱신했다. NGINX 설정은 바꾸지 않았다.
  추가 비용은 없으며 운영 배포는 미실행이다.
- `b1975ed`에서 WATCH의 DNS·방화벽·NAT 장애로 모든 대상이 함께 실패하면 대상마다 `BROKEN` 이벤트가 BATON에
  전달되던 문제를 막았다. 점검 실행 서비스가 마지막 HTTP 응답 뒤 응답 없는 실패가 난 서로 다른 호스트를 세고,
  다섯 번째 호스트부터 응답 없는 실패를 기존 `INTERNAL_FAILURE`로 기록한다. 리다이렉트 응답을 포함해 어느 대상이든
  응답하면 바로 해제한다. 새 결과 분류·DB 열·API 필드는 없어 BATON은 기존 내부 실패 표시를 쓴다. 판정 건수는 배치
  로그의 `egressSuspected`와 `baton.watch.check.egress.suspected`로 확인하고, 시도 메트릭은 원래 결과를 남겨
  `WatchCheckEgressFailure` 경보는 그대로다. 모든 대상이 실제로 응답하지 않는 작은 환경은 `BROKEN` 대신
  `DEGRADED`·`UNKNOWN`에 머문다. PRD-0003·ADR-0002·[경보 절차](docs/runbooks/monitoring-alerts.md)·README에 반영했다.
  봇 차단 응답 분류는 새 결과 코드와 BATON 화면 변경이 함께 필요해 보류했다. 추가 의존성·비용은 없으며 운영 배포는 미실행이다.
- `58a2099`에서 모니터 API 토큰을 바꾸는 동안 BATON 요청이 401로 끊기던 문제를 해결했다. 선택 설정
  `WATCH_API_PREVIOUS_TOKEN`(`watch.api-previous-token`)의 이전 토큰 하나를 함께 허용하고 두 토큰을 모두 상수 시간으로
  비교한다. 이전 토큰도 같은 형식이어야 하며 현재 토큰·콜백 토큰과 같으면 시작에 실패하고, 빈 값은 설정하지 않은
  것으로 본다. 제거 시점은 시작 시 0으로 등록한 `baton.watch.api.previous.token.authentications`로 판단한다.
  로컬 Compose는 환경 변수를 전달하고, 스테이징은 교체 동안에만 쓰는 `compose.staging-api-token-rotation.yml`과
  진입 스크립트의 선택 복사로 configtree에 넣는다. PRD-0002·PRD-0003·ADR-0002·README와
  [스테이징 배포](docs/runbooks/staging-deployment.md)의 교체 순서에 반영했다. 추가 의존성·비용은 없으며 이미지 재빌드,
  실제 BATON 전환과 운영 배포는 미실행이다.
- `ff49f56`에서 WATCH 자체의 DNS·방화벽·NAT 장애로 모든 대상이 함께 실패해도 개별 대상 장애와 구분하는 경보가 없던
  문제를 보완했다. `WatchCheckEgressFailure`는 같은 인스턴스의 최근 5분 점검에 HTTP 응답을 받은 결과가 없고
  응답 없는 실패(`destination_rejected`·`dns_failure`·`connect_timeout`·`read_timeout`·`tls_failure`·`network_failure`)가
  3회 이상이면 2분 뒤 발생한다. 응답을 받은 점검이 하나라도 있으면 제외하고 `internal_failure`는 세지 않는다.
  응답 없는 실패 6종의 시도 카운터는 첫 증가량을 잃지 않도록 시작 시 0으로 등록한다. 경보는 `DEGRADED`·`BROKEN`
  전환과 BATON 이벤트를 막지 않는다. [경보 절차](docs/runbooks/monitoring-alerts.md)·PRD-0003·README에 반영했다.
  추가 의존성·비용은 없으며 수집기·알림 연결과 운영 배포는 미실행이다.
- `485ac2b`에서 대상 GET과 콜백 POST가 `User-Agent` 없이 나가 정상 링크를 실패로 판정하던 문제를 수정했다.
  2026-10-13 curl 확인에서 Wikipedia·GitHub API·Reddit은 헤더가 없으면 403, 고정 값을 보내면 200을 반환했다.
  `PinnedApacheClientFactory`가 `baton-watch (+https://github.com/ljkhyeong/baton-watch)`를 두 경로에 함께 보낸다.
  브라우저 값·Apache HttpClient·JVM 버전은 쓰지 않고 설정으로 바꾸지 않는다. 쿠키·인증 캐시·압축 해제 비활성화,
  본문 미소비, IP 고정은 그대로다. Stack Overflow·npm·Medium은 고정 값에도 403을 반환했으며 봇 차단은
  우회하지 않는다. [PRD-0003](docs/PRD/0003_monitoring-mvp/spec.md)·[PRD-0004](docs/PRD/0004_health-change-event-delivery/spec.md)·
  [ADR-0002](docs/ADR/0002_monitoring-mvp-storage-and-execution/adr.md)·README·외부 통신 스킬에 반영했다.
  추가 의존성·비용은 없으며 운영 배포는 미실행이다.
- `b0ae87f`·`5e00d0d`·`71535cb`·`6482fc3`·`adf68e8`에서 직접 구현·호환 코드·과잉 추상화를 Java·Spring API로 정리했다.
  - 규모: 171개 파일, 순 2,253줄 감소(운영 Java 635줄, 테스트 1,305줄, SQL·ops·빌드 220줄, 문서 93줄).
  - 후보 59건과 교차 검토 7건을 jar 근거로 반박 검증한 뒤 적용했다.
  - 도메인·애플리케이션: `TargetUrl` 생성자가 인코딩 문자까지 검사하고, 기존 DB 값 호환 경로를 지웠다.
    `TimeBoundaryPolicy`와 서비스 생성자의 설정 재검증, `HealthDerivationPolicy`를 지웠다.
    유지보수 유스케이스 6개는 2개로 합쳤다. 단건 조회 포트는 다건 조회로 통합했다.
  - 영속성:
    - `lock_timeout`은 pgJDBC `options`로 모든 런타임 연결에 건다. 비트랜잭션 조회도 1초 뒤 55P03(503)으로 실패한다.
      `PostgresTransactionOperations`는 외부 트랜잭션 합류 거부만 맡는다.
    - 백로그 요약 테이블·트리거는 `PENDING` 부분 인덱스 집계로 바꿨다.
    - 쓰지 않던 `created_at`·`updated_at`·시도 리스 토큰 열과 리스 토큰 고유 인덱스를 지웠다.
    - 선점의 시각 조회 왕복, 완료 전 결과 조회, 중복 REVOKE를 지웠다.
  - 외부 통신:
    - DNS·HTTP 실행기를 `BoundedTaskExecutor`로 합쳤다. DNS 종료 때 실행 중 조회의 호출자도 바로 `INTERNAL_FAILURE`를 받는다.
    - 실패 예외를 `OutboundHttpFailure`로 통합했다.
    - 퍼사드와 엔진을 `ApacheUrlChecker`·`ApacheHealthChangeEventSender`로 합쳤다.
    - 콜백 JSON은 전역 `spring.jackson` 설정과 분리된 `JsonMapper.shared()`로 직렬화한다.
    - 리다이렉트 상한 설정 `watch.http.max-redirects`는 지우고 `CheckObservation.MAX_REDIRECT_COUNT`(3)로 고정했다.
    - IP 고정 범위는 Apache 요청 authority에서 정한다. 그래서 `xn--`로 시작하는 호스트가 항상 `INTERNAL_FAILURE`가 되던 잠재 결함도 해소됐다.
  - 웹:
    - 본문 16 KiB 제한을 보안 필터에서 `RequestBodyAdvice`로 옮겼다. 그래서 405·406·415와 경로 참조 400이 413보다 먼저 판정된다.
    - `MonitorApiException`은 `ErrorResponseException`을 상속한다.
    - `spring-boot-starter-web`을 `spring-boot-starter-webmvc`로 바꿨다.
  - bootstrap·운영:
    - JDBC 어댑터·`@Primary` 계측 빈 쌍을 합쳤다.
    - 쓰지 않던 활성화 필드, 전달 전용 유지보수 주기·배치 키를 지웠다. 전달 정리도 공통 `watch.maintenance-*`를 쓴다.
    - `baton.watch.maintenance.items` 메트릭과 메트릭 기록 예외 격리를 지웠다. 예외를 던지는 `MeterFilter` 등의 금지 조건은 PRD-0003·ADR-0002에 남겼다.
    - Compose·yml의 기본값 복제와 Boot 환경 변수 매핑과 겹치던 자리표시자를 지웠다. 환경 변수 이름은 그대로다.
    - 종료 단계 30초는 고정 속성으로 옮겼다.
  - 시험:
    - `loadTest`·`capacityTest`와 런북 2개를 지웠다. 용량 판단은 [스테이징 배포](docs/runbooks/staging-deployment.md)로 옮겼다.
    - Testcontainers 싱글턴 컨테이너를 쓴다. 테스트 PostgreSQL 이미지는 Dockerfile 한 곳에서 받는다.
  - 기각: 보안 오류 응답의 MVC 위임(401 계약 변경·경로 노출 위험), `PostgresTransactionOperations` 삭제(Spring 전파 옵션으로 대체 불가),
    RowMapper 자동 매핑(행마다 드라이버 예외), BOM과 같은 버전 고정 삭제(Dependabot 보안 추적 상실),
    JdbcTemplate 제한의 Boot 속성 이관(단위 해석 불일치 위험 대비 이득 적음).
  - V1을 직접 고쳤으므로 이전 V1을 적용한 로컬 볼륨(`watch-postgres-data`)과 시험 DB는 Flyway 체크섬 검증에 실패한다.
    볼륨을 지우고 다시 만든다. 추가 비용은 없으며 운영 배포는 미실행이다.
- 정책 시험이 `WATCH_CHECK_ENABLED`를 지우지 않아 개발자 환경 값에 따라 Compose 렌더가 달라지던 문제를 고쳤다.
  DNS 조회도 HTTP 실행기처럼 작업 제출 전에 기한을 계산한다. HANDOFF의 main 병합 이전 기록 57개 항목과 검증 65행은
  [10월 5일 이전 인계 기록](docs/history/handoff-2026-10-05.md)으로 옮겼다. 추가 비용은 없으며 운영 배포는 미실행이다.
- `2485c4f`·`67b8dd4`에서 58개 파일, 순 644줄을 줄였다(운영 Java 110줄, 테스트 349줄, 빌드·설정 185줄).
  대상 점검과 콜백 전송의 공통 실행 골격을 `ApacheHttpRequestExecutor.executePinned` 하나로 합쳤다. 헤더 수신 시점 기록,
  닫기 방식, 요청별 IP 고정 클라이언트는 그대로다. 상수 상태만 돌려주던 시스템 상태 유스케이스 체인, 진입 클래스의 중복 사전 검사,
  내부 호출 null 검사, 중복 불변식, 테스트 전용 오버로드를 지웠다. 한 번도 동작하지 않은 `renovate.json`을 삭제하고
  Dependabot에 Compose 이미지 점검을 추가했다. Dockerfile은 공통 JRE 단계로 묶었고 해석되지 않는 의존성 검증 항목을 지웠다.
  Spring `@ConditionalOnBooleanProperty`는 `"true"` 문자열만 비교해 문서가 약속한 `on`·`yes`·`1` 해석과 잘못된 값의 시작 실패를 지키지 못하므로 직접 구현한 조건을 유지했다.
  Compose의 `pids_limit`과 `deploy.resources.limits.pids`는 Compose가 함께 요구해 중복이 아니다. 추가 비용은 없으며 운영 배포는 미실행이다.
- `7c6eeff`에서 운영 데이터가 생기기 전에 Flyway V1~V6을 최종 스키마를 바로 만드는 단일 V1로 통합했다.
  기존 데이터 이행 구문과 이행 경로 시험 3개를 지웠고, 대상 점검에서 항상 0이던 `responseBytes`와
  `watch_result.response_bytes` 열을 모델·저장소·진단·권한 SQL에서 제거했다. 이미 V1~V6을 적용한 로컬 DB는
  Flyway 검증이 실패하므로 볼륨을 지우고 다시 만든다. ADR-0002에 통합 결정을, PRD·런북에 단일 버전과 진단 출력을 반영했다.
  추가 의존성·비용은 없으며 운영 배포는 미실행이다.
- `ad46bbc`에서 같은 호출 경로의 중복 검증과 직접 구현을 정리했다. 운영 코드 25개 파일에서 순 148줄이 줄었다.
  서비스가 검증한 인자를 영속성 어댑터가 다시 검사하던 코드, 승인 주소·응답 헤더의 도달하지 않는 방어 코드,
  보안 인증 진입점의 중복 지정을 지웠다. 퍼센트 인코딩 검사·콜백 응답 소비·시간 게이지는 정규식·`readNBytes`·
  Micrometer `TimeGauge`로, 요청의 상태·URL 짝 검사는 Bean Validation으로 바꿨다. 문서 근거가 없던
  `retention > staleAfter` 비교와 쓰지 않던 `watch.http.max-response-bytes` 설정도 제거했다.
  API 응답·메트릭 이름·안전 상한은 그대로다. PRD-0003·ADR-0002·README에 반영했고 추가 의존성·비용은 없으며 운영 배포는 미실행이다.
- `7055aaa`에서 쿼리만 바꾸는 리다이렉트가 한글 경로를 퍼센트 인코딩해 같은 주소로 돌아오는 순환을
  첫 홉에서 놓치던 문제를 수정했다. Apache `URIUtils.resolve` 대신 현재 경로 원문에 새 쿼리를 붙인다.
  추가 연결도 DNS 재검증과 리다이렉트 상한 안에서 일어나던 결함이다. [ADR-0002](docs/ADR/0002_monitoring-mvp-storage-and-execution/adr.md)에
  해석 기준을 반영했다. 추가 의존성·비용은 없으며 운영 배포는 미실행이다.
- `fe04a66`에서 Trivy DB에 새로 반영된 Jackson CVE 5건(HIGH)으로 공급망 검사가 실패하는 문제를 수정했다.
  부트 JAR는 Spring Boot BOM의 `jackson-bom.version`을 3.1.7로 덮어쓰고 체크섬을 추가했다.
  마이그레이션 이미지는 Jackson 2.22.3·3.1.7을 포함한 공식 Flyway 13.8.1 다이제스트로 올렸으며,
  애플리케이션의 `flyway-core` 13.4.0과 라이선스 예외 목록은 유지했다. README의 이미지 버전을 맞췄다.
  NGINX의 `pcre2` CVE-2026-103111은 공식 이미지에 수정판이 없어 사용자 결정에 따라 공식 재빌드를 기다린다.
  그동안 `verify`가 실패하므로 PR #57 병합은 보류한다. 추가 비용은 없으며 운영 배포는 미실행이다.
- `0473d8e`에서 Alpine 3.24 저장소가 OpenSSL을 3.5.9-r0, libexpat을 2.8.5-r0으로 올려 이미지 빌드가 실패하는 문제를 수정했다.
  저장소는 최신 버전만 제공해 기존 고정값 3.5.8-r0·2.8.4-r0의 `apk add`가 종료 코드 8로 실패했고, 코드 변경 없이 PR #57의 `verify`가 막혔다.
  postgres·migrations·runtime 단계의 고정값 9곳만 올리고 나머지 패키지와 기반 이미지 다이제스트는 유지했다.
  Alpine 고정 패키지는 자동 갱신 대상이 아니어서 같은 문제가 반복될 수 있으므로 [배포 절차](docs/runbooks/staging-deployment.md)에 확인·갱신 방법을 적었다.
  추가 비용은 없으며 운영 배포는 미실행이다.
- `285bc26`에서 macOS 파일 검사가 `docs/prd`처럼 대소문자만 다른 Markdown 링크를 통과시키는 문제를 수정했다.
  경로 요소마다 `os.listdir`의 실제 이름과 비교해 Git·GitHub·Linux와 같은 기준으로 판정하며 표준 라이브러리만 사용한다.
  상대 경로·`..`·디렉터리·앵커 링크 처리와 외부 링크·코드 블록 제외는 유지한다. 새 검사로 찾은 HANDOFF의 소문자 PRD 링크 2개를 고쳤고
  [개발 검증 절차](docs/runbooks/development-validation.md)와 Claude 문서 스킬에 반영했다.
  `e09d5ee`에서 링크 오류에 파일 이름이 두 번 출력되던 문구를 정리했다. 추가 의존성·비용과 애플리케이션 동작 변경은 없다.
- `b4fb5b5`·`75c0cba`에서 동작을 바꾸지 않고 중복 코드를 정리했다. 68개 파일에서 순 1,008줄이 줄었다
  (운영 Java 39줄, 테스트 763줄, Gradle 12줄, ops·Compose 194줄).
  운영 코드는 Problem 정의, 모니터 행 잠금 조회, 실행기 생성의 같은 계층 중복만 합쳤다. 응답·SQL·스레드 설정은 그대로다.
  테스트는 영속성 동시 실행·잠금 틀을 지원 클래스로 모으고 같은 검사를 반복한 케이스를 매개변수 테스트로 합쳤다.
  Compose 공통 보안 설정은 YAML 앵커로 공유하고 런타임 역할 SQL은 heredoc으로 바꿨다.
  서로 다른 경계의 검증, 보안 상한, 자원 생성 전 검증, 응답 처리 골격, 이미지 구성은 유지했다. 추가 의존성·비용은 없으며 운영 배포는 미실행이다.
- Codex 스킬 5개를 Claude Code용 `.claude/skills/`로 옮기고 위치·테스트 클래스·검증 명령을 보강했다.
  외부 통신 정책은 `baton-watch-outbound-http`로 분리했다. 반복되는 재현→수정→`fix:`·`docs:` 커밋 절차,
  검증 기록·결과 재사용, 서비스 불변식 검토는 `baton-watch-defect-fix`·`baton-watch-validation`·`baton-watch-review`로 추가했다.
  JUnit XML에서 모듈별 테스트 수·실행 시각을 집계하는 스크립트를 검증 스킬에 넣었다.
  Codex 스킬의 `docs/prd`·`docs/adr` 소문자 링크를 Git 경로(`docs/PRD`·`docs/ADR`)에 맞췄다.
  macOS 로컬 검사는 대소문자를 구분하지 않아 이 오류를 잡지 못했다. 문서·스킬만 변경했으며 추가 의존성·비용은 없다.
- main 병합(`9dde469`) 이전 작업 기록은 [이전 인계 기록](docs/history/handoff-2026-10-05.md)에 보관했다.

## 최근 검증

| 대상 | 결과와 재사용 범위 |
| --- | --- |
| cloudflared Go·x/net `d91cc9e` | PR #57 CI 보고서에서 cloudflared만 HIGH 3종이 나오고 나머지 산출물 6개는 0건임을 확인. 같은 Trivy 0.74.0·기준으로 기존 로컬 cloudflared 이미지에서 같은 3종(4건)을 재현하고, 수정한 Dockerfile로 빌드한 이미지는 0건임을 확인. Docker 빌드 안의 CLI·터널 RPC 테스트와 모듈 LICENSE·PATENTS 체크섬 대조 통과, 표시 버전 `2026.8.3-watch.3`·사용자 `65532:65532`·라이선스 경로 확인. 로컬 arm64 이미지로 검사했으며 amd64와 전체 공급망 검사는 PR CI로 확인 |
| NGINX 1.30.6 `dff37b0` | 같은 Trivy 0.74.0 고정 이미지·기준(`HIGH`·`CRITICAL`, 수정판 있음)과 10월 16일 DB로 기존 `1.30.4`의 CVE-2026-103111 1건을 재현하고 `1.30.6`은 0건임을 확인. 이미지 안의 `pcre2` 10.49-r0·`libssl3` 3.5.9-r0 확인. `staging-compose-policy-test.sh`와 실제 NGINX·Blackbox를 쓰는 `gateway-test.py` 2개 통과. 로컬 arm64 이미지로 검사했으며 amd64 이미지와 전체 공급망 검사는 PR CI로 확인 |
| 외부 연결 장애 판정 `b1975ed` | 판정 기준 수, 응답 시 해제, 리다이렉트 뒤 실패, 호스트 대소문자, 내부 실패 제외, 서비스의 내부 실패 변환·측정값 보존, 스케줄러의 메트릭 기록을 각각 되돌린 변이 8개가 모두 테스트를 실패시키는 것을 대조군과 함께 확인. 관련 38개(도메인 9·애플리케이션 11·bootstrap 18) 통과. 도메인 변경으로 전체 `./gradlew test` 624개를 새로 실행해 통과(domain 26, application 55, web 47, 외부 통신 283, 실제 PostgreSQL 영속성 74, bootstrap 136, ArchUnit 3), 실패·건너뜀 없음. 다섯 호스트가 실패하면 다섯 번째가 `INTERNAL_FAILURE`·30초 재시도·원래 소요 시간으로 확정되는 것을 서비스 시험으로 확인. 복구·부하 시험은 단일 대상이나 모의 점검기를 써서 판정 조건에 닿지 않아 재실행하지 않음 |
| API 토큰 교체 `58a2099` | 새 기능이라 변경 전 코드로는 새 테스트가 컴파일되지 않아, 이전 토큰 무시·사용 기록 누락·현재 토큰 기록·현재 토큰 재사용 허용·형식 검사 누락·빈 값 거부·콜백 토큰 재사용 허용의 변이 7개가 각각 테스트를 실패시키는 것을 대조군과 함께 확인. 관련 웹 3개·bootstrap 4개 클래스 80개 통과 뒤 바인딩 시험을 더한 `WatchPropertiesTest` 13개 통과. 웹 전체 47개·bootstrap 전체 135개·ArchUnit 3개 통과, 실패·건너뜀 없음. 전체 `./gradlew test` 618개는 다른 모듈 입력이 같아 재사용. 환경 변수 `WATCH_API_PREVIOUS_TOKEN`과 마지막 줄바꿈이 있는 configtree 파일 `watch.api-previous-token`의 바인딩 확인. `staging-compose-policy-test.sh`를 기본·`WATCH_CHECK_ENABLED=false` 환경에서 통과하고 오버레이 대상을 바꾸면 실패함을 확인. 로컬 `baton-watch:67b8dd4` 이미지에 새 진입 스크립트만 덮어 이전 토큰 없음·있음(0400, UID 10001)·일반 파일 아님(시작 전 실패)을 가짜 토큰으로 확인. 이미지 재빌드·실제 BATON 전환은 미실행 |
| 전체 무응답 경보 `ff49f56` | 변경 전 첫 점검 전에는 시도 카운터가 없고(`{}`) Prometheus 출력에 `outcome="dns_failure"` 행이 없음을 2개 테스트로 재현. 변경 후 관련 11개·bootstrap 전체 128개·ArchUnit 3개 통과, 실패·건너뜀 없음. 고정 다이제스트의 공식 `promtool` v3.13.1로 전체 규칙·대시보드 쿼리 검사 통과. 새 시나리오 3개는 대기·발생·해제, 적은 실패 제외, 응답 기록 없는 시작·재시작, 수집 실패와 결과 분류 13종을 확인. 임계치·대기 시간·조회 구간 변경, `unless` 제거, 분류 12종 각각 제외의 변이 18개를 모두 잡는 것을 대조군과 함께 확인. 실제 수집기·Alertmanager 수신은 미확인. 외부 통신·영속성은 바꾸지 않아 해당 테스트는 재실행하지 않음 |
| 고정 User-Agent `485ac2b` | 변경 전 루프백 서버에서 대상 GET·콜백 POST의 `User-Agent`가 없음(`null`)을 2개 테스트로 재현. 변경 후 관련 35개(홉 전송·콜백 전송·TLS 고정)와 외부 통신 모듈 전체 283개 통과, 실패·건너뜀 없음. 헤더가 정확히 1개이고 값이 고정 문자열인지 두 경로에서 확인. 문제 확인은 공개 사이트 17곳에 `User-Agent`·`Accept` 유무 조합별 GET을 한 번씩 보낸 curl 결과다(`Accept`는 결과에 영향 없음). 자동 검증은 루프백 서버만 사용. 다른 모듈은 호출 API가 바뀌지 않아 재실행하지 않음 |
| Java·Spring API 정리 `b0ae87f`~`adf68e8` | 그룹마다 독립 검토 뒤 커밋했다. 최종 상태에서 `./gradlew test processRecoveryTest` 통과. 테스트 수: domain 25, application 51, web 46, 외부 통신 282, 영속성 74, bootstrap 128, ArchUnit 3, 복구 3(실패·건너뜀 0). web·영속성·application·bootstrap은 그룹 D·E에서 같은 입력으로 실행한 결과를 Gradle이 재사용했다. 이전 629개에서 줄어든 것은 호환·중복·프레임워크 반복 시험과 load·capacity 시험을 지웠기 때문이다. `runtimeLoadTest`(25개) 통과. 작업 트리로 빌드한 이미지 4종으로 `staging-database-operation-postgres-test.sh` 통과(권한 증거 `f|t|f|t|f|t|f|t|f|t|f`). `staging-compose-policy-test.sh`와 Compose 렌더 비교 통과(차이는 종료 단계 변수 삭제와 env 파일이 없을 때 값 없는 `WATCH_*`뿐). 오프라인 의존성 해석·`verifyBootJarLicense` 통과. 공급망·cloudflared·gateway 검사는 이미지 기반 변경이 없어 미실행 |
| 정책 시험 격리·DNS 기한 | `staging-compose-policy-test.sh`를 기본 환경과 `WATCH_CHECK_ENABLED=false` 환경에서 각각 통과, ShellCheck 통과. 외부 통신 모듈 281개 통과, 실패·건너뜀 없음. 다른 모듈·이미지 변경은 없어 반복하지 않음 |
| 골격·설정 정리 `2485c4f`·`67b8dd4` | 전체 629개(ArchUnit 3개·실제 PostgreSQL 포함) 새로 실행·통과, 실패·건너뜀 없음. domain 22→26·bootstrap 131→136개(매개변수 병합·설정 사례 확대), web 52→48·외부 통신 282→281개(같은 계층 중복 삭제), 나머지 동일. 리다이렉트 미추종은 설정을 임시로 빼면 302 사례가 실패함을 확인. `processRecoveryTest` 3개, `loadTest` 2개, `runtimeLoadTest` 1개 통과. `67b8dd4`로 이미지 5개를 로컬 빌드해 `verify-runtime-images.py`와 `staging-database-operation-postgres-test.sh` 통과. 변경 전후 이미지의 레이블·사용자·진입점·환경·패키지 목록이 같고 Compose 렌더 차이는 네트워크 `driver: bridge`뿐. 의존성 검증은 전체 구성 오프라인 해석으로 통과. Dependabot의 Compose 첫 실행과 CI 이미지 추출 변경은 원격에서 확인 필요 |
| 마이그레이션 통합 `7c6eeff` | 고정 PostgreSQL 이미지에서 기존 V1~V6 체인과 새 V1의 `pg_dump --schema-only`를 비교해 `response_bytes` 열·CHECK만 다르고 제약 이름·인덱스·리스 CHECK·트리거·함수와 백로그 초기 행이 같음을 확인. 전체 625개(ArchUnit 3개·실제 PostgreSQL 포함) 새로 실행·통과, 실패·건너뜀 없음. 영속성 84→81개(이행 경로 시험 3개 삭제), 나머지 모듈 개수 동일. `processRecoveryTest` 3개, `loadTest -PwatchLoadMonitors=25` 2개, `runtimeLoadTest -PwatchRuntimeLoadMonitors=25` 1개 통과. `7c6eeff`로 PostgreSQL·DB 작업·마이그레이션·런타임 이미지를 로컬 빌드(`--pull` 없음)해 `staging-database-operation-postgres-test.sh` 통과, Flyway V1 적용 증거·역할 권한·런타임 DML·WATCH 기동 확인. ShellCheck 통과. 공급망·cloudflared·gateway 검사는 변경 범위 밖이라 미실행 |
| 중복 검증 정리 `ad46bbc` | 전체 628개(ArchUnit 3개·실제 PostgreSQL 포함) 통과, 실패·건너뜀 없음. web 50→52개(상태·URL 짝 위반 2건 추가), bootstrap 130→131개(Prometheus 수집 이름·값 고정 추가), 나머지 모듈 개수 동일. 11개 요청의 상태·헤더·본문과 401 응답(HEAD·XML·HTML Accept 포함)이 변경 전후 같음을 확인. 콜백 응답 소비의 정확한 상한 읽기·탐색 바이트 미소비·선언 길이 사전 거부 유지. `processRecoveryTest` 3개, `loadTest -PwatchLoadMonitors=25` 2개, `runtimeLoadTest -PwatchRuntimeLoadMonitors=25` 1개 통과. 전체 `./gradlew test`는 모듈별 실행 뒤 입력이 같아 `UP-TO-DATE`로 재사용. 이미지·Compose 변경이 없어 해당 검사는 반복하지 않음 |
| 쿼리 전용 리다이렉트 `7055aaa` | 변경 전 `https://example.com/문서?page=2`에서 `?page=2`로 돌아오는 순환 미감지와 한글 경로·쿼리 인코딩 변경 3개 사례 재현. 변경 후 점검 엔진 66개·외부 통신 모듈 전체 282개 통과, 실패·건너뜀 없음. 기존 ASCII 경로·인코딩·빈 쿼리·점 구간 사례 유지 확인. 단일 클래스 변경이라 전체 Java·DB·이미지 검사는 반복하지 않음 |
| Jackson·Flyway 취약점 `fe04a66` | 변경 전 CI 보고서에서 부트 JAR·WATCH·마이그레이션 이미지의 Jackson CVE 5건과 NGINX `pcre2` 1건 확인. 변경 후 체크섬 갱신과 함께 전체 622개(ArchUnit 3개·실제 PostgreSQL 포함) 새로 실행·통과, 실패·건너뜀 없음, `verifyBootJarLicense` 통과. 로컬 arm64 5개 이미지 빌드, OCI·라이선스 검사, 실제 PostgreSQL DB 작업 검증(Flyway 13.8.1의 V1~V6) 통과. 같은 Trivy 0.74.0·기준으로 부트 JAR와 자체 이미지 4개 0건, NGINX만 1건 남음 확인. cloudflared는 변경이 없어 CI 결과(0건)를 사용 |
| Alpine 고정 버전 `0473d8e` | 변경 전 amd64 PostgreSQL 기반 이미지에서 3.5.8-r0 설치가 CI와 같은 종료 코드 8로 실패하는 것 재현. 변경 후 amd64에서 세 단계의 고정 패키지 설치 성공. 로컬 arm64에서 CI와 같은 5개 대상 빌드, 이미지 내 3.5.9-r0·2.8.5-r0 설치, `verify-runtime-images.py` 라이선스·OCI 검사와 실제 PostgreSQL DB 작업 검증 통과. Java·SQL 변경이 없어 Gradle 테스트는 반복하지 않았고, Trivy 공급망 검사는 CI 결과로 확인 |
| Markdown 링크 대소문자 `285bc26`·`e09d5ee` | 변경 전 디렉터리·파일 이름의 대소문자만 다른 링크 3개가 macOS에서 통과하는 오류 재현. 변경 후 파일 검사 도구 전체 10개 통과. 대소문자 불일치 거부와 정확한 경로의 `..`·`./`·디렉터리·앵커 링크 허용 확인. 추적 Markdown 전체를 새 검사로 확인해 HANDOFF 링크 2개 외 불일치 0개. 오류 문구 전체 비교로 파일 이름 중복 3건 재현 후 제거, 전체 10개 재통과와 XML 구문 오류 거부 유지 확인. 임시 Git 저장소의 합성 파일만 사용했으며 애플리케이션·DB·이미지 변경이 없어 해당 검사는 반복하지 않음 |
| 중복 코드 정리 `b4fb5b5`·`75c0cba` | 전체 622개(ArchUnit 3개·실제 PostgreSQL 포함) 통과, 실패·건너뜀 없음. domain 22·application 54·web 50·외부 통신 279·영속성 84·bootstrap 130개. 다른 테스트와 같은 값을 검증하던 domain 1개를 지웠고, 병합한 매개변수 테스트로 application·외부 통신이 각 1개 늘었다. 영속성 클래스별 개수는 그대로다. `processRecoveryTest` 3개, `loadTest -PwatchLoadMonitors=25` 2개, `runtimeLoadTest -PwatchRuntimeLoadMonitors=25` 1개 통과. Compose 6개 조합의 `config --format json`과 역할 SQL 출력이 변경 전과 바이트 단위로 같고, 변경한 ops 테스트 10종과 ShellCheck가 통과했다. 전체 `./gradlew test`는 모듈별 실행 뒤 입력이 같아 `UP-TO-DATE`로 재사용했다. 고정 Blackbox 이미지가 로컬에 없어 `gateway-test.py`는 미실행이다. 역할 스크립트의 실제 이미지 실행은 이미지 재빌드가 필요해 CI에서 확인한다. `capacityTest`도 미실행 |
| 에이전트 스킬 | 변경 파일 16개의 파일 검사와 종료 검사 통과. Git 경로 기준 대소문자 구분 링크 검사에서 끊긴 링크 0개, Claude 스킬 9개의 이름·frontmatter 키 확인. 스킬이 참조한 클래스·경로의 존재 확인. 집계 스크립트는 기존 결과에서 bootstrap 130개·외부 통신 278개로 이전 기록과 일치했고, `domain` 필터 실행 뒤 해당 스위트만 남는 동작도 확인. PyYAML 경로가 없어 YAML 파서 검사는 미실행. 문서·스킬만 변경해 애플리케이션 테스트는 실행하지 않음 |

main 병합 이전의 검증 결과는 [이전 인계 기록](docs/history/handoff-2026-10-05.md)에 보관했다.

검증 소스가 바뀌지 않은 문서 수정은 링크·형식만 확인한다. 환경·의존성·원격 상태가 바뀌면
이전 성공을 새 실행 결과로 보고하지 않는다. 긴 검사는 실행 도구로 로그를 남기고 종료 코드를 확인한다.

## 검증 환경

- 기본·번들 Python에는 `yaml`이 없다. YAML 검사는 PyYAML 6.0.3이 설치된
  `/private/tmp/baton-watch-skill-validation-20260905/bin/python`으로 통과했으나 2026-10-04에는 경로가 없었다.
  YAML 검사가 필요하면 PyYAML이 있는 환경을 먼저 준비한다.
- `actionlint`·ShellCheck는 PATH에 없었다. 로컬에서는 YAML 구문과 등록 명령을 확인했다.
  원격 CI 결과는 아래 병합 기록에 별도로 남겼다.

## 차단 상태와 다시 확인할 조건

| 항목 | 마지막 확인과 다음 조건 |
| --- | --- |
| NGINX `pcre2` 취약점 | 10월 16일 해결. 10월 15일 빌드된 공식 `1.30.6-alpine-slim`이 `pcre2` 10.49-r0을 포함해 `dff37b0`에서 다이제스트를 갱신했다. PR #57의 원격 `verify` 통과를 확인한 뒤 병합한다. `claude/simplify-validation`은 PR #57 브랜치 위에, `claude/check-false-positives`는 다시 그 위에 쌓여 있고 두 브랜치에도 이 갱신을 병합했다. CI는 PR에서만 실행되므로 두 브랜치의 변경은 원격 `verify`를 아직 거치지 않았다 |
| WATCH 원격 병합 | 9월 8일 [필수 CI](https://github.com/ljkhyeong/baton-watch/actions/runs/34228797671) 통과 후 PR #37을 `main`에 병합했다. 병합 커밋은 `e2ad4b0`이며 당시 로컬·원격 main이 일치함을 확인했다 |
| 공식 cloudflared 후보 | 2026.8.3 공식 이미지에는 필요한 의존성 수정이 없어 공식 소스를 패치 버전의 의존성으로 빌드한다. 필요한 수정이 포함된 공식 이미지가 나오면 같은 공급망 검사를 통과한 뒤 별도 빌드를 제거할 수 있다 |
| 공식 이미지 패치 확인 | 기존 `MVP 이후 우선순위 정리` 작업에 매일 오전 9시 확인이 설정돼 있음. 새 자동화 추가 전 기존 설정을 조회하며, 같은 후보의 다운로드·검사를 중복 수행하지 않음 |
| 공개 스테이징 | `watch-staging.b4ton.com` DNS·스테이징 환경과 실제 연동 설정이 준비되면 [공개 검증 절차](docs/runbooks/baton-resource-health-verification.md) 재개 |
| BATON 원격 `main` | 9월 8일 `a9d1feb9` 병합·푸시와 원격 참조 확인. 최신 화면·문구와 WATCH 상태 조회·재점검을 함께 유지. 원본 체크아웃의 별도 작업은 보존 |

현재 상태 재확인 요청이 없다면 위 조건이 그대로인 작업은 재시도하지 않는다.
이 기록은 당시 확인 결과이며 현재 원격 상태나 배포 완료를 보장하지 않는다.

## 구현과 후속 작업

- 봇 차단 응답 분류는 [ADR-0005 제안](docs/ADR/0005_access-blocked-outcome/adr.md)으로 남겼다. BATON이 `lastOutcome`을 enum으로
  엄격하게 읽어 모르는 값이면 조회 불가로 처리하므로, BATON이 `ACCESS_BLOCKED`를 먼저 받아들인 뒤 WATCH를 바꾼다.
- 오래 `BROKEN`인 대상의 점검 간격 확대는 10분 만료 규칙과 얽혀 `BROKEN`·`UNKNOWN` 이벤트가 반복될 수 있어 보류했다.
- 대상 GET은 헤더만 확인하고 본문을 읽지 않는다. 인증된 수동 재점검 API는 기존 일정·리스를 유지하며
  새 예약에 30초 간격을 적용한다.
- BATON 자료 상태 표시·재점검과 WATCH 독립 복원은 [연동 확인](docs/runbooks/baton-integration-review.md),
  [스냅샷 복원](docs/runbooks/baton-snapshot-recovery.md)에 구현·검증 범위를 기록했다.
- 공개 배포에는 이그레스 정책, 지원 규모·SLO, 공개 HTTPS 제한 검증, 대시보드·알림,
  암호화 백업·보존·복구 담당자와 Cloudflare·BATON 설정이 필요하다.
  [스테이징 배포](docs/runbooks/staging-deployment.md)와 [공개 이벤트 전달](docs/runbooks/public-staging-event-delivery.md) 절차를 따른다.
- 과거 커밋별 검증과 판단 근거는 [9월 5일](docs/history/handoff-2026-09-05.md)·[10월 5일](docs/history/handoff-2026-10-05.md) 이전 인계 기록에 보관했다.
