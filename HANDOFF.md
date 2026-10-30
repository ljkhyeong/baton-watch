# BATON WATCH 인계

최종 수정일: 2026-10-30

## 현재 작업

- 10월 30일 전수검토로 문서·외부 통신·영속성·애플리케이션·웹·운영 6개 영역을 나눠 검토하고, 지적을 실제 PostgreSQL·
  Tomcat·소켓·이미지로 재현해 확인된 것만 고쳤다. 추가 비용은 없으며 운영 배포는 미실행이다.
  - `12ee5cd`: 스테이징처럼 소유자와 런타임 역할을 나누면 시도·결과 보존 정리가 `permission denied`로 항상 실패해
    30일 보존이 지켜지지 않았다. `FOR UPDATE`가 UPDATE 권한이 있는 열을 요구하기 때문이다. 삽입 뒤 읽지 않는
    `watch_attempt.lease_expires_at`만 열었다. 슈퍼유저로만 돌던 통합 시험의 사각지대를 막으려고 배포 권한 파일을
    적용한 런타임 역할로 어댑터 전체를 실행하는 시험을 추가했다. 정리는 완료 후보를 먼저 별도 트랜잭션으로 지워,
    밀린 이력 200만 건에서 약 840ms이던 비용이 2ms가 됐고 조회 시간 초과가 완료 후보 삭제를 되돌리지 않는다.
  - `a50fae0`: `/문서?검색=한글`이 `GET /?????=??`로 나가 다른 자원을 점검하던 결함을 고쳤다. 저장 값은 원문을 유지하고
    전송 직전에만 UTF-8 퍼센트 인코딩한다. 원문 UTF-8 `Location` 복원, 설정값과 같은 응답 헤더 개수 허용도 함께 고쳤다.
  - `160cbd1`: 토큰 없는 폼 `PUT` 본문을 보안 필터 전에 크기 제한 없이 읽던 필터를 껐다(잘못된 본문은 401 대신 500이었다).
    multipart 해석도 껐다. 묶음 조회는 쿼리 값을 문자 그대로 검증해 `a,b`·공백을 400으로 거부하고 잘못된 인코딩의 500을
    400으로 바꿨다. `LOGGING_LEVEL_ROOT=TRACE`로 실제 누출을 측정해 Bearer 토큰·요청 본문·대상 URL을 남기던 로거
    8개를 고정 목록에 추가했고, 운영 조립과 실제 PostgreSQL에서 값이 남지 않음을 시험으로 고정했다.
  - `ba89df9`: 예약 작업 대기를 켜면 Spring이 종료 신호를 스케줄러에 전달하지 않아 SIGTERM 뒤에도 새 점유가 이어졌다.
    `WorkerShutdownSignal`이 세 스케줄러를 함께 닫고 다음 점유만 막는다. 제안됐던 `await-termination=false`는 실행 중
    호출을 끊어 결과를 잃고 콜백 중복을 늘리므로 쓰지 않았다. 인터럽트 확인은 운영에서 항상 거짓이라 대체했다.
    저장 범위 밖 상태 코드(예: 999)를 응답으로 보고 판정을 해제하며, `egress.suspected`는 저장한 결과만 센다.
  - `ff57887`: 잠금 전에 정한 시각이 먼저 커밋한 요청보다 이르면 합류 대신 429가 되던 재점검 경합을 고쳤다.
  - `c9c7b53`: `database-role-init`이 테이블 권한을 회수해 실행 중 WATCH가 `migrate` 전까지 실패할 수 있던 구간을 없앴다.
    `pg_isready`를 TCP로 바꿔 초기화용 임시 서버를 준비 완료로 보지 않게 했고(10초간 소켓만 성공함을 측정),
    모니터 없는 백업의 복원 성공, 게이트웨이 `/api/v1`의 301을 고쳤다. 공개 스모크가 묶음 조회 경로도 확인한다.
  - `ecf95c2`: 만료된 `libexpat` 고정으로 이미지 빌드가 깨지던 문제와 새 취약점을 고쳤다. Temurin 21.0.13_9와 재빌드된
    PostgreSQL 18.6으로 올리고, 기반 이미지가 이미 가진 패키지의 고정을 없앴다. 남은 고정은 `brotli-libs`·`libcurl`·`su-exec`·
    `bash`다. 공식 `cloudflared` 2026.10.6이 취약점 0건이라 자체 빌드(`ops/cloudflared`)를 제거했다. 관련 Dependabot·GitHub
    경고는 파일 삭제로 해소된다. 실제 터널 연결은 확인하지 못했다.
  - 기각: 제어 바이트 헤더의 500은 재현되지 않았다(이미 400 `REQUEST_REJECTED`). HTTP가 아닌 응답의 분류는 상태 전이에
    영향이 없고 엄격한 파서로 바꾸면 깨진 서버를 오판할 수 있어 두었다. `%2F`·`%5C`의 HTML 400은 Tomcat 단계 거부로
    PRD에 명시했다(경로를 응답에 남기지 않음을 시험으로 고정). 문서 지적 25건은 코드와 대조해 반영했다.
