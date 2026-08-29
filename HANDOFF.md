# BATON WATCH 인계 문서

최종 수정일: 2026-08-29

## 현재 인계 상태

- 2026-08-29 후속 유지보수 변경과 검증 기록은
  [PR #28](https://github.com/ljkhyeong/baton-watch/pull/28)에 있다.
- Java 21 / Spring Boot 4.1.1 기반 모니터링 MVP와 하나의 BATON HTTPS 상태 변경
  콜백 전달이 구현되어 있다.
- 운영 배포, Cloudflare Tunnel 연결, 외부 알림, 프런트엔드, 메시지 브로커는
  구축되거나 검증되지 않았다.
- GitHub Dependabot 보안 업데이트는 2026-08-29에 활성화했다.

## 유지되는 구현 기준

- 제품 범위와 HTTP 계약은 [PRD-0001](docs/PRD/0001_product-baseline/spec.md),
  [PRD-0002](docs/PRD/0002_api-contract/spec.md),
  [PRD-0003](docs/PRD/0003_monitoring-mvp/spec.md),
  [PRD-0004](docs/PRD/0004_health-change-event-delivery/spec.md)를 따른다.
- 저장소·실행·직접 전달 결정은 [ADR-0002](docs/ADR/0002_monitoring-mvp-storage-and-execution/adr.md)와
  [ADR-0003](docs/ADR/0003_health-change-event-delivery/adr.md)를 따른다.
- 외부 설정보다 우선하는 Spring Boot 환경 후처리기가 Tomcat 자원 상한,
  Apache HttpClient 민감 로거 차단, Actuator 허용 목록·상세 비공개,
  65초 예약 작업 종료 대기를 고정한다.
- 스테이징은 같은 Git SHA의 데이터베이스 작업·마이그레이션·WATCH 이미지 세 개를
  사용한다. [배포 런북](docs/runbooks/staging-deployment.md)은 이미지 ID·OCI
  리비전·아카이브 SHA-256 보관과 데이터베이스 소유자·런타임 비밀번호의 별도
  교체·원복 절차를 제공한다.

## 이번 변경의 검증 상태

- `./gradlew clean test :bootstrap:verifyBootJarLicense --no-daemon --no-build-cache`가
  Gradle 9.7.1에서 358개 테스트와 실행 가능한 부트 JAR의 Apache-2.0 전문 검증을
  통과했다.
- 실제 PostgreSQL 18.6 격리 검증에서 런타임·소유자 비밀번호를 각각 교체한 뒤
  이전 자격 증명이 거부됐고, 원복 뒤 교체된 자격 증명이 거부됐으며 WATCH 재기동이
  정상 완료됐다.
- 스테이징 Compose 정책, 이미지 ID·아카이브 체크섬·복원, 데이터베이스 비밀
  비노출, 이벤트 전달 사전 검사와 Compose 렌더링 검증이 통과했다.
- 같은 Git SHA의 데이터베이스 작업·Flyway 13.4 마이그레이션·WATCH 이미지 빌드와
  운영 셸 테스트 7종이 통과했다. 공개 스모크는 8개 격리 사례, 로그 비식별화
  감사는 7개 격리 사례를 검증했으며 새 셸 파일 4개는 ShellCheck를 통과했다.
- 실제 공개 스테이징 배포, 외부 HTTPS 스모크와 외부 로그 감사는 수행하지 않았다.
- PR은 최신 HEAD에서 `Verify / verify`가 성공해야 병합할 수 있다.

## 공개 배포 차단 조건

다음 항목이 모두 승인되고 증거가 남기 전에는 공개 배포하지 않는다.

- 공개 목적지만 허용하는 인프라 DNS·HTTP/HTTPS 이그레스 정책
- 활성 모니터·동기화·이벤트 폭주 부하 시험과 지원 규모·SLO
- Cloudflare 상태·모니터 경로별 요청 속도 제한과 `429` 외부 검증
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
