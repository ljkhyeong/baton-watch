# BATON WATCH

BATON WATCH는 BATON에 등록된 자료 URL의 연결 상태를 비동기로 점검하는 독립 Java/Spring 서비스입니다.

## 현재 구현

| 기능 | 동작 |
| --- | --- |
| 서비스 상태 | `GET /api/v1/system/status` |
| 점검 대상 등록·조회 | 인증된 `PUT`·`GET /api/v1/resource-monitors/{resourceReference}`. `ACTIVE`·`INACTIVE`를 동기화하고 이전 리비전의 덮어쓰기를 거부 |
| 여러 대상 조회 | 인증된 `GET /api/v1/resource-monitors?resourceReference=...`. 최대 20개를 한 번에 조회하고 미등록 대상은 별도 반환 |
| 재점검 요청 | 인증된 `POST /api/v1/resource-monitors/{resourceReference}/check-requests`. 기존 점검을 사용하거나 새 일정을 예약 |
| URL 점검 | GET 응답 헤더로 연결 상태 판단. 응답 본문은 읽거나 저장하지 않음 |
| 점검 기록 | 일정, 시도·결과, 현재 상태와 상태 변경 이벤트를 PostgreSQL에 저장. 저장한 시도·결과는 변경하지 않음 |
| 상태 변경 전달 | 설정된 BATON HTTPS 콜백 하나로 비동기 전달. 같은 이벤트를 다시 보낼 수 있으며 BATON은 이벤트 ID로 중복 처리 방지 |
| 점검 중지 | 시작 설정으로 해당 인스턴스의 점검 예약 중지. API·이력 정리·이벤트 전달은 유지 |
| 장애 진단·복구 | 점검 진행 상태·최근 점검·전달 이력의 읽기 전용 조회, DB 백업·격리 복원, BATON 스냅샷 대조·재전송 |

모니터 등록·조회 응답의 `checkStatus`로 예약·대기·진행 중·비활성을 구분할 수 있습니다.
링크의 연결 상태는 기존 `health`로 확인합니다. 각 값의 의미는 [API 계약](docs/PRD/0002_api-contract/spec.md)을 따릅니다.
상태 조회 응답은 마지막 점검을 시도한 시각과 상태 판단에 사용한 마지막 결과의 시각을 구분합니다.
점검과 전달은 각각 전용 스레드에서 처리하며, 외부 요청 직전에 한 건씩 점유합니다.
리스는 만료 시간이 정해진 작업 점유권입니다. 일정·리스·전달 상태는 DB에 저장해 재시작 후에도 유지합니다.

### 요청·실행 제한

| 항목 | 제한·처리 |
| --- | --- |
| 모니터 `PUT` 본문 | 인증 후 JSON 16 KiB 제한. 초과 시 `413 PAYLOAD_TOO_LARGE` |
| 수동 재점검 | 리소스별 새 예약 간격 30초. 이미 대기·실행 중이면 기존 점검 사용 |
| Tomcat 헤더 | 요청·응답 각각 8 KiB |
| Tomcat 연결 | 최대 128개, 수락 대기 최대 32개 |
| Tomcat 스레드·큐 | 워커 최대 32개·최소 유휴 4개, 대기열 최대 64개 |
| 배치 | 허용 실행 시간 60초. 시작 시 JDBC·트랜잭션·행 잠금·HTTP 제한을 합산해 검증 |
| 아웃바운드 요청 | DNS 해석 결과 검증·고정, SSRF 방어, 시간·헤더 제한, 리다이렉트 정책 적용 |
| 요청 취소 | 이미 중단된 호출은 DNS·HTTP 작업 제출 전 거부. 실행 중에는 전체 시간 초과·호출자 중단·실행기 종료 시 HTTP 요청 취소 |
| 결과 보존 | 유효 기간이 지난 점검 상태를 `UNKNOWN`으로 변경. 시도·결과는 보존 기간 제한, 이벤트는 전달 완료된 건만 삭제 |

