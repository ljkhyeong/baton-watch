---
name: baton-watch-documentation-flows
description: BATON WATCH의 README, HANDOFF, PRD, ADR과 구현 설명을 수정할 때 사용한다. 변경한 문서와 관련 요약만 맞춘다.
---

# BATON WATCH 문서

- README에는 현재 기능·설정·실행 방법, HANDOFF에는 현재 상태·검증 결과·남은 작업을 기록한다. AGENTS에는 작업 규칙만 둔다.
- PRD의 담당 범위는 [제품 범위](../../../docs/prd/0001_product-baseline/spec.md), [인바운드 API](../../../docs/prd/0002_api-contract/spec.md), [점검 실행](../../../docs/prd/0003_monitoring-mvp/spec.md), [이벤트 전달](../../../docs/prd/0004_health-change-event-delivery/spec.md)다. 설계 결정은 관련 ADR에 기록한다.
- 구현 설명은 코드·테스트·설정과 대조한다. 구현 여부, 설정 여부, 실제 가동 여부를 구분한다. 기능 목록은 README·PRD에서 관리하고 스킬에 반복하지 않는다.
- 기준 문서를 먼저 고치고 영향을 받는 요약·링크만 맞춘다. 번역·축약으로 의무·예외·기본값·인증·재시도·보존 조건을 바꾸지 않는다.

변경한 문서의 로컬 링크와 `git diff --check`를 확인한다. 스킬을 바꾸면 frontmatter·`agents/openai.yaml`·참조 경로도 확인한다. 문서만 바뀌면 애플리케이션 테스트는 실행하지 않는다.
