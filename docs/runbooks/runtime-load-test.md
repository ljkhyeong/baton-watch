# 전체 Spring 런타임 부하·복구 시험

이 시험은 실제 Spring Boot 애플리케이션을 임의 HTTP 포트와 관리 포트로 시작하고,
Testcontainers PostgreSQL에 Flyway 마이그레이션을 적용한다. 인증 필터, 요청 본문
처리, 컨트롤러, 트랜잭션, HikariCP, JDBC 어댑터, 점검·전달 예약 작업과
Prometheus 노출을 한 흐름에서 확인한다.

대상 URL 점검과 BATON 콜백 전송만 Spring 테스트 더블로 교체한다. 외부 DNS·소켓·
TLS·공개 콜백을 사용하지 않으므로 인터넷 상태나 유료 서비스에 의존하지 않는다.
운영 코드의 HTTP 클라이언트 안전 정책은 별도의 어댑터 시험에서 계속 검증한다.

## 실행

Java 21, 실행 중인 Docker와 로컬 임의 포트 사용 권한이 필요하다.

```bash
./gradlew :bootstrap:runtimeLoadTest --no-daemon
./gradlew :bootstrap:runtimeLoadTest -PwatchRuntimeLoadMonitors=50 --no-daemon
```

모니터 수는 기본 25개이고 허용 범위는 1~50개다. 일반 `test`에서는
`runtime-load` 태그를 제외하며, CI는 25개로 별도 실행하고 HTML·XML 결과를 7일
보관한다. 결과는 다음 경로에서 확인한다.

```text
bootstrap/build/reports/tests/runtimeLoadTest/index.html
bootstrap/build/test-results/runtimeLoadTest/
```

## 확인하는 흐름

1. HikariCP 최대 연결 수를 2로 제한하고 두 연결을 모두 점유한다. 인증된 모니터
   동기화가 일반화된 `500 INTERNAL_ERROR`로 실패하며 JDBC URL·비밀번호·풀 내부
   오류 문구를 응답에 노출하지 않는지 확인한다.
2. 연결을 반환한 뒤 같은 요청이 성공하는지 확인한다.
3. 인증된 활성 모니터 동기화 요청을 JDK `HttpClient.sendAsync`로 동시에 보낸다.
4. 점검 완료를 잠시 보류하고 시험 DB의 다음 점검 시각만 2분 전으로 조정한다.
   독립 유지보수 경로가 실제 `baton_watch_check_schedule_delay_seconds`에 지연을
   기록하는지 확인한다.
5. 점검을 재개해 실제 예약 작업이 모든 결과와 상태 변경 이벤트를 확정하는지
   확인한다.
6. 콜백 테스트 더블이 `503`을 반환하는 동안 이벤트가 `PENDING`으로 보존되고
   실제 백로그 지표가 전체 건수를 나타내는지 확인한다.
7. 콜백 테스트 더블을 `204`로 복구해 모든 이벤트가 전달되고 백로그와 일정
   지연이 0으로 돌아오는지 확인한다.
8. 관리 포트의 실제 Prometheus 응답에서 점검·전달 결과, 예약 작업 성공,
   HikariCP 최대 연결 수를 확인한다.

점검 테스트 더블은 항목당 100ms를 사용한다. 리스·재시도·폴링 설정은 운영에서
허용하는 최소 경계 안에서 시험 전용 속성으로 줄이며, 운영 기본값이나 스키마는
변경하지 않는다.

## 결과 해석

표준 출력에는 풀 복구, 동시 동기화, 점검 완료, 전달 복구 구간의 경과 시간과 최종
백로그가 표시된다. 이 값은 동일 코드와 실행 환경에서 회귀를 찾는 참고값이며
지원 모니터 수, 처리량 또는 SLO를 인증하지 않는다.

이 시험은 실제 외부 DNS 해석, TCP 연결, TLS 검증, 응답 크기 제한, 리다이렉트 정책,
BATON 공개 콜백, Cloudflare Tunnel, NGINX, 외부 Prometheus·Grafana·알림 경로를
검증하지 않는다. 실제 네트워크 경계는 승인된 스테이징에서 별도로 검증해야 한다.
DB 잠금 실패와 리스 만료 복구는 [격리 DB 부하·장애 복구 시험](load-recovery-test.md),
별도 JVM 강제 종료는 [프로세스 복구 시험](process-recovery-test.md)을 함께 사용한다.