Tomcat 제한은 Spring Boot 환경 후처리기가 외부 설정보다 우선해 적용합니다.
모니터 API는 일시적 DB 장애에 `503 SERVICE_UNAVAILABLE`과 `Retry-After: 5`를 반환합니다.
호출자는 최소 5초 뒤 재시도하며, 이 값이 복구 완료를 보장하지는 않습니다.
점검기의 자원 설정에도 상한을 적용합니다. 대상 점검은 리다이렉트마다 주소를 다시 검증하고,
이벤트 전달은 리다이렉트를 따라가지 않습니다. 이벤트 본문은 변경하지 않으며,
재시도 간격은 지수 백오프로 늘리되 설정된 상한을 넘지 않습니다.
콜백의 429·503 응답에 유효한 `Retry-After`가 있으면 그 시각까지 재시도를 늦춥니다.
기존 백오프보다 일찍 재시도하거나 최대 재시도 지연을 넘기지는 않습니다.

### 운영 도구와 자동 검증

| 구분 | 제공 내용 |
| --- | --- |
| 점검·전달 메트릭 | 결과별 시도 수·소요 시간, 실행 중 작업 수, 점유·완료·만료 리스 회수 횟수. 레이블 값의 종류 제한 |
| 지연·적체 메트릭 | 실행 가능한 가장 오래된 점검의 지연, JVM·DB 시계 편차, Spring 예약 실행 시간, 미전달 이벤트 수·최대 대기 시간 |
| 상태 감시 | Blackbox Exporter로 내부 프록시·공개 HTTPS 응답 확인. 공개 TLS 만료 7일 전 경보를 포함한 Prometheus 설정과 Grafana 대시보드 JSON 제공 |
| 운영 알림 연결 | Alertmanager의 Telegram·Slack 연결 설정. 같은 경보 묶기·복구 알림, 내부 주소와 자료 참조를 제외한 공통 한글 문구 제공 |
| 개발 알림 연결 | [GitHub 공식 Slack 앱](docs/runbooks/github-slack.md)의 검증 결과·PR·리뷰·IANA 검사 구독 안내 |
| 외부 상태 점검 연결 | Grafana Cloud 공개 HTTP 점검과 15분간 결과 누락 경보 예시. 홈서버 중단·공개 TLS 만료·점검 중단 알림 연결 절차 제공 |
| 외부 요청 처리 | 스테이징 NGINX 경로별 요청 속도 제한, 비공개 네트워크 분리 |
| Compose 상태 확인 | Spring Boot 기본 liveness와 DB를 포함한 readiness 사용 |
| 주소 정책 변경 확인 | IANA 주소 레지스트리 체크섬을 매월 비교. 변경 시 허용 정책·경계 테스트 수동 검토 |
| 실행·복구 테스트 | 도메인·애플리케이션·HTTP·아웃바운드 정책·PostgreSQL 통합, 부하·강제 종료·리스 만료·동일 이벤트 재전달, Spring Boot 전체 런타임 복구 검증 |
| 운영 도구 테스트 | 작업자 미실행 경보·PromQL·`promtool`, `0600` DB 백업·임시 PostgreSQL 복원, URL·비밀값을 제외한 진단 CLI 검증 |
| GitHub Actions | 의존성 체크섬·라이선스, CodeQL·ShellCheck, JAR·이미지 CycloneDX SBOM, 심각도별 취약점 검사. 검사 실패·미실행 시 CI 실패 처리 |

이미지 검사는 자체 배포 이미지 다섯 개와 공식 NGINX 이미지를 포함합니다. 의존성 변경은
허용 라이선스도 검토합니다. 자세한 실행 명령과 범위는 아래 빌드·검증 절차를 따릅니다.

### 운영에 필요한 별도 작업

콜백과 전용 서비스 토큰을 설정하기 전에는 이벤트를 전송하지 않습니다. 전달이 꺼져 있거나
콜백에 연결할 수 없어도 미전달 이벤트는 DB에 보관합니다.
WATCH에는 프런트엔드와 메시지 브로커가 없습니다. 외부 계정과 수집기·대시보드·알림 서버는
별도로 연결해야 합니다. Cloudflare 유료 요청 속도 제한과 R2는 포함하지 않습니다.

기존 Prometheus로 외부 메트릭을 보내려면 [Grafana Cloud Free 연결 설정](docs/runbooks/grafana-cloud-free.md)을
사용할 수 있습니다. WATCH 지표만 60초마다 보내는 예시이며 실제 계정·수집기 연결은 별도로 설정해야 합니다.
운영 알림은 [무료 API 검토와 Telegram·Slack 연결](docs/runbooks/external-api-options.md)의 설정을 사용할 수 있습니다.
현재는 설정·문구만 검증했으며 실제 채널 인증과 알림 수신은 확인하지 않았습니다.

