---
name: baton-watch-documentation-flows
description: BATON WATCH의 README, HANDOFF, PRD, ADR, 런북과 에이전트 스킬(`.agents/skills`·`.claude/skills`)을 수정할 때 사용한다. HANDOFF의 현재 작업·최근 검증·차단 상태 형식과 구현 설명의 대조 기준을 다룬다.
---

# BATON WATCH 문서

## 문서별 담당

- README에는 현재 기능·설정·실행 방법, HANDOFF에는 현재 상태·검증 결과·남은 작업을 기록한다. AGENTS에는 작업 규칙만 둔다.
- PRD의 담당 범위는 [제품 범위](../../../docs/PRD/0001_product-baseline/spec.md), [인바운드 API](../../../docs/PRD/0002_api-contract/spec.md), [점검 실행](../../../docs/PRD/0003_monitoring-mvp/spec.md), [이벤트 전달](../../../docs/PRD/0004_health-change-event-delivery/spec.md)다. 설계 결정은 `docs/ADR`의 관련 ADR, 운영 절차는 `docs/runbooks`에 기록한다.
- 구현 설명은 코드·테스트·설정과 대조한다. 구현 여부, 설정 여부, 실제 가동 여부를 구분한다. 기능 목록은 README·PRD에서 관리하고 스킬에 반복하지 않는다.
- 기준 문서를 먼저 고치고 영향을 받는 요약·링크만 맞춘다. 번역·축약으로 의무·예외·기본값·인증·재시도·보존 조건을 바꾸지 않는다.

## HANDOFF 형식

기존 항목의 문체와 길이를 따른다. 새 항목은 각 절의 맨 위에 추가하고 `최종 수정일`을 갱신한다.

- `## 현재 작업`: `` `<수정 커밋>`에서 <문제>를 수정했다. `` 뒤에 해결 방식, 유지한 기존 동작, 반영한 문서 링크를 적는다. 마지막에 추가 의존성·비용과 실제 배포·외부 연동의 실행 여부를 적는다.
- `## 최근 검증` 표: `| <대상> \`<수정 커밋>\` | ... |` 형식이다. 변경 전 재현 → 변경 후 관련 N개·모듈 전체 M개 통과와 실패·건너뜀 여부 → 확인한 동작 → 반복하지 않은 검사와 이유 순으로 쓴다. 대역·합성 입력을 썼으면 밝힌다.
- `## 차단 상태와 다시 확인할 조건`: 마지막 확인 날짜·결과와 재개 조건을 적는다. 조건이 그대로면 같은 외부 작업을 반복하지 않는다는 기존 문장을 유지한다.
- 수정 커밋 해시를 기록해야 하므로 HANDOFF는 코드 커밋 뒤의 별도 `docs:` 커밋에서 갱신한다.
- 테스트 개수는 추정하지 않고 JUnit 결과에서 집계한다. 방법은 [검증](../baton-watch-validation/SKILL.md) 스킬을 따른다.
- 절이 길어져 현재 판단에 필요 없는 기록이 쌓이면 `docs/history/handoff-<날짜>.md`로 옮기고 링크만 남긴다.

## 에이전트 스킬

- Codex는 `.agents/skills/<이름>/`(`SKILL.md`와 `agents/openai.yaml`), Claude Code는 `.claude/skills/<이름>/SKILL.md`를 사용한다. 같은 이름의 스킬은 공통 규칙을 함께 맞춘다.
- Claude 스킬은 frontmatter `description`으로 자동 호출이 결정된다. 대상 클래스·경로·작업 종류를 구체적으로 적는다.
- `baton-watch-outbound-http`, `baton-watch-defect-fix`, `baton-watch-validation`, `baton-watch-review`는 Claude 전용이다. Codex에서는 `baton-watch-ops`와 AGENTS의 검증 절차가 해당 범위를 담당한다.
- 저장소 경로는 `docs/PRD`·`docs/ADR`처럼 대문자다. 파일 검사는 macOS에서도 경로 대소문자를 구분하므로 소문자 링크를 거부한다.

## 검증

```bash
python3 ops/check-feedback.py file <변경한 문서...>
git diff --check
```

로컬 링크만 검사하며 제목 앵커·외부 링크는 직접 확인한다. 스킬을 바꾸면 frontmatter·`agents/openai.yaml`·참조 경로도 확인한다. 문서만 바뀌면 애플리케이션 테스트는 실행하지 않는다.
