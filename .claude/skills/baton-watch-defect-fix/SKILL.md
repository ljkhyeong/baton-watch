---
name: baton-watch-defect-fix
description: BATON WATCH의 버그·보안 결함·검증 누락·회귀를 재현 테스트부터 시작해 최소 수정, 검증, PRD·README·HANDOFF 반영, `fix:`·`docs:` 두 커밋과 푸시까지 처리하는 절차. "이 문제 고쳐줘", 결함 제보, 리뷰 지적 수정 요청에 사용한다.
allowed-tools: Bash(git status:*), Bash(git rev-parse:*), Bash(git log:*)
---

# BATON WATCH 결함 수정

요청: $ARGUMENTS

요청이 비어 있으면 대화에서 받은 결함 설명을 기준으로 한다.

## 시작 상태

- 브랜치와 변경: !`git status --short --branch`
- 현재 커밋: !`git rev-parse HEAD`
- 최근 커밋: !`git log --oneline -5`

이 세션에서 이미 작업을 시작했다면 처음 기록한 커밋을 작업 시작 커밋으로 유지한다. 아니면 위 현재 커밋을 기록한다.
`main`이면 작업 브랜치를 만든다. 미커밋 변경이 있으면 보존하고 요청과 관련 있는지 먼저 확인한다.

## 1. 범위 확인

- HANDOFF의 `현재 작업`·`차단 상태`에서 같은 문제나 이전 판단이 있는지 확인한다.
- 결함이 속한 영역의 스킬을 함께 사용한다: [API](../baton-watch-api-contract/SKILL.md), [영속성](../baton-watch-persistence/SKILL.md), [외부 통신](../baton-watch-outbound-http/SKILL.md), [운영](../baton-watch-ops/SKILL.md), [관측성](../baton-watch-observability/SKILL.md).
- 기대 동작은 PRD에서 확인한다. PRD가 결함 동작을 허용하거나 정하지 않았으면 계약 변경이므로 사용자에게 확인한다.

## 2. 재현

- 수정 전에 실패하는 테스트를 먼저 추가한다. 결함이 실제로 드러나는 가장 낮은 경계(값 타입 → 로컬 HTTP·TLS → 실제 Tomcat → 실제 PostgreSQL)를 고른다.
- 합성 입력만 사용한다. 실제 비밀값·공개 대상·운영 DB에 요청하지 않는다.
- 실패를 기록한다. 종료 코드 1이 기대 결과다.

```bash
python3 ops/check-feedback.py file <테스트 파일>
python3 ops/run-validation.py run --label <주제>-reproduction -- \
  ./gradlew :<모듈>:test --tests '<클래스>.<메서드>'
```

- 재현되지 않으면 수정하지 않는다. 시도한 조건과 결과를 보고한다.
- 정상 경로·경계값도 함께 넣어 수정이 기존 동작을 깨지 않는지 확인한다. 구현을 그대로 반복하는 테스트는 추가하지 않는다.

## 3. 수정

- 원인이 있는 계층에서 최소로 고친다. 같은 경계의 중복 검증은 추가하지 않고, 서로 다른 경계(웹 형식·도메인 값·DB 제약·리스 경합)의 검증은 유지한다.
- 기존 확장점·유틸리티를 먼저 찾는다. 새 의존성은 비용·라이선스·공급망 영향을 확인한 뒤에만 추가한다.
- 오류 메시지·로그·예외에 URL·토큰·응답 본문·원인 예외 원문이 새로 노출되지 않는지 확인한다.
- 수정한 파일은 바로 `check-feedback.py file`로 검사한다.

## 4. 검증

[검증](../baton-watch-validation/SKILL.md) 스킬을 따른다.

1. 재현 테스트 통과: `--label <주제>-regression`
2. 해당 모듈 전체, 영향받는 호출자 모듈
3. 여러 모듈 동작이 바뀌면 `./gradlew test`
4. `test-summary.py`로 관련·전체 테스트 수와 건너뜀을 집계한다.

변경 범위 밖의 DB·이미지·부하·공급망 검사는 반복하지 않고 이유를 기록한다.

## 5. 커밋

- 첫 커밋은 코드·테스트만 담는다: `fix: <무엇을 어떻게 막았는지>`. 기능 추가면 `feat:`를 쓴다.
- 이어서 [문서](../baton-watch-documentation-flows/SKILL.md) 스킬에 따라 계약·기준은 PRD, 설정·동작 설명은 README, 운영 절차는 런북을 고친다.
- HANDOFF `현재 작업`과 `최근 검증`에 첫 커밋 해시로 항목을 추가하고 두 번째 커밋을 만든다: `docs: <주제>와 검증 결과 정리`.
- 커밋 메시지는 한국어로 쓰고 끝에 세션 지시의 공동 작성자 줄을 붙인다.

## 6. 완료

```bash
python3 ops/run-validation.py run --label <주제>-finish -- \
  python3 ops/check-feedback.py finish --base <작업 시작 커밋>
```

- `git diff <작업 시작 커밋> --` 전체와 새 파일 본문을 읽고 [검토](../baton-watch-review/SKILL.md) 기준으로 범위·중복을 확인한다.
- 작업 브랜치를 푸시한다. 강제 푸시는 명시적 승인이 필요하다.
- 보고는 변경 내용, 검증 결과(재현 → 통과 개수), 남은 차단 사유만 간단히 쓴다. 실제 배포·외부 연동은 실행한 경우에만 실행했다고 쓴다.