저장소의 코드와 설정만으로 실제 배포·외부 알림 연결을 확인할 수는 없습니다.
공개 배포 전에는 이그레스 정책, 지원 규모·SLO, NGINX 제한값, 공개 HTTPS 429 검증과
실제 대시보드·알림 구성을 승인받아야 합니다.

## 기술과 모듈

- Java 21
- Spring Boot 4.1.1
- Gradle 9.7.1
- PostgreSQL 18.6
- Flyway 13.4.0 마이그레이션 이미지

코드 의존성은 다음 순서를 따릅니다.

bootstrap -> adapters -> application -> domain

모듈은 `domain`, `application`, `adapter-in-web`, `adapter-out-persistence`, `adapter-out-external`, `bootstrap`입니다.

## 빌드와 실행

개발 중에는 [변경 범위별 검증 절차](docs/runbooks/development-validation.md)에 따라 필요한 검사부터 실행합니다.
`python3 ops/run-validation.py status`로 이전 결과를 확인하고, 같은 도구의 `run` 명령으로 로그와 종료 코드를 보관할 수 있습니다.
파일 작성 직후에는 `ops/check-feedback.py file`, 완료 전에는 작업 시작 커밋을 지정한 `finish --base`로 검사합니다.
Java·빌드 변경은 ArchUnit으로 계층 방향과 어댑터 간 직접 의존을 확인하며, 구조 검사는 기존 테스트·CI에서도 실행됩니다.

### 빌드·검증 조건

- Gradle Wrapper는 9.7.1로 고정하고 공식 SHA-256 체크섬으로 검증합니다.
- 빌드·테스트 의존성의 SHA-256은 `gradle/verification-metadata.xml`에 기록합니다.
- 전체 테스트에는 Docker와 Docker Compose가 필요합니다. PostgreSQL 통합 테스트를 건너뛰면 검증에 실패합니다.

의존성을 변경하면 다음 명령을 실행하고, 새 체크섬과 의존성 변경을 함께 검토하세요.

```bash
./gradlew --write-verification-metadata sha256 --refresh-dependencies clean test :bootstrap:verifyBootJarLicense --no-daemon --no-build-cache
```

### DB 연결·실행 제한

아래 기본값은 [.env.example](.env.example) 기준입니다.

| 설정 | 환경 변수 | 기본값 | 허용 범위·조건 |
| --- | --- | --- | --- |
| JDBC 구문 제한 | `WATCH_PERSISTENCE_QUERY_TIMEOUT` | 5s | 1~30초, 정수 초 단위 |
| JDBC 트랜잭션 제한 | `WATCH_PERSISTENCE_TRANSACTION_TIMEOUT` | 5s | 1~30초, 정수 초 단위 |
| HikariCP 최대 연결 수 | `WATCH_DB_MAXIMUM_POOL_SIZE` | 8 | 1~32 |
| 최소 유휴 연결 수 | `WATCH_DB_MINIMUM_IDLE` | 2 | 0~32, 최대 연결 수 이하 |
| 연결 대기 제한 | `WATCH_DB_CONNECTION_TIMEOUT_MILLIS` | 3000ms | 250~30000ms |
| 연결 검증 제한 | `WATCH_DB_VALIDATION_TIMEOUT_MILLIS` | 1000ms | 250~30000ms, 연결 대기 제한보다 짧게 |
| 유휴 연결 유지 시간 | `WATCH_DB_IDLE_TIMEOUT_MILLIS` | 600000ms | 10000~1800000ms. 가변 풀에서는 최대 수명보다 1000ms 이상 짧게 |
| 연결 최대 수명 | `WATCH_DB_MAX_LIFETIME_MILLIS` | 1800000ms | 30000~3600000ms |
| 연결 생존 확인 주기 | `WATCH_DB_KEEPALIVE_TIME_MILLIS` | 120000ms | 30000~1800000ms, 최대 수명보다 짧게 |
| 초기화 실패 제한 | `WATCH_DB_INITIALIZATION_FAIL_TIMEOUT_MILLIS` | 5000ms | 1~30000ms |
| pgJDBC 연결 제한 | `WATCH_DB_CONNECT_TIMEOUT_SECONDS` | 3초 | 1~30초 |
| pgJDBC 로그인 제한 | `WATCH_DB_LOGIN_TIMEOUT_SECONDS` | 5초 | 1~30초 |
| pgJDBC 취소 요청 제한 | `WATCH_DB_CANCEL_SIGNAL_TIMEOUT_SECONDS` | 3초 | 1~30초 |
| pgJDBC 소켓 제한 | `WATCH_DB_SOCKET_TIMEOUT_SECONDS` | 10초 | 1~120초 |

