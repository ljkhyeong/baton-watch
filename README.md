# BATON WATCH

BATON WATCH는 BATON `RoleResource` URL 스냅샷을 비동기로 상태 점검하는 독립 Java/Spring 서비스입니다.

## 현재 구현

현재 구현된 서비스는 다음 기능을 제공합니다.

- `GET /api/v1/system/status`
- 인증된 `PUT` 및 `GET /api/v1/resource-monitors/{resourceReference}`. JSON `PUT` 본문은 인증 후 16 KiB로 제한되며 초과 시 `413 PAYLOAD_TOO_LARGE`를 반환
- 리비전 안전성을 보장하는 `ACTIVE`/`INACTIVE` 모니터 동기화
- PostgreSQL 기반 일정, 리스, 불변 시도·결과, 현재 상태, 전달 리스와 재시도 상태를 포함한 내구성 있는 상태 변경 이벤트
- 대상 점검, 콜백 전달, 유지보수를 위한 격리된 단일 스레드 실행 레인과 제한된 JDBC 구문·트랜잭션 행 잠금 대기
- DNS 고정, SSRF·리다이렉트 방어, 시간·헤더·바이트 제한, 강제 상한이 있는 런타임 리소스 설정, 오래된 프로젝션 처리, 제한된 보존 기간을 갖춘 백그라운드 점검기
- 고정 페이로드, 이벤트 ID 멱등성, DNS 고정, 리다이렉트 금지, 지연 상한이 있는 지수 백오프 재시도, 전달 완료 이벤트 보존을 적용한 하나의 BATON HTTPS 콜백 최소 한 번 전달
- 낮은 카디널리티의 점검 시도·소요 시간 `outcome` 메트릭, 최근 점검 배치의 최대 일정 지연, Spring 예약 실행 타이머, 이벤트 전달 백로그·가장 오래된 미전달 이벤트의 경과 시간·완료 처리·제한된 결과 메트릭
- 헥사고날 구조의 Gradle 6개 모듈 구성
- 도메인, 애플리케이션, HTTP, 아웃바운드 정책, PostgreSQL 통합 테스트와 최종 WATCH 이미지 런타임, Gradle 의존성 체크섬, 변경 의존성 검토, CodeQL, ShellCheck를 실패 폐쇄 방식으로 검증하는 GitHub Actions 검사

운영자가 콜백과 별도의 서비스 토큰을 제공하기 전까지 전달은 비활성화됩니다. 전달이 비활성화되어 있거나 콜백을 사용할 수 없는 동안에도 대기 이벤트는 내구성 있게 유지됩니다. WATCH에는 프런트엔드나 브로커가 없으며, 저장소 산출물은 운영 배포나 외부 알림이 존재한다는 증거가 아닙니다.

## 기술과 모듈

- Java 21
- Spring Boot 4.1.0
- Gradle 9.2.1

운영 코드의 의존성은 안쪽을 향합니다.

bootstrap -> adapters -> application -> domain

모듈은 `domain`, `application`, `adapter-in-web`, `adapter-out-persistence`, `adapter-out-external`, `bootstrap`입니다.

## 빌드와 실행

저장소에는 공식 SHA-256 체크섬과 함께 9.2.1로 고정된 Gradle Wrapper와 해석된 빌드·테스트 의존성의 SHA-256을 기록한 `gradle/verification-metadata.xml`이 포함되어 있습니다. 의존성을 변경할 때는 `./gradlew --write-verification-metadata sha256 --refresh-dependencies clean test :bootstrap:bootJar --no-daemon --no-build-cache`를 실행하고 새 체크섬과 의존성 변경을 함께 검토해야 합니다. 전체 테스트 작업에는 Docker가 필요하며, PostgreSQL 통합 테스트는 건너뛰는 방식으로 성공할 수 없도록 의도적으로 구성되어 있습니다. `WATCH_PERSISTENCE_QUERY_TIMEOUT`과 `WATCH_PERSISTENCE_TRANSACTION_TIMEOUT`으로 기본 5초인 JDBC 구문·트랜잭션 제한을 재정의할 수 있으며, 둘 다 1~30초의 정수 초 단위 기간이어야 합니다.

HikariCP 풀은 최대 1~32, 최소 유휴 0~32로 제한하고 최소 유휴는 최대 풀 이하여야 합니다. 연결·검증 제한은 각각 250~30000ms이며 검증 제한이 연결 제한보다 작아야 합니다. 유휴 10000~1800000ms와 생존 확인 30000~1800000ms를 허용하며, 가변 풀의 유휴 제한은 최대 수명 30000~3600000ms보다 1000ms 이상 짧고 생존 확인은 최대 수명보다 짧아야 합니다. 초기화 실패 제한은 1~30000ms입니다. pgJDBC의 연결·로그인·취소 제한은 1~30초, 소켓 제한은 1~120초며, `WATCH_DB_TCP_KEEP_ALIVE`는 Spring이 지원하는 불리언 표현을 허용합니다. `SPRING_DATASOURCE_URL`은 `jdbc:postgresql://` 계층형 형식이어야 하며, 검증된 상한을 우회할 수 있는 JDBC URL 쿼리 매개변수는 허용하지 않습니다. 환경 변수 이름과 로컬 기본값은 [.env.example](.env.example)을 참고하세요.

~~~bash
./gradlew clean test :bootstrap:bootJar --no-build-cache
~~~

권장 로컬 컨테이너 실행을 위해 환경 템플릿을 복사하고, 시작 전에 데이터베이스와 모니터 API의 자리표시자 비밀값을 교체합니다. 콜백을 활성화할 때는 별도의 전달 토큰도 교체합니다.

~~~bash
cp .env.example .env
docker compose up --build
curl http://localhost:8080/api/v1/system/status
~~~

`WATCH_API_TOKEN`은 RFC 6750 `token68` 문자 집합을 사용하며 패딩 제외 문자가 32자 이상이고 전체 길이가 200자 이하여야 합니다. 32바이트 16진수 난수 값은 이 조건과 호환됩니다.

로컬 Compose는 WATCH와 비공개 PostgreSQL 18.4 서비스를 시작합니다. 데이터베이스 포트와 WATCH 관리 포트 `8081`은 공개되지 않으며, 관리 포트는 런타임 네트워크 내부에서 Actuator 상태와 Prometheus 메트릭을 제공합니다. 스테이징 산출물은 데이터베이스 소유자와 WATCH 런타임 역할을 분리하고, 같은 Git SHA로 태그한 `baton-watch-database-operations`, `baton-watch-migrations`, `baton-watch` 이미지 세 개를 사용합니다. 일회성 역할 초기화와 Flyway 마이그레이션이 완료된 뒤에만 런타임을 시작합니다. 모니터를 동기화하려면 다음 명령을 사용합니다.

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
- [현재 인계 문서](HANDOFF.md)
