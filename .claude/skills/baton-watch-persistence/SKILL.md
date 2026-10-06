---
name: baton-watch-persistence
description: BATON WATCH의 SQL, Flyway 마이그레이션(`db/migration/V*__*.sql`), 런타임 DB 권한, 점검·전달 리스 경합, 결과·이벤트 저장과 보존 정리, 진단·백업 SQL을 바꿀 때 사용한다. `Jdbc*Adapter`, `PostgresTransactionOperations`, 실제 PostgreSQL 통합 테스트가 대상이다.
---

# BATON WATCH 영속성

점검 저장은 [PRD-0003](../../../docs/PRD/0003_monitoring-mvp/spec.md)·[ADR-0002](../../../docs/ADR/0002_monitoring-mvp-storage-and-execution/adr.md),
전달 저장은 [PRD-0004](../../../docs/PRD/0004_health-change-event-delivery/spec.md)·[ADR-0003](../../../docs/ADR/0003_health-change-event-delivery/adr.md)의 관련 부분을 확인한다.

## 위치

| 역할 | 위치 |
| --- | --- |
| 마이그레이션 | `adapter-out-persistence/src/main/resources/db/migration/V<n>__<설명>.sql` |
| 런타임 역할 권한 | `ops/flyway/afterMigrate__runtime_privileges.sql` |
| 저장소 구현 | `adapter-out-persistence/.../persistence/monitoring/Jdbc*`, `MonitoringJdbcRows`, `PostgresTransactionOperations` |
| 출력 포트 | `application/.../monitoring/port/out/*PersistencePort` |
| 진단·백업·복원 | `ops/monitor-diagnostics.sql`, `ops/staging-database-backup.sh`, `ops/verify-restored-database.sql`, `ops/compose.restore-test.yml` |
| 통합 테스트 | `adapter-out-persistence` 테스트. `PostgresPersistenceIntegrationTestSupport`의 싱글턴 Testcontainers PostgreSQL을 JVM당 한 번 띄운다. 이미지는 Gradle 테스트 태스크가 Dockerfile `postgres` 단계에서 넘긴다 |

## 규칙

- 적용된 마이그레이션은 수정하지 않고 다음 번호로 새 파일을 추가한다. SQL과 행 매핑의 null 허용 여부·인덱스·고유 제약을 맞춘다.
- 테이블·열을 추가하면 런타임 역할에 필요한 최소 권한만 `afterMigrate__runtime_privileges.sql`에 부여한다. 열 단위 `UPDATE` 권한을 넓히지 않는다.
- 시도·결과 이력은 변경하지 않는다. 현재 상태, 이벤트의 불변 페이로드, 가변 전달 상태·리스·시도 횟수를 구분한다.
- 상태 변경과 이벤트 삽입은 같은 트랜잭션에서 처리한다. 상태가 같으면 이벤트를 만들지 않는다.
- 점유는 호출 직전 한 건씩 짧은 트랜잭션에서 하고, DNS·HTTP는 트랜잭션 밖에서 수행한 뒤 별도 짧은 트랜잭션에서 완료한다.
- 점검 완료는 현재 모니터의 리스·원본 리비전과 대조해 오래된 결과의 덮어쓰기를 막는다. 이벤트 전달 완료는 해당 이벤트의 전달 리스로 확인한다. `SKIP LOCKED`와 원자적 SQL의 동시성 조건을 보존한다.
- 시간 판단은 주입한 `Clock`과 UTC를 사용한다. DB 시각과 비교가 필요하면 기존 `DatabaseClockPort`를 사용한다.
- 이력 정리는 제한된 건수로 나눈다. 이벤트는 보존 기준 시각보다 오래된 전달 완료 행만 삭제한다. 시도 횟수나 리스 만료를 이유로 미전달 이벤트를 버리지 않는다.
- 테스트가 별도 프로세스에서 새 운영 파일(SQL·셸·Compose)을 읽으면 `adapter-out-persistence/build.gradle`의 `test` 입력 목록에 추가한다. 빠뜨리면 해당 파일만 바뀌었을 때 `UP-TO-DATE`로 검사가 생략된다.

## 검증

```bash
python3 ops/run-validation.py run --label <주제>-persistence -- ./gradlew :adapter-out-persistence:test
```

- 변경한 경합·중복 완료·리스 복구·재시도·보존 시간 경계를 실제 PostgreSQL로 검증한다. 시간 경계는 고정 `Clock`으로 확인한다.
- Docker가 없어 Testcontainers가 실패하면 환경 차단으로 보고한다. DB 테스트를 건너뛰어 성공 처리하지 않는다.
- 리스·종료·동시성 동작을 바꾸면 `./gradlew :adapter-out-persistence:processRecoveryTest`, 처리량에 영향이 있으면 `:bootstrap:runtimeLoadTest -PwatchRuntimeLoadMonitors=25`를 추가한다.
- 진단 SQL을 바꾸면 읽기 전용 여부와 잠금 시간 제한을 [진단 절차](../../../docs/runbooks/check-control-and-diagnostics.md)와 대조한다.