`WATCH_DB_TCP_KEEP_ALIVE`는 Spring이 지원하는 불리언 표현을 허용합니다.
`SPRING_DATASOURCE_URL`은 `jdbc:postgresql://` 계층형 형식이어야 합니다.
검증된 상한을 우회할 수 있는 JDBC URL 쿼리 매개변수는 허용하지 않습니다.

### 점검·전달 주기

점검·전달 폴링 주기는 최소 1초, 유지보수 주기는 최소 1분입니다. 일반 점검 간격은 최소 1분, 내부 실패 재시도는 최소 30초이며, 이벤트 전달의 최초·최대 재시도 지연은 각각 최소 5초입니다. 이보다 짧은 값은 시작 시 Spring 설정 속성 검증에서 거부됩니다.

`WATCH_CHECK_ENABLED`는 기본 `true`입니다. `false`로 바꾸고 프로세스를 다시
시작하면 그 인스턴스의 점검 예약만 중지됩니다. Compose에서는 환경 파일 변경 후
`restart`가 아니라 컨테이너 재생성이 필요합니다. API, 오래된 상태 처리, 이력 정리,
시계 편차 측정과 별도로 활성화한 이벤트 전달은 계속 동작합니다. 점검이 꺼져 있어도
설정 검증은 유지됩니다. 다중 인스턴스의 중지·재개와 경보 설정은
[점검 제어·진단 절차](docs/runbooks/check-control-and-diagnostics.md)를 따릅니다.
설정 바인딩과 예약 조건은 같은 Spring 불리언 변환을 사용합니다. `on`·`yes`·`1`과
앞뒤 공백도 해석하지만, 운영 설정에는 혼동을 줄이기 위해 `true`·`false`를 사용하세요.

점검과 전달은 외부 호출 직전에 한 건씩 점유합니다. 시작 시 리스 유효 기간이 작업 점유·외부 호출·결과 저장의
최대 소요 시간보다 긴지 확인합니다. 배치를 순서대로 처리하는 데 걸리는 시간도
`WATCH_WORKER_EXECUTION_BUDGET` 안에 들어야 합니다.

배치 허용 실행 시간은 기본·최대 60초입니다. 기본 배치 크기는 점검 1건, 전달 2건이며,
기본 DB·HTTP 제한으로 계산한 최대 소요 시간은 각각 21초와 42초입니다.

~~~bash
./gradlew test :bootstrap:verifyBootJarLicense
~~~

일반 테스트는 실제 DB 백업·격리 복원과 읽기 전용 진단 CLI도 확인합니다. 별도 부하·복구 시험과 경보
규칙 검사는 다음 명령으로 실행합니다. 부하 시험은 기본 100개 모니터를 사용하며
운영 처리량이나 SLO를 인증하지 않습니다. 경보 검사는 수집기나 외부 알림을
활성화하지 않습니다.

~~~bash
./gradlew :adapter-out-persistence:loadTest --no-daemon
./gradlew :bootstrap:runtimeLoadTest --no-daemon
./gradlew :bootstrap:capacityTest --no-daemon
./gradlew :adapter-out-persistence:processRecoveryTest --no-daemon
./ops/tests/prometheus-rules-test.sh
python3 ops/tests/gateway-test.py
~~~

