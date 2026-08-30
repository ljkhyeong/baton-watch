# BATON WATCH

BATON WATCH는 BATON `RoleResource` URL 스냅샷을 비동기로 상태 점검하는 독립 Java/Spring 서비스입니다.

## 현재 구현

현재 구현된 서비스는 다음 기능을 제공합니다.

- `GET /api/v1/system/status`
- 인증된 `PUT` 및 `GET /api/v1/resource-monitors/{resourceReference}`. 조회 프로젝션은 마지막 점검 시각과 상태 도출에 반영한 마지막 확정 결과 시각을 구분하며, JSON `PUT` 본문은 인증 후 16 KiB로 제한되고 초과 시 `413 PAYLOAD_TOO_LARGE`를 반환
- 외부 설정보다 우선하는 Spring Boot 환경 후처리기로 요청·응답 헤더 8 KiB, 연결 128개, 수락 대기 32개, 워커 스레드 최대 32개·최소 유휴 4개, 워커 큐 64개를 고정한 내장 Tomcat 자원 상한
- 리비전 안전성을 보장하는 `ACTIVE`/`INACTIVE` 모니터 동기화
- PostgreSQL 기반 일정, 리스, 불변 시도·결과, 현재 상태, 전달 리스와 재시도 상태를 포함한 내구성 있는 상태 변경 이벤트
- 대상 점검과 콜백 전달을 항목 처리 직전에 한 건씩 점유하는 격리된 단일 스레드 실행 레인, 60초 직렬 배치 실행 예산, 제한된 JDBC 구문·트랜잭션·행 잠금 대기
- DNS 고정, SSRF·리다이렉트 방어, 시간·헤더·바이트 제한, 강제 상한이 있는 런타임 리소스 설정, 오래된 프로젝션 처리, 제한된 보존 기간을 갖춘 백그라운드 점검기
- API·유지보수·이벤트 전달을 유지하면서 인스턴스의 점검 예약만 끄는 시작 시 설정
- 고정 페이로드, 이벤트 ID 멱등성, DNS 고정, 리다이렉트 금지, 지연 상한이 있는 지수 백오프 재시도, 전달 완료 이벤트 보존을 적용한 하나의 BATON HTTPS 콜백 최소 한 번 전달
- 낮은 카디널리티의 점검·이벤트 전달 시도와 소요 시간 `outcome` 메트릭, 점유·완료 처리·만료 리스 회수 카운터, 현재 실행 중인 점검·전달 수, 최근 점검 배치의 최대 일정 지연, JVM과 PostgreSQL의 시계 편차, Spring 예약 실행 타이머, 이벤트 전달 백로그와 가장 오래된 미전달 이벤트의 경과 시간
- 공식 IANA 주소 레지스트리 체크섬을 매월 비교하고 변경 시 주소 정책과 경계 테스트의 수동 검토를 요구하는 드리프트 검사
- 헥사고날 구조의 Gradle 6개 모듈 구성
- 격리 PostgreSQL 부하·장애 복구 시험, 별도 JVM 강제 종료 후 리스·동일 이벤트 재전달 시험,
  작업자 미실행을 포함한 기존 메트릭 경보 규칙과 `promtool` 검사,
  `0600` DB 백업·별도 임시 PostgreSQL 복원 확인 도구,
  URL·비밀값을 제외한 리소스별 최근 점검·전달 상태의 읽기 전용 진단 도구
- 도메인, 애플리케이션, HTTP, 아웃바운드 정책, PostgreSQL 통합 테스트와 세 배포 이미지, Gradle 의존성 체크섬, 허용 라이선스를 적용한 변경 의존성 검토, CodeQL, ShellCheck, JAR·이미지 CycloneDX SBOM과 심각도별 취약점 차단을 실패 폐쇄 방식으로 검증하는 GitHub Actions 검사

운영자가 콜백과 별도의 서비스 토큰을 제공하기 전까지 전달은 비활성화됩니다. 전달이 비활성화되어 있거나 콜백을 사용할 수 없는 동안에도 대기 이벤트는 내구성 있게 유지됩니다. WATCH에는 프런트엔드나 브로커가 없으며, 저장소 산출물은 운영 배포나 외부 알림이 존재한다는 증거가 아닙니다. 외부 메트릭 전송 경로와 외부 계정·대시보드·알림 자동 생성은 제공하지 않습니다. 비용이 발생할 수 있는 Cloudflare 유료 요청 속도 제한과 R2 저장소는 현재 구성에 포함하지 않습니다. 인프라 이그레스 정책, 지원 규모·SLO, 비용 없는 요청 속도 제한 대안, 외부 대시보드·알림이 승인되기 전에는 공개 배포하지 않습니다.

