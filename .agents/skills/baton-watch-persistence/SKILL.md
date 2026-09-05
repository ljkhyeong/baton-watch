---
name: baton-watch-persistence
description: BATON WATCH의 SQL, Flyway 마이그레이션, 리스 경합, 결과·이벤트 저장과 보존 처리를 변경할 때 사용한다.
---

# BATON WATCH 영속성

점검 저장은 [PRD-0003](../../../docs/prd/0003_monitoring-mvp/spec.md)·[ADR-0002](../../../docs/adr/0002_monitoring-mvp-storage-and-execution/adr.md), 전달 저장은 [PRD-0004](../../../docs/prd/0004_health-change-event-delivery/spec.md)·[ADR-0003](../../../docs/adr/0003_health-change-event-delivery/adr.md)의 관련 부분을 확인한다.

- 적용된 마이그레이션은 수정하지 않고 새 파일을 추가한다. SQL과 매핑의 null 허용 여부·인덱스·고유 제약을 맞춘다.
- 시도·결과 이력은 변경하지 않는다. 현재 상태와 이벤트의 불변 페이로드, 가변 전달 상태·리스·시도 횟수를 구분한다.
- 상태 변경과 이벤트 삽입은 같은 트랜잭션에서 처리하고, 상태가 같으면 이벤트를 만들지 않는다.
- 점검 완료는 현재 모니터의 리스·원본 리비전과 대조해 오래된 결과의 덮어쓰기를 막는다. 이벤트 전달 완료는 해당 이벤트의 전달 리스로 확인한다. `SKIP LOCKED`와 원자적 SQL의 동시성 조건을 보존한다.
- 이력 정리는 제한된 건수로 나눈다. 이벤트는 보존 기준 시각보다 오래된 전달 완료 행만 삭제하며, 시도 횟수나 리스 만료를 이유로 미전달 이벤트를 버리지 않는다.

`./gradlew :adapter-out-persistence:test`를 실행한다. 변경한 경합·중복 완료·리스 복구·재시도·보존 시간 경계를 실제 PostgreSQL로 검증한다. DB 테스트를 건너뛰어 성공 처리하지 않는다.
