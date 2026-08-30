# BATON WATCH 인계 문서

최종 수정일: 2026-08-30

## 현재 인계 상태

- 2026-08-30 격리 PostgreSQL에서 동기화·점검·전달 부하와 DB 잠금·콜백 장애 후
  복구를 확인하는 `:adapter-out-persistence:loadTest`를 추가했다. 기본 100개,
  최대 1000개 모니터를 사용하고 일반 테스트와 분리했다. CI는 25개로 실행한다.
- 기존 메트릭을 사용하는 경보 규칙 8개와 `promtool` 테스트를 추가했다. 전달
  비활성 환경의 백로그는 경보에서 제외하며 실제 수집기·외부 알림은 연결하지 않았다.
- 표준 `pg_dump`로 `0600` 파일을 만들고 별도 임시 PostgreSQL에서 실제 복원하는
  도구를 추가했다. 운영 볼륨·외부 네트워크를 연결하지 않으며 마이그레이션 이력과
  이벤트 백로그 일치를 확인한다. 실제 운영 데이터 백업은 수행하지 않았다.
- CI는 DB 백업·복원 통합 테스트, 부하·복구 스모크와 경보 규칙을 검사하고 시험
  보고서를 7일 보관하도록 구성했다. 이 변경의 원격 CI 결과는 아직 확인하지 않았다.
- 이전 Java/Spring 정리에서 적용한 `JdbcClient`, Spring 예약 관측 자동 구성,
  Awaitility와 AssertJ 재사용을 유지했다. 이번 변경은 운영 애플리케이션 코드나
  HTTP 계약·DB 스키마·런타임 기본 설정을 바꾸지 않는다.
- Java 21 / Spring Boot 4.1.1 기반 모니터링 MVP와 하나의 BATON HTTPS 상태 변경
  콜백 전달이 구현되어 있다.
- 운영 배포, Cloudflare Tunnel 연결, 외부 알림, 프런트엔드, 메시지 브로커는
  구축되거나 검증되지 않았다.
- 관리 포트의 내부 Prometheus 조회와 적용 전 경보 규칙만 제공한다. 외부 메트릭
  전송, 외부 계정, 실제 대시보드와 알림 수신 경로는 구현하거나 생성하지 않았다.
- BATON 콜백 사전 검사 뒤에만 토큰을 `configtree`로 주입해 전달을 켜는 선택적
  스테이징 이벤트 전달 오버레이가 준비되어 있다. 실제 콜백 전달은 검증하지 않았다.
- Gradle·GitHub Actions·Docker 기본 이미지의 주간 Dependabot 점검을 활성화했다.
  다단계 Dockerfile·Compose·Alpine 패키지·Trivy·Prometheus 검사용 이미지의 갱신 범위는
  `renovate.json`에 준비했지만 외부 Renovate 서비스는 활성화하지 않았다.

## 유지되는 구현 기준

- 제품 범위와 HTTP 계약은 [PRD-0001](docs/PRD/0001_product-baseline/spec.md),
  [PRD-0002](docs/PRD/0002_api-contract/spec.md),
  [PRD-0003](docs/PRD/0003_monitoring-mvp/spec.md),
  [PRD-0004](docs/PRD/0004_health-change-event-delivery/spec.md)를 따른다.
- 저장소·실행·직접 전달 결정은 [ADR-0002](docs/ADR/0002_monitoring-mvp-storage-and-execution/adr.md)와
  [ADR-0003](docs/ADR/0003_health-change-event-delivery/adr.md)를 따른다.
- 외부 설정보다 우선하는 Spring Boot 환경 후처리기가 Tomcat 자원 상한,
  Apache HttpClient와 Spring JDBC 민감 로거 차단, Actuator 허용 목록·상세 비공개,
  65초 예약 작업 종료 대기를 고정한다.
- 스테이징은 같은 Git SHA의 데이터베이스 작업·마이그레이션·WATCH 이미지 세 개를
  사용한다. [배포 런북](docs/runbooks/staging-deployment.md)은 이미지 ID·OCI
  리비전·아카이브 SHA-256 보관과 데이터베이스 소유자·런타임 비밀번호의 별도
  교체·원복 절차를 제공한다.

## 이번 변경의 검증 상태

- 이번 구현·CI 코드 리비전은 `93e08eb`이다. 완료한 변경을 부하 시험, 경보,
  백업·복원, CI 연결로 나눠 커밋했다.
- `./gradlew clean test :bootstrap:verifyBootJarLicense
  :adapter-out-persistence:loadTest --no-daemon --no-build-cache`로 일반 테스트
  368개와 기본 100개 모니터 부하·복구 사례 2개를 실패·오류·건너뜀 없이 통과했다.
  6개 모듈의 테스트 작업을 다시 실행했고 부트 JAR의 Apache-2.0 전문 검증도 통과했다.
- CI와 같은 `-PwatchLoadMonitors=25` 시험과 `ScheduledTaskObservationTest`도
  구현·CI 커밋 뒤에 다시 실행해 통과했다.
- 부하 시험은 지연 0ms·20ms 모두 DB 잠금 실패 후 점검 결과를 확정하고, 콜백
  실패 전후 같은 페이로드를 유지하며 최종 백로그 0을 확인했다. 이 결과는 실제
  네트워크·운영 폴링·Hikari 풀·프로세스 재시작의 부하 증거가 아니다.
