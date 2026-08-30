# BATON WATCH 인계 문서

최종 수정일: 2026-08-30

## 현재 인계 상태

- 2026-08-30 재시도와 시계 테스트 대역을 기존 라이브러리 API로 단순화하고, URL
  정책 사례를 공통 정책 테스트로 모았으며 데이터베이스 권한은 실제 PostgreSQL
  동작으로 검증하도록 정리했다.
- 2026-08-29 저장소의 아웃바운드 안전 경계를 충족하지 않는 선택적 OTLP 전송과
  스테이징 관측 오버레이를 제거하고 내부 Prometheus 조회만 유지했다.
- 2026-08-29 점검·전달 영속성 포트를 실제 실행 방식에 맞는 단건 점유 계약으로
  좁히고, 계측 데코레이터가 업무 실패를 중복 변환하지 않도록 책임을 정리했다.
- 2026-08-29 공개 스테이징 URL 검증을 Python 표준 라이브러리 기반 공통 정책으로
  통합하고, 카운터 계측 실패가 점검·전달 업무 결과를 바꾸지 않도록 격리했다.
- 2026-08-29 Spring JDBC 구문 매개변수 로거를 차단하고 스테이징 URL 원문 경계와
  사전 검사 토큰 환경 정리를 강화했다.
- 2026-08-29 정적 Bearer 인증은 Spring Security Resource Server 모듈을 유지하되
  JWT/JOSE 구현을 함께 가져오는 Boot 스타터는 제거했다. 이벤트 재시도 상한은
  application 정책이 소유하고 카운터 계측 실패 격리는 한 번만 적용하도록 정리했다.
- 2026-08-29 작업 주기 최소값과 기존 도메인·데이터베이스 상태 불변식, DNS 호출자
  중단 처리를 회귀 테스트로 고정했다.
- Java 21 / Spring Boot 4.1.1 기반 모니터링 MVP와 하나의 BATON HTTPS 상태 변경
  콜백 전달이 구현되어 있다.
- 운영 배포, Cloudflare Tunnel 연결, 외부 알림, 프런트엔드, 메시지 브로커는
  구축되거나 검증되지 않았다.
- 관리 포트의 내부 Prometheus 조회만 제공한다. 외부 메트릭 전송, 외부 계정,
  대시보드와 알림은 구현하거나 생성하지 않았다.
- BATON 콜백 사전 검사 뒤에만 토큰을 `configtree`로 주입해 전달을 켜는 선택적
  스테이징 이벤트 전달 오버레이가 준비되어 있다. 실제 콜백 전달은 검증하지 않았다.
- Gradle·GitHub Actions·Docker 기본 이미지의 주간 Dependabot 점검을 활성화했다.
  다단계 Dockerfile·Compose·Alpine 패키지·Trivy의 추가 갱신 범위는
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

- `./gradlew --write-verification-metadata sha256 --refresh-dependencies clean test
  :bootstrap:verifyBootJarLicense --no-daemon --no-build-cache`가 Gradle 9.7.1에서
  364개 테스트를 실패·건너뜀 없이 실행했고, 실행 가능한 부트 JAR의 Apache-2.0
  전문 검증을 통과했다.
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
- 현재 코드 리비전으로 배포 이미지 세 개를 빌드한 뒤 실제 PostgreSQL 역할 분리,
  Flyway V1~V4, 비밀번호 교체·원복과 WATCH 재기동 스모크가 통과했다. 이미지는
  외부 레지스트리에 게시하거나 배포하지 않았다.
- 실제 공개 스테이징 배포, 외부 HTTPS 스모크와 외부 로그 감사는 수행하지 않았다.
- PR은 최신 HEAD에서 `Verify / verify`가 성공해야 병합할 수 있다.

## 공개 배포 차단 조건

다음 항목이 모두 승인되고 증거가 남기 전에는 공개 배포하지 않는다.

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

1. `main`의 최신 `Verify / verify`와 공급망 출처 증명 결과를 확인한다.
2. 공개 배포를 추진한다면 위 차단 조건부터 별도 운영 승인으로 해소한다.
3. 승인 뒤에만 [스테이징 배포 런북](docs/runbooks/staging-deployment.md)을 처음부터
   실행하고 내부·외부 스모크와 로그 비식별화 증거를 보관한다.
4. BATON 전달 검증은 스테이징 배포가 끝난 뒤
   [공개 전달 런북](docs/runbooks/public-staging-event-delivery.md)으로 별도 수행한다.
