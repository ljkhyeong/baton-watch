# BATON WATCH 인계

최종 수정일: 2026-09-08

## 현재 작업

- `304bd0c`에서 필수 CI를 막던 배포 의존성·이미지 취약점을 수정했다. Tomcat은 11.0.25,
  PostgreSQL의 OpenSSL은 3.5.8-r0, libuuid는 2.42.3-r1로 고정하고 gosu를 su-exec으로 교체했다.
  NGINX는 수정된 공식 다이제스트를 사용한다. cloudflared는 공식 2026.8.3 소스를
  Go 1.26.6·x/crypto 0.55.0·gRPC 1.83.1로 빌드한다. [빌드 입력](ops/cloudflared/README.md)을 참고한다.
  자체 이미지 5개의 보관·복원과 OCI·LICENSE 검증을 CI·배포 절차에 반영했다.
- `454360b`에서 V6 마이그레이션으로 점검 결과·전달 결과·전달 완료 상태의 DB 제약 3개를 보강했다.
  수정 전에는 필수 값이 `NULL`인 잘못된 조합 9가지가 저장됐다. 이제 `CASE`와 `IS TRUE`로 거부한다.
  운영 DB 적용은 미실행이다. 기존 불완전한 이력이 있으면 V6 적용이 실패하며, 이력을 자동 수정·삭제하지 않는다.
- 작업 브랜치: `codex/grafana-cloud-free`. `3739149`에서
  [Grafana Cloud Free 연결 설정](ops/prometheus/watch-cloud-free.yml)을 추가했다.
  WATCH 지표만 60초마다 전송하며 한 번의 수집은 1MB·2,000개 샘플로 제한한다.
- 실제 Free 계정·전송 주소·비밀 파일·수집기는 미설정이다. 외부 전송과 계정 인증은 실행하지 않았다.
  [무료 연결 절차](docs/runbooks/grafana-cloud-free.md)에 따라 준비한 뒤 수신을 확인한다.
