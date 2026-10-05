# BATON WATCH 인계

최종 수정일: 2026-10-05

## 현재 작업

- 정책 시험이 `WATCH_CHECK_ENABLED`를 지우지 않아 개발자 환경 값에 따라 Compose 렌더가 달라지던 문제를 고쳤다.
  DNS 조회도 HTTP 실행기처럼 작업 제출 전에 기한을 계산한다. HANDOFF의 main 병합 이전 기록 57개 항목과 검증 65행은
  [10월 5일 이전 인계 기록](docs/history/handoff-2026-10-05.md)으로 옮겼다. 추가 비용은 없으며 운영 배포는 미실행이다.
- `2485c4f`·`67b8dd4`에서 58개 파일, 순 644줄을 줄였다(운영 Java 110줄, 테스트 349줄, 빌드·설정 185줄).
  대상 점검과 콜백 전송의 공통 실행 골격을 `ApacheHttpRequestExecutor.executePinned` 하나로 합쳤다. 헤더 수신 시점 기록,
  닫기 방식, 요청별 IP 고정 클라이언트는 그대로다. 상수 상태만 돌려주던 시스템 상태 유스케이스 체인, 진입 클래스의 중복 사전 검사,
  내부 호출 null 검사, 중복 불변식, 테스트 전용 오버로드를 지웠다. 한 번도 동작하지 않은 `renovate.json`을 삭제하고
  Dependabot에 Compose 이미지 점검을 추가했다. Dockerfile은 공통 JRE 단계로 묶었고 해석되지 않는 의존성 검증 항목을 지웠다.
  Spring `@ConditionalOnBooleanProperty`는 `"true"` 문자열만 비교해 설정 바인딩의 `on`·`yes`·`1`과 어긋나므로 직접 구현한 조건을 유지했다.
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
| NGINX `pcre2` 취약점 | 10월 5일 공식 `1.30.4`·`1.30.5`·`1.31.6` alpine-slim 모두 `pcre2` 10.48-r0이라 CVE-2026-103111(HIGH)로 `verify` 공급망 검사가 실패한다. Alpine 저장소에는 10.49-r0이 있다. 공식 이미지가 다시 빌드되면 NGINX 다이제스트(현재 `compose.staging-tunnel.yml`)를 갱신하고 같은 검사를 통과시킨 뒤 PR #57을 병합한다. 자체 이미지 빌드·예외 추가는 하지 않기로 했다 |
| WATCH 원격 병합 | 9월 8일 [필수 CI](https://github.com/ljkhyeong/baton-watch/actions/runs/34228797671) 통과 후 PR #37을 `main`에 병합했다. 병합 커밋은 `e2ad4b0`이며 당시 로컬·원격 main이 일치함을 확인했다 |
| 공식 cloudflared 후보 | 2026.8.3 공식 이미지에는 필요한 의존성 수정이 없어 공식 소스를 패치 버전의 의존성으로 빌드한다. 필요한 수정이 포함된 공식 이미지가 나오면 같은 공급망 검사를 통과한 뒤 별도 빌드를 제거할 수 있다 |
| 공식 이미지 패치 확인 | 기존 `MVP 이후 우선순위 정리` 작업에 매일 오전 9시 확인이 설정돼 있음. 새 자동화 추가 전 기존 설정을 조회하며, 같은 후보의 다운로드·검사를 중복 수행하지 않음 |
| 공개 스테이징 | `watch-staging.b4ton.com` DNS·스테이징 환경과 실제 연동 설정이 준비되면 [공개 검증 절차](docs/runbooks/baton-resource-health-verification.md) 재개 |
| BATON 원격 `main` | 9월 8일 `a9d1feb9` 병합·푸시와 원격 참조 확인. 최신 화면·문구와 WATCH 상태 조회·재점검을 함께 유지. 원본 체크아웃의 별도 작업은 보존 |

현재 상태 재확인 요청이 없다면 위 조건이 그대로인 작업은 재시도하지 않는다.
이 기록은 당시 확인 결과이며 현재 원격 상태나 배포 완료를 보장하지 않는다.

## 구현과 후속 작업

- 대상 GET은 헤더만 확인하고 본문을 읽지 않는다. 인증된 수동 재점검 API는 기존 일정·리스를 유지하며
  새 예약에 30초 간격을 적용한다.
- BATON 자료 상태 표시·재점검과 WATCH 독립 복원은 [연동 확인](docs/runbooks/baton-integration-review.md),
  [스냅샷 복원](docs/runbooks/baton-snapshot-recovery.md)에 구현·검증 범위를 기록했다.
- 공개 배포에는 이그레스 정책, 지원 규모·SLO, 공개 HTTPS 제한 검증, 대시보드·알림,
  암호화 백업·보존·복구 담당자와 Cloudflare·BATON 설정이 필요하다.
  [스테이징 배포](docs/runbooks/staging-deployment.md)와 [공개 이벤트 전달](docs/runbooks/public-staging-event-delivery.md) 절차를 따른다.
- 과거 커밋별 검증과 판단 근거는 [9월 5일](docs/history/handoff-2026-09-05.md)·[10월 5일](docs/history/handoff-2026-10-05.md) 이전 인계 기록에 보관했다.
