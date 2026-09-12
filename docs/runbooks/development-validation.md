# 개발 검증 절차

## 기본 흐름

1. `git status --short --branch`와 HANDOFF를 확인하고 작업 시작 커밋을 기록한다.
2. 파일을 작성·수정한 직후 아래 파일 검사를 실행한다. 한 번에 수정한 관련 파일은 묶어서 검사한다.
3. 아래 표에서 필요한 동작 테스트를 골라 검증 기록 도구로 실행한다. 실패하면 해당 범위부터 수정·재검사한다.
4. 완료 직전 종료 검사를 실행하고 작업 시작 이후의 전체 diff와 새 파일 본문을 검토한다.
5. 필수 검사가 통과하면 입력이 같은 성공 결과는 재사용한다. 문서 수정·커밋만으로 동작 테스트를 반복하지 않는다.

## 작성 직후와 종료 전 검사

[검사 도구](../../ops/check-feedback.py)는 Python 표준 라이브러리와 기존 Gradle을 사용한다.
파일 검사는 AGENTS 지시에 따라 에이전트가 호출한다. 구조 검사는 기존 `test`·`check`와 CI에도 연결돼 있다.

```bash
# 작업을 시작할 때 한 번 기록한다. 에이전트는 이 커밋을 후속 작업에도 인계한다.
WATCH_FEEDBACK_BASE=$(git rev-parse HEAD)

# 파일을 작성·수정한 직후: 실제 변경 경로를 지정한다.
python3 ops/check-feedback.py file ops/check-feedback.py

# 작업 종료 직전: 중간에 커밋했어도 작업 시작 커밋을 유지한다.
python3 ops/run-validation.py run --label finish -- \
  python3 ops/check-feedback.py finish --base "$WATCH_FEEDBACK_BASE"
git diff "$WATCH_FEEDBACK_BASE" --
git ls-files --others --exclude-standard
```

| 단계 | 자동 검사 범위 |
| --- | --- |
| `file` | 지정 파일의 줄 끝 공백·LF·마지막 줄바꿈, Python·JSON·TOML·XML·셸 문법, Markdown 로컬 파일 링크. Java는 해당 모듈의 운영 코드 또는 테스트 코드 컴파일 |
| `finish` | 기준 커밋 이후의 커밋·스테이징·미커밋 변경과 새 파일. 삭제·이름 변경도 포함. 파일 검사·`git diff --check` 후 Java·Gradle·검사 도구 변경이 있으면 전체 ArchUnit 검사 |

Java 파일 검사에는 컴파일에 필요한 상위 모듈도 포함된다. Gradle 파일은 `help`로 구성 오류부터 확인한다.
종료 검사는 문서만 바뀌면 Java를 실행하지 않는다. Markdown은 로컬 파일 존재만 검사하며 제목 앵커·
외부 링크는 확인하지 않는다. YAML의 의미·Compose 정책과 기능 동작 테스트는 아래 표에 따라 별도로 확인한다.
전체 diff를 읽을 때는 자동 규칙에 없는 책임 배치·중복·요청 범위도 검토한다. 새 파일 본문은 별도로 읽는다.

## 계층 의존성 규칙

[ArchitectureTest](../../bootstrap/src/test/java/com/personal/baton/watch/bootstrap/ArchitectureTest.java)는
테스트 코드를 제외한 전체 운영 바이트코드를 검사한다. 필드·메서드·생성자 등 실제 타입 참조를 확인하며
위반한 클래스와 의존 대상을 출력한다. DB·Spring 컨텍스트·외부 API는 기동하지 않는다.

| 규칙 | 허용·차단 기준 |
| --- | --- |
| 계층 방향 | `bootstrap → adapters → application → domain`. 각 계층 내부와 하위 도메인 값 사용은 허용, 역방향 의존은 차단 |
| 어댑터 분리 | 웹·영속성·외부 통신 어댑터 간 직접 의존 차단. Controller의 JDBC 저장소 구현체 사용도 포함 |
| 도메인·애플리케이션 | `java.*`와 두 핵심 계층만 사용. Spring·DB·HTTP 구현 의존 차단. 서비스는 `application`의 저장소 포트 사용 가능 |
| 웹 진입점 | 서비스 구현체·출력 포트 직접 사용 차단. 입력 포트로 유스케이스 호출 |
| 검사 누락 | 정해진 계층 밖의 WATCH 패키지 또는 비어 있는 계층도 실패 처리 |

구조 검사만 실행할 때는 다음 명령을 사용한다.

```bash
python3 ops/run-validation.py run --label architecture -- ./gradlew :bootstrap:architectureTest
```

ArchUnit은 테스트 전용 의존성이다. 별도 `architectureTest` 작업에서 실행하며 `bootstrap:test`가 이를
선행 실행한다. 일반 테스트에서는 같은 클래스를 제외해 중복 실행하지 않는다. 기존 Gradle 캐시를 사용한다.

## 변경 범위별 동작 테스트