## 기술과 모듈

- Java 21
- Spring Boot 4.1.1
- Gradle 9.7.1
- PostgreSQL 18.6
- Flyway 13.4.0 마이그레이션 이미지

운영 코드의 의존성은 안쪽을 향합니다.

bootstrap -> adapters -> application -> domain

모듈은 `domain`, `application`, `adapter-in-web`, `adapter-out-persistence`, `adapter-out-external`, `bootstrap`입니다.

## 빌드와 실행

저장소에는 공식 SHA-256 체크섬과 함께 9.7.1로 고정된 Gradle Wrapper와 해석된 빌드·테스트 의존성의 SHA-256을 기록한 `gradle/verification-metadata.xml`이 포함되어 있습니다. 의존성을 변경할 때는 `./gradlew --write-verification-metadata sha256 --refresh-dependencies clean test :bootstrap:verifyBootJarLicense --no-daemon --no-build-cache`를 실행하고 새 체크섬과 의존성 변경을 함께 검토해야 합니다. 전체 테스트 작업에는 Docker와 Docker Compose가 필요하며, PostgreSQL 통합 테스트는 건너뛰는 방식으로 성공할 수 없도록 의도적으로 구성되어 있습니다. `WATCH_PERSISTENCE_QUERY_TIMEOUT`과 `WATCH_PERSISTENCE_TRANSACTION_TIMEOUT`으로 기본 5초인 JDBC 구문·트랜잭션 제한을 재정의할 수 있으며, 둘 다 1~30초의 정수 초 단위 기간이어야 합니다.

HikariCP 풀은 최대 1~32, 최소 유휴 0~32로 제한하고 최소 유휴는 최대 풀 이하여야 합니다. 연결·검증 제한은 각각 250~30000ms이며 검증 제한이 연결 제한보다 작아야 합니다. 유휴 10000~1800000ms와 생존 확인 30000~1800000ms를 허용하며, 가변 풀의 유휴 제한은 최대 수명 30000~3600000ms보다 1000ms 이상 짧고 생존 확인은 최대 수명보다 짧아야 합니다. 초기화 실패 제한은 1~30000ms입니다. pgJDBC의 연결·로그인·취소 제한은 1~30초, 소켓 제한은 1~120초며, `WATCH_DB_TCP_KEEP_ALIVE`는 Spring이 지원하는 불리언 표현을 허용합니다. `SPRING_DATASOURCE_URL`은 `jdbc:postgresql://` 계층형 형식이어야 하며, 검증된 상한을 우회할 수 있는 JDBC URL 쿼리 매개변수는 허용하지 않습니다. 환경 변수 이름과 로컬 기본값은 [.env.example](.env.example)을 참고하세요.

점검·전달 폴링 주기는 최소 1초, 유지보수 주기는 최소 1분입니다. 일반 점검 간격은 최소 1분, 내부 실패 재시도는 최소 30초이며, 이벤트 전달의 최초·최대 재시도 지연은 각각 최소 5초입니다. 이보다 짧은 값은 시작 시 Spring 설정 속성 검증에서 거부됩니다.

`WATCH_CHECK_ENABLED`는 기본 `true`입니다. `false`로 바꾸고 프로세스를 다시
시작하면 그 인스턴스의 점검 예약만 중지됩니다. Compose에서는 환경 파일 변경 후
`restart`가 아니라 컨테이너 재생성이 필요합니다. API, 오래된 상태 처리, 이력 정리,
시계 편차 측정과 별도로 활성화한 이벤트 전달은 계속 동작합니다. 점검이 꺼져 있어도
설정 검증은 유지됩니다. 다중 인스턴스의 중지·재개와 경보 설정은
[점검 제어·진단 절차](docs/runbooks/check-control-and-diagnostics.md)를 따릅니다.
설정 바인딩과 예약 조건은 같은 Spring 불리언 변환을 사용합니다. `on`·`yes`·`1`과
앞뒤 공백도 해석하지만, 운영 설정에는 혼동을 줄이기 위해 `true`·`false`를 사용하세요.

점검과 전달은 배치 전체를 미리 점유하지 않고 각 외부 호출 직전에 한 건씩 점유합니다. 시작 시 각 리스가 선점·외부 호출·확정의 최악 실행 시간을 넘고, 직렬 배치가 `WATCH_WORKER_EXECUTION_BUDGET` 안에 끝나는지 검증합니다. 실행 예산 기본값과 상한은 60초이며 기본 점검 배치는 1건, 전달 배치는 2건입니다. 기본 데이터베이스·HTTP 제한에서 점검 배치는 최대 21초, 전달 배치는 최대 42초의 계산 예산을 사용합니다.

