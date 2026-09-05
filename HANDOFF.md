# BATON WATCH 인계

최종 수정일: 2026-09-05

## 현재 작업

- 작업 브랜치: `codex/simplify-watch-skills`. 공용 HTTP의 미사용 실패 바이트 집계를 제거하고
  응답 시작 알림을 `Runnable`로 바꿨다. 본문 폐기 함수의 반환값·진행 콜백·0바이트 분기와 전용 테스트 1개를 제거했다.
  콜백 본문 상한·실제 읽은 바이트 검사, HTTP 취소·타임아웃 분류는 유지한다.
- `d3aa13a`에서 점검 바이트 계산·전용 테스트 2개, 미사용 `ClaimedCheck.scheduledAt`과
  스케줄러 메트릭 주입을 제거했다. 기존 설정 검증·저장 필드, DB 예약·리스·지연 측정은 유지한다.
- `15bd9b8`에서 지시·스킬을 정리했고, `d1763d0`에서 [검증 기록 도구](ops/run-validation.py)와
  [개발 검증 절차](docs/runbooks/development-validation.md)를 추가했다.
  기록은 `.gradle/agent-validation/`에 남기며 `status` 명령으로 최근 결과와 파일 변경 여부를 확인한다.
- 스킬은 API·영속성·운영·관측성·문서 5개다. 공통 규칙은 [AGENTS.md](AGENTS.md)를 따른다.
  새 작업마다 전체 검증 이력이나 모든 스킬을 읽을 필요는 없다.

## 최근 검증

| 대상 | 결과와 재사용 범위 |
| --- | --- |
| 공용 HTTP 정리 | 관련 테스트 후 `:adapter-out-external:test` 210개 실행·통과, 실패·건너뜀 없음. 본문·헤더 제한, TLS·DNS 고정, 취소·종료·타임아웃 확인. 변경과 호출자가 외부 통신 모듈 안에 있어 전체 프로젝트 검사는 반복하지 않음 |
| 점검 코드 정리 `d3aa13a` | 당시 `./gradlew test` 통과. 435개 중 423개 실행·domain 12개 기존 결과 재사용, 실패·건너뜀 없음. 실제 PostgreSQL 검사 포함 |
| 검증 기록 도구 | Python 테스트 5개 통과. 성공·실패 종료 코드, 도구 부재, 실행 중 파일 변경, 시그널 종료 확인 |
| 개발 검증 절차·CI 등록 | 문서 링크 74개·워크플로 YAML·기존 필수 Gradle 명령 유지·이전 인계 본문 보존 확인 |
| 지시·스킬 정리 `15bd9b8` | 스킬 5개 형식·표시 정보와 문서 링크 44개 통과. 당시 문서 전용 검증 |
| WATCH 용량·복구·공급망 | 이번 코드 정리에서 별도 재실행하지 않음. 이전 결과와 차단 상태는 아래 표·이전 인계 기록 참조 |
| BATON 문구 `ef39f1fc` | 타입·빌드·정책 113개와 실행 대상 브라우저 512개 통과, 기기별 제외 43개. WATCH Java 검증과 구분 |

검증 소스가 바뀌지 않은 문서 수정은 링크·형식만 확인한다. 환경·의존성·원격 상태가 바뀌면
이전 성공을 새 실행 결과로 보고하지 않는다. 긴 검사는 실행 도구로 로그를 남기고 종료 코드를 확인한다.
점검 코드 정리의 전체 로그는 `.gradle/agent-validation/20260905T132821057159Z-cleanup-full/`에 있다.
공용 HTTP 모듈 로그는 `.gradle/agent-validation/20260905T134006130498Z-http-module/`에 있다.

## 이번 세션의 도구 상태

- 기본·번들 Python에는 `yaml`이 없다. YAML 검사는 PyYAML 6.0.3이 설치된
  `/private/tmp/baton-watch-skill-validation-20260905/bin/python`으로 통과했다. 다음 사용 전 경로 존재 여부를 확인한다.
- `actionlint`·ShellCheck는 PATH에 없었다. 이번 CI 변경은 YAML 구문과 등록 명령을 확인했으며 원격 워크플로 전체를 실행한 결과는 아니다.

## 차단 상태와 다시 확인할 조건

| 항목 | 마지막 확인과 다음 조건 |
| --- | --- |
| WATCH 원격 `main` | 9월 5일 `5836328`. 로컬 병합 `e799504` 푸시는 필수 `verify` 실패로 거부됨 |
| PR #32 보안 검사 | [실행 33957066002](https://github.com/ljkhyeong/baton-watch/actions/runs/33957066002): 기능 검사·CodeQL 통과, Tomcat과 이미지 취약점으로 실패. 이미지·의존성·검사 DB가 바뀌면 해당 보고서부터 재확인 |
| 공식 cloudflared 후보 | `2026.8.3`도 `x/crypto 0.53.0`·gRPC `1.83.0`을 포함해 단순 교체로 해결되지 않음. 공식 수정 릴리스 또는 승인된 별도 해결 방안이 재개 조건 |
| 공식 이미지 패치 확인 | 기존 `MVP 이후 우선순위 정리` 작업에 매일 오전 9시 확인이 설정돼 있음. 새 자동화 추가 전 기존 설정을 조회하며, 같은 후보의 다운로드·검사를 중복 수행하지 않음 |
| 공개 스테이징 | `watch-staging.b4ton.com` DNS·스테이징 환경과 실제 연동 설정이 준비되면 [공개 검증 절차](docs/runbooks/baton-resource-health-verification.md) 재개 |
| BATON 원격 `main` | 9월 5일 `ef39f1fc` 반영 확인. `/Users/lim/devProject/personal/manager`의 별도 미푸시 작업은 보존 |

현재 상태 재확인 요청이 없다면 위 조건이 그대로인 작업은 재시도하지 않는다.
이 기록은 당시 확인 결과이며 현재 원격 상태나 배포 완료를 보장하지 않는다.

## 구현과 다음 진입점

- 대상 GET은 헤더만 확인하고 본문을 읽지 않는다. 인증된 수동 재점검 API는 기존 일정·리스를 유지하며
  새 예약에 30초 간격을 적용한다. V5 마이그레이션이 필요하다.
- BATON 자료 상태 표시·재점검과 WATCH 독립 복원은 [연동 확인](docs/runbooks/baton-integration-review.md),
  [스냅샷 복원](docs/runbooks/baton-snapshot-recovery.md)에 구현·검증 범위를 기록했다.
- 공개 배포에는 이그레스 정책, 지원 규모·SLO, 공개 HTTPS 제한 검증, 대시보드·알림,
  암호화 백업·보존·복구 담당자와 Cloudflare·BATON 설정이 필요하다.
  [스테이징 배포](docs/runbooks/staging-deployment.md)와 [공개 이벤트 전달](docs/runbooks/public-staging-event-delivery.md) 절차를 따른다.
- 과거 커밋별 검증과 판단 근거는 [이전 인계 기록](docs/history/handoff-2026-09-05.md)에 보관했다.