- `b1975ed`에서 WATCH의 DNS·방화벽·NAT 장애로 모든 대상이 함께 실패하면 대상마다 `BROKEN` 이벤트가 BATON에
  전달되던 문제를 막았다. 점검 실행 서비스가 마지막 HTTP 응답 뒤 응답 없는 실패가 난 서로 다른 호스트를 세고,
  다섯 번째 호스트부터 응답 없는 실패를 기존 `INTERNAL_FAILURE`로 기록한다. 리다이렉트 응답을 포함해 어느 대상이든
  응답하면 바로 해제한다. 새 결과 분류·DB 열·API 필드는 없어 BATON은 기존 내부 실패 표시를 쓴다. 판정 건수는 배치
  로그의 `egressSuspected`와 `baton.watch.check.egress.suspected`로 확인하고, 시도 메트릭은 원래 결과를 남겨
  `WatchCheckEgressFailure` 경보는 그대로다. 호스트가 다섯 곳 이상이고 모두 실제로 응답하지 않는 환경은 `BROKEN` 대신
  `DEGRADED`·`UNKNOWN`에 머물고, 네 곳 이하면 판정에 닿지 않아 `BROKEN`까지 진행한다. PRD-0003·ADR-0002·[경보 절차](docs/runbooks/monitoring-alerts.md)·README에 반영했다.
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
- main 병합(`9dde469`) 이전 작업 기록은 [이전 인계 기록](docs/history/handoff-2026-10-05.md)에 보관했다.
- PR #57 병합(`64c9b16`)으로 `main`에 들어간 작업 기록은 [10월 16일 이전 인계 기록](docs/history/handoff-2026-10-16.md)에 보관했다.

## 최근 검증

| 대상 | 결과와 재사용 범위 |
| --- | --- |
| 전수검토 수정 `12ee5cd`~`ecf95c2` | 변경 전 재현: 런타임 역할 정리 42501, 200만 건 정리 839ms, 요청 줄 `/?????=??`, 원문 UTF-8 `Location` 깨짐, 무토큰 폼 `%zz` 500, 유효 토큰 `%ZZ` 500, `a,b` 200, 잘린 multipart 500, 종료 뒤 시도 2건, TRACE 로그의 Bearer 토큰·본문·대상 URL, 임시 서버에서 소켓만 준비 완료, 재점검 경합 429. 변경 후 `./gradlew test processRecoveryTest :bootstrap:runtimeLoadTest :bootstrap:verifyBootJarLicense` 통과(`test` 658개·복구 3개·부하 1개, 실패·건너뜀 없음, 부하 최종 백로그 0). 운영 시험 16개 통과(실제 PostgreSQL 역할·권한 e2e, 게이트웨이, promtool, Alertmanager 포함). 이미지 4개 빌드·OCI·LICENSE 검증과 공식 cloudflared 실행 확인 통과. 공급망 검사 SBOM 7개 모두 취약점 0건(Trivy 0.74.0, 2026-10-30 DB). 변이: pgJDBC 로거 차단·종료 신호·`/api/v1` 위치·역할 초기화 회수를 되돌리면 각각 실패. 공식 cloudflared는 스테이징과 같은 플래그·읽기 전용·권한 제거로 토큰 파일을 읽는 것까지 확인했고 실제 터널 연결은 미실행 |
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

main 병합 이전의 검증 결과는 [10월 5일](docs/history/handoff-2026-10-05.md)·[10월 16일](docs/history/handoff-2026-10-16.md) 이전 인계 기록에 보관했다.

검증 소스가 바뀌지 않은 문서 수정은 링크·형식만 확인한다. 환경·의존성·원격 상태가 바뀌면
이전 성공을 새 실행 결과로 보고하지 않는다. 긴 검사는 실행 도구로 로그를 남기고 종료 코드를 확인한다.

## 검증 환경

- 기본·번들 Python에는 `yaml`이 없다. YAML 검사는 PyYAML 6.0.3이 설치된
  `/private/tmp/baton-watch-skill-validation-20260905/bin/python`으로 통과했으나 2026-10-04에는 경로가 없었다.
  YAML 검사가 필요하면 PyYAML이 있는 환경을 먼저 준비한다.
- ShellCheck는 `/opt/homebrew/bin/shellcheck`에 있다. `actionlint`는 없어 로컬에서는 YAML 구문과 등록 명령을 확인했다.
  원격 CI 결과는 아래 병합 기록에 별도로 남겼다.
- Docker Hub 익명 요청 한도(시간당 100회)에 걸리면 빌드가 429로 실패한다. 같은 다이제스트를
  `mirror.gcr.io/library/...`에서 받으면 한도와 무관하게 내용을 확인할 수 있다.

## 차단 상태와 다시 확인할 조건

| 항목 | 마지막 확인과 다음 조건 |
| --- | --- |
| WATCH 원격 병합 | 10월 16일 `verify` 실행 38876048551 통과 후 PR #57을 `main`에 병합했다. 병합 커밋은 `64c9b16`이며 `main` 푸시 검증은 기록 시점에 실행 중이었다. `claude/check-false-positives`는 이번 전수검토까지 포함해 `main` 대상 PR로 올렸다. 이전 병합은 10월 5일 `9dde469`다 |
| 공식 cloudflared | 10월 30일 2026.10.6 공식 이미지가 공급망 검사를 통과해 자체 빌드를 제거했다. 이미지 실행·플래그는 확인했지만 실제 터널 연결은 스테이징에서 확인해야 한다. Compose 이미지는 Dependabot이 추적하므로 기존 `MVP 이후 우선순위 정리` 작업의 매일 확인은 더 필요 없다 |
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
- 과거 커밋별 검증과 판단 근거는 [9월 5일](docs/history/handoff-2026-09-05.md)·[10월 5일](docs/history/handoff-2026-10-05.md)·[10월 16일](docs/history/handoff-2026-10-16.md) 이전 인계 기록에 보관했다.