~~~bash
./gradlew clean test :bootstrap:verifyBootJarLicense --no-build-cache
~~~

일반 테스트는 실제 DB 백업·격리 복원과 읽기 전용 진단 CLI도 확인합니다. 별도 부하·복구 시험과 경보
규칙 검사는 다음 명령으로 실행합니다. 부하 시험은 기본 100개 모니터를 사용하며
운영 처리량이나 SLO를 인증하지 않습니다. 경보 검사는 수집기나 외부 알림을
활성화하지 않습니다.

~~~bash
./gradlew :adapter-out-persistence:loadTest --no-daemon
./gradlew :adapter-out-persistence:processRecoveryTest --no-daemon
./ops/tests/prometheus-rules-test.sh
~~~

프로세스 복구 시험은 점검 30초·전달 60초 리스의 자연 만료를 실제로 기다립니다.
시험용 자식 JVM·콜백 대역을 사용하므로 운영 부트 JAR 재시작이나 공개 HTTPS 전달을
검증한 것으로 해석하지 않습니다. 경보 규칙은 기본 예약 주기를 기준으로 하며,
주기를 바꾸면 [조회 구간과 시작 유예](docs/runbooks/monitoring-alerts.md)도 검토해야 합니다.

전체 Gradle 검증은 실행 가능한 부트 JAR의 `META-INF/LICENSE`가 저장소 `LICENSE`와
정확히 일치하는지도 확인합니다. Dockerfile의 데이터베이스 작업·마이그레이션·WATCH
이미지는 같은 라이선스를 `/usr/share/licenses/baton-watch/LICENSE`에 포함하고
`Apache-2.0` OCI 라이선스 레이블을 사용합니다. `검증` 워크플로는 세 이미지의
레이블과 라이선스를 확인합니다. Trivy는 독립 부트 JAR과 세 이미지의 CycloneDX
SBOM 네 개를 생성하고 수정 가능한 `HIGH`·`CRITICAL` 취약점이 있으면 실패합니다.
부트 JAR SBOM은 Trivy 표준 라이선스 스캐너와 명시적 허용 목록도 통과해야 합니다.
허용 라이선스가 없는 의존성은 차단하며, 라이선스 정보 누락·이중 라이선스 예외는
승인된 패키지와 버전에만 적용합니다. CI와 같은 정책 회귀 검사는
`python3 ops/tests/runtime-license-policy-test.py`로 실행합니다.
`main` 푸시의 전체 검증이 성공하면 JAR·SBOM 네 개·체크섬 공급망 산출물에 GitHub
출처 증명을 추가합니다. 이 산출물은 14일 동안 보관하며 컨테이너 이미지 자체는
현재 출처 증명 대상이 아닙니다.

Gradle·GitHub Actions·Docker 기본 이미지는 주간 Dependabot 점검을 사용합니다.
다단계 Dockerfile의 모든 기반 이미지, Compose 이미지, Alpine 고정 패키지와
Trivy·Prometheus 검사용 이미지의 추가 갱신 범위는 [renovate.json](renovate.json)에 준비해
두었습니다. Renovate 외부 서비스는 저장소 설정만으로 활성화되지 않으며 자동
병합도 허용하지 않습니다.

주소 정책 스냅샷을 수동으로 확인하려면 다음 명령을 실행합니다. 원본 체크섬이
달라지면 체크섬만 갱신하지 말고 주소 허용 정책과 경계 테스트를 먼저 검토해야 합니다.

~~~bash
./ops/check-iana-registry.sh
~~~

권장 로컬 컨테이너 실행을 위해 환경 템플릿을 복사하고, 시작 전에 데이터베이스와 모니터 API의 자리표시자 비밀값을 교체합니다. 콜백을 활성화할 때는 별도의 전달 토큰도 교체합니다.

~~~bash
cp .env.example .env
docker compose up --build
curl http://localhost:8080/api/v1/system/status
~~~

`WATCH_API_TOKEN`은 RFC 6750 `token68` 문자 집합을 사용하며 패딩 제외 문자가 32자 이상이고 전체 길이가 200자 이하여야 합니다. 32바이트 16진수 난수 값은 이 조건과 호환됩니다.

