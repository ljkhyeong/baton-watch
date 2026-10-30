---
name: baton-watch-validation
description: BATON WATCH 변경을 검증하거나 검증 결과를 보고할 때 사용한다. 작성 직후 파일 검사(`ops/check-feedback.py file`), 종료 검사(`finish --base`), 검증 기록(`ops/run-validation.py`), 변경 범위별 Gradle·ops 테스트 선택, 기존 결과 재사용, JUnit 테스트 개수 집계와 보고 문구를 다룬다.
---

# BATON WATCH 검증

기준은 [개발 검증 절차](../../../docs/runbooks/development-validation.md)다. 이 스킬은 Claude Code에서 그 절차를 실행하는 방법만 정리한다.

## 순서

1. 작업 시작 커밋을 기록한다. 중간에 커밋해도 종료 검사 기준은 바꾸지 않는다.
2. 파일을 작성·수정한 직후 `python3 ops/check-feedback.py file <파일...>`을 실행한다. 한 번에 고친 관련 파일은 묶는다. 실패하면 해당 범위를 고친 뒤 다음 작업으로 넘어간다.
3. 동작 테스트 전에 `python3 ops/run-validation.py status`로 같은 입력의 성공 결과가 있는지 본다. `passed`이고 “파일 동일”이며 환경도 같으면 재사용한다.
4. 아래 표에서 관련 테스트부터 고르고 `run-validation.py run`으로 실행한다.
5. 완료 직전 종료 검사를 실행하고 `git diff <작업 시작 커밋> --`와 새 파일 본문을 읽어 범위·중복·책임 배치를 검토한다. 필요하면 [검토](../baton-watch-review/SKILL.md) 스킬을 사용한다.

```bash
python3 ops/run-validation.py run --label <주제>-finish -- \
  python3 ops/check-feedback.py finish --base <작업 시작 커밋>
git ls-files --others --exclude-standard
```

## 범위별 검사

| 변경 범위 | 먼저 실행 | 추가 조건 |
| --- | --- | --- |
| 문서·스킬 | `check-feedback.py file`, `git diff --check` | Java·이미지 검사 불필요 |
| 단일 Java 동작 | `./gradlew :<모듈>:test --tests '<관련 클래스>'` | 통과 후 모듈 전체와 영향받는 호출자 |
| 여러 모듈 | 관련 테스트 후 `./gradlew test` | 의존성·패키징 변경이면 `:bootstrap:verifyBootJarLicense` |
| 계층 구조만 | `./gradlew :bootstrap:architectureTest` | `finish`가 Java·Gradle 변경 시 자동 실행 |
| 운영 Python·셸 | 해당 `ops/tests` 검사, `bash -n`·ShellCheck | DB·Docker 호출 변경이면 실제 연동 |
| Compose·이미지 | 파일 조합별 `docker compose config`, 정책 테스트 | [운영](../baton-watch-ops/SKILL.md) 스킬 |
| 부하·복구 | `processRecoveryTest`, `runtimeLoadTest` | 리스·종료·일정·동시성 변경 또는 측정 요청 시 |

영역별 테스트 클래스는 [API](../baton-watch-api-contract/SKILL.md), [영속성](../baton-watch-persistence/SKILL.md), [외부 통신](../baton-watch-outbound-http/SKILL.md), [관측성](../baton-watch-observability/SKILL.md) 스킬을 따른다.

## 라벨과 실행

- 라벨은 영문 소문자·숫자·하이픈으로 `<주제>-<단계>` 형식을 쓴다. 기존 기록은 `reproduction`(실패 기대), `test-file`·`source-file`(파일 검사), `regression`, `<모듈>-tests`, `complete`, `finish` 단계를 사용했다.
- 명령 하나만 실행한다. `;`·`&&`로 이어 앞선 실패를 가리지 않는다. 자격 증명은 명령 인자로 넘기지 않는다.
- 전체 `./gradlew test`, 부하·복구 작업처럼 2분을 넘을 수 있는 명령은 Bash `timeout`을 늘리거나 `run_in_background`로 실행하고 완료 알림을 기다린다. 상태를 반복 조회하지 않는다.
- 로그 전체는 `.gradle/agent-validation/<시각>-<라벨>/`에 남는다. 실패하면 출력된 로그 끝과 해당 로그 파일의 실패 구간부터 읽는다.
- `clean`, `--rerun-tasks`, `--no-build-cache`, `--refresh-dependencies`는 캐시 문제 재현이나 의존성 갱신이 필요할 때만 쓴다.

## 결과 집계와 보고

```bash
python3 .claude/skills/baton-watch-validation/scripts/test-summary.py bootstrap adapter-out-external
python3 .claude/skills/baton-watch-validation/scripts/test-summary.py bootstrap:architectureTest
```

- 모듈·작업별 스위트·테스트·건너뜀·실패·오류 수와 실행 시각을 출력한다. 결과가 없거나 실패·오류가 있으면 종료 코드 1이다.
- Gradle은 실행할 때 이전 XML을 지우므로 `--tests`로 고른 실행 뒤에는 고른 테스트 수만 남는다. 관련 테스트 수는 필터 실행 직후, 모듈 전체 수는 전체 실행 직후 집계한다.
- 실행 시각이 이번 `run-validation` 시작보다 이르면 `UP-TO-DATE`·`FROM-CACHE`로 재사용된 결과다. “재사용”으로 보고하고 새로 실행했다고 쓰지 않는다.
- 보고는 HANDOFF 문체를 따른다: “관련 N개·모듈 전체 M개 통과, 실패·건너뜀 없음”. 실행하지 않은 검사는 이유와 함께 적는다.

## 환경 차단

- Docker가 없으면 Testcontainers·promtool·gateway 검사가 실패한다. 환경 차단으로 보고하고 건너뛴 테스트를 성공으로 처리하지 않는다.
- 기본 Python에는 `yaml`이 없고 로컬 PATH에는 ShellCheck·actionlint가 없을 수 있다. HANDOFF `검증 환경`을 먼저 확인하고 다른 인터프리터로 추측해서 반복하지 않는다.
- 같은 코드·환경·승인 상태에서 실패한 DNS 조회·로그인·푸시·배포는 반복하지 않고 HANDOFF `차단 상태`에 재개 조건을 적는다.