| 변경 범위 | 먼저 실행 | 추가 검사 조건 |
| --- | --- | --- |
| 문구·문서·스킬 | 변경 파일의 링크·형식, `git diff --check` | 스킬 변경 시 frontmatter·표시 정보 확인. Java·이미지 검사는 불필요 |
| 단일 Java 동작 | `./gradlew :모듈:test --tests '관련테스트클래스'` | 해당 모듈 전체 테스트와 영향받는 호출자 확인 |
| 여러 모듈 동작 | 관련 테스트 후 `./gradlew test` | 의존성·패키징 변경이면 `:bootstrap:verifyBootJarLicense` 포함 |
| 운영 Python·셸 | 해당 `ops/tests` 검사, 셸은 `bash -n`·ShellCheck | DB·Docker를 호출하는 부분을 바꾸면 실제 연동 검사 |
| Compose·이미지 | 변경한 파일 조합의 `docker compose config`, 관련 정책 테스트 | 이미지 변경은 빌드·공급망 검사, 실행 변경은 시작·상태·종료 검사 |
| 부하·복구·성능 | 해당 전용 Gradle 작업 | 리스·종료·일정·동시성 동작을 바꾸거나 측정을 요청받은 경우 |

`gradle.properties`에 빌드 캐시·병렬 실행이 켜져 있다. 로컬 반복 작업은 기본값을 쓴다.
`clean`, `--no-build-cache`, `--rerun-tasks`, `--refresh-dependencies`는 캐시 문제 재현·
의존성 체크섬 갱신·독립 재실행이 필요한 경우에만 쓴다. CI의 전체 검사와 공급망 기준은 유지한다.
`UP-TO-DATE`·`FROM-CACHE`는 재사용 결과로 보고하고 이번에 테스트가 새로 실행됐다고 쓰지 않는다.

## 검증 기록 도구

Python 3.11 이상과 Git만 필요하다. 외부 Python 패키지는 설치하지 않는다.

```bash
python3 ops/run-validation.py status
python3 ops/run-validation.py run --label api -- ./gradlew :adapter-in-web:test
python3 ops/run-validation.py run --label full -- ./gradlew test
python3 ops/run-validation.py run --label diff -- git diff --check
```

전체 로그와 JSON은 Git에서 제외된 `.gradle/agent-validation/`에 저장한다.
JSON에는 명령·커밋·파일 지문·시각·소요 시간·종료 코드를 남긴다. 원문 diff·표준 입력·환경 변수는 저장하지 않는다.
실패하면 같은 종료 코드와 로그 끝만 출력한다. 명령을 `;`로 이어 앞선 실패를 뒤의 성공으로 덮지 않는다.
자격 증명은 명령 인자 대신 환경 변수나 비밀 파일로 전달한다.

`status`의 “파일 동일”은 추적 파일과 Git에서 제외되지 않은 새 파일의 내용·권한·심볼릭 링크가
같다는 뜻이다. 실행 전후 파일이 달라졌으면 동일 결과로 표시하지 않는다.
표준 입력·JDK·Docker·의존성 캐시·환경 변수·원격 서비스까지 같다는 뜻은 아니다.
도구는 검사를 자동으로 생략하지 않으며, 재사용 여부는 변경 범위와 환경을 함께 보고 결정한다.

## 재실행 전에 확인할 조건

- 도구 오류: 실행 파일·필요 모듈·Docker 연결을 한 번 확인한다. 사용 가능한 실행 경로를 기록하고
  같은 실패를 다른 Python이나 셸로 추측해서 반복하지 않는다. 권한·샌드박스 오류와 인증 오류를 구분한다.
- 외부 차단: 실패한 주소·작업과 재개 조건을 HANDOFF에 적는다. 코드·환경·승인 상태가 그대로면
  같은 DNS 조회·로그인·푸시·배포를 반복하지 않는다. 현재 상태를 다시 확인해 달라는 요청은 별도로 처리한다.
- 공급망 검사: 이미지 다이제스트·플랫폼·Trivy 버전·취약점 DB·검사 정책을 결과와 함께 확인한다.
  같은 후보와 입력이면 기존 보고서부터 읽는다. 후보·DB·정책 변경이나 최신 검사 요청이 있을 때 재검사한다.
- 세션 검토: 저장소와 관련된 작업만 선택하고 최근 최종 결과·실패 명령부터 읽는다.
  `read_thread`의 `includeOutputs: false`만으로 출력이 작아지지는 않으므로 결과를 저장한 뒤 필요한 필드만 추린다.
- 긴 출력: 로그는 파일로 남기고 요약·실패 구간부터 읽는다. 잘린 출력 전체를 반복해서 불러오지 않는다.

## 기존 작업에서 반영한 사례

2026-09-05에 `MVP 이후 우선순위 정리`와 `검토: 추가 개선 기능`의 WATCH 작업 기록을 검토했다.
프로젝트 전체 작업이나 모든 도구 호출을 전수 분석한 결과는 아니다.

| 확인한 사례 | 반영한 조치 |
| --- | --- |
| 9월 2일 확인한 cloudflared 2026.8.3 의존성과 공식 이미지 차단을 9월 5일 병합 시 다시 조사 | 최신 차단 사유·재개 조건·기존 패치 확인 자동화를 HANDOFF에 기록 |
| 로컬 검증에 전체 재실행 옵션이 반복 사용됨 | 기본 캐시 사용과 전체 재검사가 필요한 조건 명시 |
| 기본·번들 Python에서 `yaml` 부재를 연달아 확인 | 검증 기록 도구는 표준 라이브러리만 사용. 추가 도구는 필요한 환경 하나부터 확인 |
| 앞선 Python 검사 실패 뒤 `git diff` 성공이 셸의 최종 종료 코드로 표시됨 | 검증 명령 하나의 종료 코드를 그대로 반환하는 실행 도구 추가 |
| 큰 세션 출력이 잘리고 HANDOFF에 과거 검증 기록이 누적됨 | 세션 결과를 필요한 필드로 제한하고 과거 인계를 별도 보관 |