NGINX 검사는 실제 프록시·Blackbox Exporter와 HTTP 대역을 사용하는 임시 Compose
프로젝트에서 실행합니다. 프록시 상태 확인과 실제 요청의 성공·실패·복구를 구분해 검사합니다.
Prometheus 검사는 경보와 대시보드의 실제 쿼리를 평가하며 요청 실패·수집 실패·지표
누락이 겹쳐도 요청 실패 경보가 유지되는지 확인합니다. Grafana 서버를
배포하지 않습니다. 관리 상태 확인은 컨테이너 루프백의
`/actuator/health/liveness`와 `/actuator/health/readiness`를 사용합니다.
readiness에는 DB를 포함하고 liveness에는 포함하지 않습니다. Docker unhealthy만으로
자동 재시작이나 실행 중 트래픽 차단이 보장되지는 않습니다.

프로세스 복구 시험은 점검 30초·전달 60초 리스의 자연 만료를 실제로 기다립니다.
시험용 자식 JVM·콜백 대역을 사용하므로 운영 부트 JAR 재시작이나 공개 HTTPS 전달을
검증한 것으로 해석하지 않습니다. 경보 규칙은 기본 예약 주기를 기준으로 하며,
주기를 바꾸면 [조회 구간과 시작 유예](docs/runbooks/monitoring-alerts.md)도 검토해야 합니다.

전체 Gradle 검증은 실행 가능한 부트 JAR의 `META-INF/LICENSE`가 저장소 `LICENSE`와
정확히 일치하는지도 확인합니다. Dockerfile의 PostgreSQL·데이터베이스 작업·마이그레이션·cloudflared·WATCH
이미지는 같은 라이선스를 `/usr/share/licenses/baton-watch/LICENSE`에 포함하고
`Apache-2.0` OCI 라이선스 레이블을 사용합니다. `검증` 워크플로는 [이미지 검증 도구](ops/verify-runtime-images.py)로
레이블과 라이선스를 확인합니다. Trivy는 독립 부트 JAR, 자체 이미지 다섯 개와
Compose에 고정된 NGINX 이미지의 CycloneDX SBOM 일곱 개를
생성하고 수정 가능한 `HIGH`·`CRITICAL` 취약점이 있으면 실패합니다. 공식 이미지의
원래 라이선스는 유지됩니다. PostgreSQL의 OS 패키지와 cloudflared의 Go 의존성은
수정 버전으로 빌드합니다. [cloudflared 빌드 입력](ops/cloudflared/README.md)은 공식 소스와 변경한 의존성을 기록합니다.
CI와 배포 절차는 [공용 공급망 검사](ops/scan-supply-chain.sh)를 사용하므로 배포
호스트에서 다시 빌드한 플랫폼별 이미지도 검증용으로 보관한 이미지 아카이브를 동일한
기준으로 검사합니다. 한 산출물에서 취약점이 발견되어도 나머지 취약점·라이선스
검사를 끝까지 수행한 뒤 전체 결과를 실패로 처리합니다.
부트 JAR SBOM은 Trivy 표준 라이선스 스캐너와 명시적 허용 목록도 통과해야 합니다.
허용 라이선스가 없는 의존성은 차단하며, 라이선스 정보 누락·이중 라이선스 예외는
승인된 패키지와 버전에만 적용합니다. CI와 같은 정책 회귀 검사는
`python3 ops/tests/runtime-license-policy-test.py`로 실행합니다.
`main` 푸시의 전체 검증이 성공하면 JAR·SBOM 일곱 개·체크섬 공급망 산출물에 GitHub
출처 증명을 추가합니다. 이 산출물은 14일 동안 보관하며 컨테이너 이미지 자체는
현재 출처 증명 대상이 아닙니다.

공급망 검사의 차단 사유와 재검사 조건은 [HANDOFF.md](HANDOFF.md)에서 관리합니다.
보고서의 이미지·플랫폼·검사 시점을 확인하며, 과거 검사 결과만으로 현재 배포 준비가
완료됐다고 판단하지 않습니다. 날짜별 결과는 [이전 인계 기록](docs/history/handoff-2026-09-05.md)에 보관합니다.

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

로컬 Compose는 WATCH와 PostgreSQL 18.6을 시작합니다. DB 포트와 WATCH 관리 포트 `8081`은
외부에 공개하지 않습니다. 관리 서버는 모든 실행 방식에서 `127.0.0.1`에 고정되며,
컨테이너 내부에 Actuator 상태와 Prometheus 메트릭을 제공합니다. 외부 서비스에 메트릭을 직접 보내지는 않습니다.