로컬 Compose는 WATCH와 비공개 PostgreSQL 18.6 서비스를 시작합니다. 데이터베이스 포트와 WATCH 관리 포트 `8081`은 공개되지 않으며, 관리 포트는 런타임 네트워크 내부에서 Actuator 상태와 Prometheus 메트릭을 제공합니다. WATCH는 메트릭을 외부 서비스로 직접 전송하지 않습니다. [선택적 이벤트 전달 오버레이](compose.staging-event-delivery.yml)는 BATON 콜백 사전 검사가 끝난 경우에만 전달을 활성화하고 토큰을 `configtree` 비밀 파일로 주입합니다. 이 오버레이를 선택하지 않으면 기본 스테이징은 이벤트 전달을 수행하지 않습니다. 스테이징 산출물은 데이터베이스 소유자와 WATCH 런타임 역할을 분리하고, 같은 Git SHA로 태그한 `baton-watch-database-operations`, `baton-watch-migrations`, `baton-watch` 이미지 세 개를 사용합니다. 운영 런북은 세 이미지의 ID·OCI 리비전·아카이브 SHA-256을 보관하고, 소유자·런타임 비밀번호를 SQL이나 명령행에 노출하지 않고 별도로 교체·원복하는 절차를 제공합니다. 일회성 역할 초기화와 Flyway 마이그레이션이 완료된 뒤에만 런타임을 시작합니다. 모니터를 동기화하려면 다음 명령을 사용합니다.

~~~bash
curl -X PUT http://localhost:8080/api/v1/resource-monitors/role-resource-123 \
  -H 'Authorization: Bearer replace-with-your-token' \
  -H 'Content-Type: application/json' \
  -d '{"sourceRevision":1,"monitoringState":"ACTIVE","targetUrl":"https://example.com/"}'
~~~

BATON은 자체 트랜잭션이 커밋된 뒤에만 동기화를 호출해야 합니다. 이후 리비전은 `targetUrl` 없이 `INACTIVE`를 사용하여 제한된 이력을 유지하면서 향후 점검을 중단할 수 있습니다.

상태 변경 전달을 활성화하려면 Compose를 시작하기 전에 `.env`에 다음 값을 설정합니다.

~~~dotenv
WATCH_EVENT_DELIVERY_ENABLED=true
WATCH_EVENT_DELIVERY_ENDPOINT=https://baton.example.com/api/v1/internal/resource-health-events
WATCH_EVENT_DELIVERY_TOKEN=replace-with-a-separate-32-character-token
~~~

엔드포인트는 포트 `443`의 절대 공개 글로벌 HTTPS URL이어야 하며 사용자 정보, 쿼리, 프래그먼트, IP 리터럴 호스트를 포함할 수 없습니다. BATON은 Bearer 토큰을 인증하고 `2xx`로 응답하기 전에 `Idempotency-Key`/`eventId`를 내구성 있게 중복 제거해야 합니다. 정확한 페이로드와 재시도 동작은 전달 계약을 참고합니다.
콜백 Bearer 토큰은 모니터 API 토큰과 달라야 하고 `[A-Za-z0-9._~-]` 문자 32~200개로 구성합니다.

## 라이선스

이 프로젝트는 [Apache License 2.0](LICENSE) 조건으로 사용할 수 있습니다.
라이선스 전문은 소스 저장소뿐 아니라 실행 가능한 부트 JAR과 세 배포 이미지에도
포함됩니다. 보안 문제는 공개 이슈 대신 [보안 정책](SECURITY.md)의 비공개 제보
절차를 사용하세요.

## 유지 문서

- [제품 기준선](docs/PRD/0001_product-baseline/spec.md)
- [API 계약](docs/PRD/0002_api-contract/spec.md)
- [모니터링 MVP](docs/PRD/0003_monitoring-mvp/spec.md)
- [상태 변경 이벤트 전달](docs/PRD/0004_health-change-event-delivery/spec.md)
- [마이크로서비스 경계 ADR](docs/ADR/0001_microservice-boundary/adr.md)
- [MVP 저장소 및 실행 ADR](docs/ADR/0002_monitoring-mvp-storage-and-execution/adr.md)
- [직접 HTTPS 이벤트 전달 ADR](docs/ADR/0003_health-change-event-delivery/adr.md)
- [Cloudflare Tunnel 스테이징 배포 런북](docs/runbooks/staging-deployment.md) — 포함된 스테이징 산출물은 실제로 가동 중이거나 인증되었거나 외부에서 검증된 배포의 증거가 아닙니다.
- [공개 스테이징 전달 검증 런북](docs/runbooks/public-staging-event-delivery.md)
- [격리 DB 부하·장애 복구 시험](docs/runbooks/load-recovery-test.md)
- [별도 JVM 중단·재시작 복구 시험](docs/runbooks/process-recovery-test.md)
- [기존 메트릭 경보 규칙과 적용 조건](docs/runbooks/monitoring-alerts.md)
- [점검 중지·재개와 읽기 전용 장애 진단](docs/runbooks/check-control-and-diagnostics.md)
- [보안 정책](SECURITY.md)
- [현재 인계 문서](HANDOFF.md)