- DB 백업·복원 통합 테스트 3개에서 모니터·결과·재시도 중인 이벤트 복원,
  `0600` 권한과 덮어쓰기 거부, 손상된 아카이브·백로그 불일치 거부, 실패한 덤프의
  미완성 파일 정리를 확인했다. 임시 복원 컨테이너가 남지 않은 것도 확인했다.
- `./ops/tests/prometheus-rules-test.sh`로 경보 8개의 문법과 지속 장애·복구,
  비활성 전달·카운터 초기화·수집 대상 누락을 검사했다. 예약 실패 테스트는 실제
  Prometheus 메트릭 이름과 레이블도 확인한다.
- 전체 운영 셸의 Bash 구문과 ShellCheck, CI의 actionlint, 스테이징 Compose
  정책 테스트, 시험용 값으로 렌더링한 기본 Compose와 복원 시험 Compose 검사를 통과했다.
- 새 Gradle 의존성은 추가하지 않았다. `gradle/verification-metadata.xml`도
  변경하지 않았다. Prometheus 검사용 이미지는 버전과 다이제스트를 고정했다.
- 이전 운영 검증 이후의 Java/Spring 리팩터링을 반영한 배포 이미지 재빌드와
  운영 스모크는 수행하지 않았다. 아래 이전 기록을 현재 코드의 운영 검증 결과로
  사용하지 않는다.
- 실제 공개 스테이징 배포, 외부 HTTPS 스모크와 외부 로그 감사는 수행하지 않았다.
- PR은 최신 HEAD에서 `Verify / verify`가 성공해야 병합할 수 있다.

### 이전 작업에서 인계된 운영 검증 기록

다음 운영 검증은 이전 작업에서 남긴 결과이며 이번 변경에서 재실행하지 않았다.
당시 이미지 빌드의 정확한 코드 리비전은 이 문서에 기록되어 있지 않다.

- 실제 PostgreSQL 18.6 격리 검증에서 런타임 역할 속성·검색 경로·역할 소속·객체
  소유 금지와 소유자가 새로 만든 테이블·시퀀스·함수의 기본 권한 차단을 확인했다.
  런타임·소유자 비밀번호를 각각 교체한 뒤 이전 자격 증명이 거부됐고, 원복 뒤
  교체된 자격 증명이 거부됐으며 WATCH 재기동이 정상 완료됐다.
- 기본·터널·선택적 이벤트 전달 오버레이의 스테이징 Compose 정책, 이미지 ID·
  아카이브 체크섬·복원, 데이터베이스 비밀 비노출, 이벤트 전달 사전 검사와 Compose 렌더링
  검증이 통과했다.
- 비파괴 운영 셸 테스트 7종을 재실행했다. 공개 스모크 16개, 이벤트 전달 사전
  검사 9개, URL 원문 경계 27개, 로그 비식별화 감사 7개 격리 사례를 검증했다.
  공통 URL 정책의 Python 구문, 전체 운영 셸 파일의 Bash 구문과 로컬 고정
  `koalaman/shellcheck:v0.11.0` 정적 검사가 통과했다.
- 당시 코드로 배포 이미지 세 개를 빌드한 뒤 실제 PostgreSQL 역할 분리,
  Flyway V1~V4, 비밀번호 교체·원복과 WATCH 재기동 스모크가 통과했다. 이미지는
  외부 레지스트리에 게시하거나 배포하지 않았다.

## 공개 배포 차단 조건

다음 항목이 모두 승인되고 증거가 남기 전에는 공개 배포하지 않는다.
이번에 추가한 시험·경보·백업 도구만으로 아래 운영 조건이 해소되지는 않는다.

- 공개 목적지만 허용하는 인프라 DNS·HTTP/HTTPS 이그레스 정책
- 활성 모니터·동기화·이벤트 폭주 부하 시험과 지원 규모·SLO
- 비용 없는 상태·모니터 경로별 요청 속도 제한 대안과 `429` 외부 검증. 비용이
  발생할 수 있는 Cloudflare 유료 요청 속도 제한은 현재 범위에서 제외한다.
- 점유·완료 실패, 리스 회수, 일정 지연, 전달 백로그, 데이터베이스 시계 편차를
  포함한 외부 대시보드·알림 경로·임계치와 장애 주입 증거
- Cloudflare 계정·터널 토큰·DNS/TLS와 호환 BATON 콜백의 실제 준비
- 비어 있지 않은 공개 스테이징을 위한 별도 암호화 백업 위치, 승인된 보존 기간,
  암호화 키·복사·만료 삭제·정기 복원 시험 담당

## 다음 작업 진입점

1. 현재 브랜치의 PR에서 `Verify / verify`를 확인하고, 병합 뒤 `main`의 공급망
   출처 증명 결과를 확인한다. 이번 작업에서 PR 생성이나 병합은 수행하지 않았다.
2. [부하·복구 시험](docs/runbooks/load-recovery-test.md),
   [경보 적용 조건](docs/runbooks/monitoring-alerts.md), 배포 런북의 백업 절차를
   운영 환경 검증의 시작점으로 사용한다.
3. 공개 배포를 추진한다면 위 차단 조건부터 별도 운영 승인으로 해소한다.
4. 승인 뒤에만 [스테이징 배포 런북](docs/runbooks/staging-deployment.md)을 처음부터
   실행하고 내부·외부 스모크와 로그 비식별화 증거를 보관한다.
5. BATON 전달 검증은 스테이징 배포가 끝난 뒤
   [공개 전달 런북](docs/runbooks/public-staging-event-delivery.md)으로 별도 수행한다.