[이벤트 전달용 추가 Compose 설정](compose.staging-event-delivery.yml)은 BATON 콜백 사전 검사가 끝난 경우에만 전달을 활성화하고 토큰을 `configtree` 비밀 파일로 주입합니다. 이 설정을 추가하지 않으면 기본 스테이징은 이벤트 전달을 수행하지 않습니다.

스테이징에서는 DB 소유자와 WATCH 실행 계정을 분리합니다. 역할 초기화와 Flyway 마이그레이션이
끝난 뒤 WATCH를 시작합니다. 사용하는 이미지 5개는 같은 Git SHA로 태그합니다.

- `baton-watch-postgres`: PostgreSQL
- `baton-watch-database-operations`: DB 계정·권한 관리
- `baton-watch-migrations`: 마이그레이션
- `baton-watch-cloudflared`: Cloudflare Tunnel
- `baton-watch`: 애플리케이션

[배포 절차](docs/runbooks/staging-deployment.md)에 따라 이미지 ID·OCI 리비전·아카이브 SHA-256을 보관합니다.
DB 비밀번호를 SQL이나 명령행에 노출하지 않고 교체·원복하는 절차도 제공합니다.

점검 대상을 동기화하려면 다음 명령을 사용합니다.

~~~bash
curl -X PUT http://localhost:8080/api/v1/resource-monitors/role-resource-123 \
  -H 'Authorization: Bearer replace-with-your-token' \
  -H 'Content-Type: application/json' \
  -d '{"sourceRevision":1,"monitoringState":"ACTIVE","targetUrl":"https://example.com/"}'
~~~

BATON은 자체 트랜잭션이 커밋된 뒤에만 동기화를 호출해야 합니다. 더 높은 리비전으로 `targetUrl` 없이 `INACTIVE`를 보내면 이후 점검을 중단합니다. 기존 이력은 보존 기간 동안 유지합니다.

상태 변경 전달을 활성화하려면 Compose를 시작하기 전에 `.env`에 다음 값을 설정합니다.

~~~dotenv
WATCH_EVENT_DELIVERY_ENABLED=true
WATCH_EVENT_DELIVERY_ENDPOINT=https://baton.example.com/api/v1/internal/resource-health-events
WATCH_EVENT_DELIVERY_TOKEN=replace-with-a-separate-32-character-token
~~~

`WATCH_EVENT_DELIVERY_ENABLED`는 기본 `false`이며, 점검 설정과 같은 Spring 불리언
변환을 사용합니다. `on`·`yes`·`1`과 앞뒤 공백도 해석하지만 운영 설정에는
`true`·`false`를 사용하세요. 값 변경 후에는 프로세스 재시작 또는 컨테이너 재생성이 필요합니다.

콜백 주소는 포트 `443`을 사용하는 절대 HTTPS URL이며, DNS 조회 결과가 모두 공개 IP여야 합니다.
사용자 정보, 쿼리, 프래그먼트나 IP 주소를 직접 쓴 호스트는 허용하지 않습니다.

BATON은 Bearer 토큰을 인증하고 `Idempotency-Key`와 `eventId`가 같은지 확인해야 합니다.
중복 처리를 막기 위해 `2xx` 응답 전에 처리한 `eventId`를 DB에 기록합니다.
본문 형식과 재시도 규칙은 [이벤트 전달 계약](docs/PRD/0004_health-change-event-delivery/spec.md)을 참고하세요.
콜백 Bearer 토큰은 모니터 API 토큰과 달라야 하고 `[A-Za-z0-9._~-]` 문자 32~200개로 구성합니다.

## 라이선스

이 프로젝트는 [Apache License 2.0](LICENSE) 조건으로 사용할 수 있습니다.
라이선스 전문은 소스 저장소뿐 아니라 실행 가능한 부트 JAR과 자체 배포 이미지 5개에도
포함됩니다. 보안 문제는 공개 이슈 대신 [보안 정책](SECURITY.md)의 비공개 제보
절차를 사용하세요.

## 관련 문서