- `8bf0546`에서 공용 HTTP의 미사용 실패 바이트 집계와 본문 폐기 함수의 반환값·콜백·0바이트 분기를 제거했다.
  콜백 본문 상한, HTTP 취소·타임아웃 분류는 유지한다.
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
| 보안 수정 `304bd0c` | `./gradlew test :bootstrap:verifyBootJarLicense` 통과. 447개 중 web·bootstrap 113개 실행, 나머지 334개 기존 결과 재사용. 실패·건너뜀 없음. 실제 JAR의 Tomcat 세 개 모두 11.0.25 확인 |
| 최종 배포 이미지 | linux/arm64 이미지 5개 빌드, OCI·LICENSE·cloudflared 실행·CLI/RPC 테스트 통과. 기존 정책으로 JAR·이미지 SBOM 7개에서 수정 가능한 HIGH·CRITICAL 0건. gosu 완전 삭제 후 바뀐 PostgreSQL·DB 작업 이미지 2개만 재검사하고 나머지 이미지 ID·아카이브 일치 확인 |
| 운영 검증 | Compose 정책·이미지 보관/복원·NGINX 검사 통과. 실제 PostgreSQL V1~V6 적용, 최소 권한, 비밀번호 교체·원복, WATCH 기동·재시작 통과. Docker Desktop 파일 공유 캐시 때문에 비밀 파일 덮어쓰기 대신 단계별 파일 경로를 사용하는 테스트로 수정 |
| DB 결과 정합성 `454360b` | 회귀·V5 업그레이드 테스트 11개 통과. `./gradlew test` 447개 중 159개 실행·288개 기존 결과 재사용, 실패·건너뜀 없음. 정상 이력 보존과 불완전한 이력의 적용 거부 확인 |
| 중복 코드 정리 `d6b0ca1` | 관련 테스트 69개 통과. `./gradlew test` 436개 중 424개 실행·domain 12개 기존 결과 재사용. 마지막 보조 클래스 통합 후 `:bootstrap:test` 91개 재검증 통과. 실패·건너뜀 없음. 메트릭 시작·기록 실패 시 전달 성공 유지, 리다이렉트 후 오류의 시간·횟수 보존 확인 |
| 문구 정리 | 대시보드 JSON 정상, 제목 외 쿼리·단위·설정 동일 확인. 변경 문서 링크·형식과 `git diff --check` 통과. 문구만 바뀌어 Java·PromQL·배포 검사는 반복하지 않음 |
| 무료 메트릭 연결 `3739149` | `./ops/tests/prometheus-rules-test.sh` 통과. 새 설정 문법·기존 대시보드·경보 검사. 계정·수집기 정보가 없어 실제 인증·전송·수신은 미실행. Java·배포 이미지 변경이 없어 해당 검사는 반복하지 않음 |
| 테스트 준비 코드 정리 | `WorkerExecutionBudgetTest`·`ApacheHttpHopTransportTest`·`ApacheEventDeliveryTransportTest` 15개 실행·통과. 실패·건너뜀 없음. 테스트 보조 코드만 바뀌어 관련 검사만 실행 |
| 공용 HTTP 정리 `8bf0546` | 당시 `:adapter-out-external:test` 210개 실행·통과, 실패·건너뜀 없음. 본문·헤더 제한, TLS·DNS 고정, 취소·종료·타임아웃 확인 |
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
테스트 준비 코드 정리 로그는 `.gradle/agent-validation/20260905T135604532497Z-test-support-cleanup/`에 있다.
무료 메트릭 연결 로그는 `.gradle/agent-validation/20260906T154649360265Z-grafana-cloud-free/`에 있다.
문구 검증 로그는 `.gradle/agent-validation/20260907T225732881196Z-clear-copy/`에 있다.
중복 코드 정리 로그는 `.gradle/agent-validation/20260908T010228367490Z-cleanup-full/`,
최종 bootstrap 로그는 `.gradle/agent-validation/20260908T010354287577Z-cleanup-bootstrap-final/`에 있다.
DB 결과 정합성 검증 로그는 `.gradle/agent-validation/20260908T011910849207Z-result-integrity-full/`에 있다.
보안 수정의 Gradle 로그는 `.gradle/agent-validation/20260908T123100953206Z-security-gradle/`,
최종 DB 로그는 `.gradle/agent-validation/20260908T124041614696Z-security-postgres-secret-paths/`에 있다.
공급망 로그는 `.gradle/agent-validation/20260908T123327906934Z-security-supply-chain/`과
수정한 이미지 2개의 `.gradle/agent-validation/20260908T123845279539Z-security-postgres-rescan/`에 있다.
로컬 검증 이미지는 `e9d8e03`에 미커밋 수정을 반영한 후보였다. 배포용 이미지는 최종 커밋에서
새로 빌드·검증한다. 원격 linux/amd64 결과는 [PR #37](https://github.com/ljkhyeong/baton-watch/pull/37)에서 확인한다.

## 이번 세션의 도구 상태

- 기본·번들 Python에는 `yaml`이 없다. YAML 검사는 PyYAML 6.0.3이 설치된
  `/private/tmp/baton-watch-skill-validation-20260905/bin/python`으로 통과했다. 다음 사용 전 경로 존재 여부를 확인한다.
- `actionlint`·ShellCheck는 PATH에 없었다. 이번 CI 변경은 YAML 구문과 등록 명령을 확인했으며 원격 워크플로 전체를 실행한 결과는 아니다.

## 차단 상태와 다시 확인할 조건

| 항목 | 마지막 확인과 다음 조건 |
| --- | --- |
| WATCH 원격 병합 | PR #37의 최신 필수 `verify` 결과로 판단한다. 이전 [실행 34222194403](https://github.com/ljkhyeong/baton-watch/actions/runs/34222194403)의 기능·DB·복구 검사는 통과했고, 공급망 실패 항목은 `304bd0c`에서 수정·로컬 검증했다 |
| 공식 cloudflared 후보 | 2026.8.3 공식 이미지에는 필요한 의존성 수정이 없어 공식 소스를 패치 버전의 의존성으로 빌드한다. 필요한 수정이 포함된 공식 이미지가 나오면 같은 공급망 검사를 통과한 뒤 별도 빌드를 제거할 수 있다 |
| 공식 이미지 패치 확인 | 기존 `MVP 이후 우선순위 정리` 작업에 매일 오전 9시 확인이 설정돼 있음. 새 자동화 추가 전 기존 설정을 조회하며, 같은 후보의 다운로드·검사를 중복 수행하지 않음 |
| 공개 스테이징 | `watch-staging.b4ton.com` DNS·스테이징 환경과 실제 연동 설정이 준비되면 [공개 검증 절차](docs/runbooks/baton-resource-health-verification.md) 재개 |
| BATON 원격 `main` | 9월 8일 `a9d1feb9` 병합·푸시와 원격 참조 확인. 최신 화면·문구와 WATCH 상태 조회·재점검을 함께 유지. 원본 체크아웃의 별도 작업은 보존 |

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