- [제품 범위](docs/PRD/0001_product-baseline/spec.md)
- [API 계약](docs/PRD/0002_api-contract/spec.md)
- [모니터링 MVP](docs/PRD/0003_monitoring-mvp/spec.md)
- [상태 변경 이벤트 전달](docs/PRD/0004_health-change-event-delivery/spec.md)
- [마이크로서비스 경계 ADR](docs/ADR/0001_microservice-boundary/adr.md)
- [MVP 저장소 및 실행 ADR](docs/ADR/0002_monitoring-mvp-storage-and-execution/adr.md)
- [직접 HTTPS 이벤트 전달 ADR](docs/ADR/0003_health-change-event-delivery/adr.md)
- [NGINX 요청 속도 제한 ADR](docs/ADR/0004_ingress-rate-limit/adr.md)
- [Cloudflare Tunnel 스테이징 배포 런북](docs/runbooks/staging-deployment.md) — 배포 파일 제공과 실제 가동·인증·외부 접속 검증은 구분합니다.
- [공개 스테이징 전달 검증 런북](docs/runbooks/public-staging-event-delivery.md)
- [격리 DB 부하·장애 복구 시험](docs/runbooks/load-recovery-test.md)
- [운영 기본 설정의 용량 참고 시험](docs/runbooks/capacity-test.md)
- [BATON 연동 확인과 복구 조건](docs/runbooks/baton-integration-review.md)
- [BATON 자료 상태·재점검의 공개 HTTPS 검증](docs/runbooks/baton-resource-health-verification.md)
- [전체 Spring 런타임 부하·복구 시험](docs/runbooks/runtime-load-test.md)
- [별도 JVM 중단·재시작 복구 시험](docs/runbooks/process-recovery-test.md)
- [기존 메트릭 경보 규칙과 적용 조건](docs/runbooks/monitoring-alerts.md)
- [NGINX 요청 속도 제한과 공개 검증](docs/runbooks/request-rate-limit.md)
- [인바운드 HTTP 점검·경보 적용 조건](docs/runbooks/ingress-monitoring.md)
- [점검 중지·재개와 읽기 전용 장애 진단](docs/runbooks/check-control-and-diagnostics.md)
- [보안 정책](SECURITY.md)
- [현재 인계 문서](HANDOFF.md)

## 복구 후 재점검

기존 모니터 API 토큰으로 `POST /api/v1/resource-monitors/{resourceReference}/check-requests`를
호출하면 기존 활성 모니터의 일정을 앞당길 수 있습니다. 본문 없이 호출하며 202는 접수만
뜻합니다. 이미 도래했거나 실행 중인 점검은 공유하고, 새 수동 예약은 30초 간격을 적용합니다.
비활성 모니터는 409, 없는 모니터는 404, 간격 제한은 429와 `Retry-After`로 응답합니다.
점검 설정이 꺼져 있으면 활성 작업자가 실행될 때까지 대기합니다.
[재점검·진단 절차](docs/runbooks/check-control-and-diagnostics.md)를 참고하세요.

대상 점검은 GET 상태·헤더를 확인하고 본문을 읽지 않은 채 연결을 닫습니다. 큰 본문이나
스트리밍 본문 자체는 실패 사유가 아니며, 본문 전체 다운로드·로그인 뒤 접근은 검증하지
않습니다. 기존 `watch.http.max-response-bytes`는 구성 호환성을 위해 검증하지만 현재
점검의 성패에는 사용하지 않습니다. 진단의 새 `responseBytes: 0`은 본문 미소비를 뜻합니다.
콜백 전달의 본문 제한은 유지합니다.

## BATON 원본 스냅샷으로 독립 복원

WATCH DB만 복원한 뒤에는 [BATON 스냅샷 대조·재전송 절차](docs/runbooks/baton-snapshot-recovery.md)를
사용한다. 기본 실행은 최대 20개씩 묶어 조회하며, 재전송은 각 항목을 다시 확인한 뒤
같은 리비전의 불변 스냅샷으로 처리한다. 더 높은 WATCH
리비전과 같은 리비전의 다른 본문은 충돌로 보고하며 원본 URL·토큰은 결과에 남기지 않는다.
HTTP 401·403을 받으면 후속 요청을 중단하고, 거부된 항목과 아직 요청하지 않은 항목을
구분해 기록한다. API 토큰과 접근 권한을 확인한 뒤 같은 파일로 다시 실행할 수 있다.
