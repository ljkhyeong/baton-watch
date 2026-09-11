# BATON WATCH 인계

최종 수정일: 2026-09-12

## 현재 작업

- 작업 브랜치 `codex/api-temporary-failures`의 `dc83cf7`에서 일시적 DB 장애를 `503 SERVICE_UNAVAILABLE`로 구분했다.
  단건·묶음 조회, PUT·재점검 POST에 `Retry-After: 5`를 제공한다. Spring 표준 예외로 분류하고
  SQL 원인이 없는 트랜잭션 생성 실패·데이터 제약 위반·코드 오류는 500을 유지한다.
  오류 원문은 노출하지 않고 서버 내부 재시도는 추가하지 않았다. 기존 런타임의 `spring-tx 7.0.9`를
  웹 모듈에 명시했으며 라이브러리 버전·DB 변경·추가 비용은 없다. 운영 배포는 미실행이다.
- 작업 브랜치 `codex/batch-monitor-query`의 `1baac29`에서 최대 20개 대상의 묶음 조회 API를 추가했다.
  인증된 `GET /api/v1/resource-monitors?resourceReference=...`는 한 번의 DB 조회로 처리하고,
  요청 순서대로 중복을 제거한다. 미등록 대상은 `missingResourceReferences`로 구분한다.
  기존 단건 조회·인증·민감정보 제외 규칙을 유지한다. 추가 의존성·DB 변경·비용은 없다.
  BATON의 묶음 조회 전환과 운영 배포는 미실행이며 [연동 준비](docs/runbooks/baton-integration-review.md)에 조건을 기록했다.
- 작업 브랜치 `codex/check-progress-status`의 `45e518a`에서 모니터 PUT·GET에 `checkStatus`를 추가했다.
  예약(`SCHEDULED`)·대기(`QUEUED`)·진행 중(`IN_PROGRESS`)·비활성(`INACTIVE`)을 기존 일정·리스와
  주입된 `Clock`으로 판단한다. 기존 응답 필드·점검 실행 규칙은 유지하며 내부 리스 정보는 노출하지 않는다.
  추가 의존성·DB 마이그레이션·비용은 없다. BATON 소비자 연동과 운영 배포는 미실행이다.
- 작업 브랜치 `codex/callback-retry-after`의 `ec521db`에서 콜백 429·503의 `Retry-After`를 반영했다.
  정수 초·HTTP 날짜를 다음 재시도 시각에 적용하며 기존 백오프·상한을 유지한다.
  잘못된 헤더는 무시하고 원문은 저장하지 않는다. 추가 의존성·DB 마이그레이션은 없다.
  실제 BATON 콜백 연결과 운영 배포는 미실행이다.
- `a71c1ad` 이후 장애 진단·복구·부하 시험 문서와 Java 주석의 추상적인 표현을 추가로 정리했다.
  재점검 응답 3종은 표로 구분했다. 실행 코드·API 응답·명령·수치는 유지한다.
- `codex/clear-copy-20260911`에서 문서·API 오류·운영 안내를 간결한 한국어로 정리했다.
  기준은 9월 11일 확인한 `origin/main`의 `e2ad4b0`이다. `42fd631`은 API 오류 제목,
  메트릭·대시보드 설명과 셸 안내 문구만 바꿨다. 오류 코드·상태·처리 로직·쿼리·단위는 유지한다.
  README·PRD·ADR·운영 절차·보안 정책의 긴 문장도 나누고 추상적인 표현을 구체화했다.
- `304bd0c`에서 필수 CI를 막던 배포 의존성·이미지 취약점을 수정했다. Tomcat은 11.0.25,
  PostgreSQL의 OpenSSL은 3.5.8-r0, libuuid는 2.42.3-r1로 고정하고 gosu를 su-exec으로 교체했다.
  NGINX는 수정된 공식 다이제스트를 사용한다. cloudflared는 공식 2026.8.3 소스를
  Go 1.26.6·x/crypto 0.55.0·gRPC 1.83.1로 빌드한다. [빌드 입력](ops/cloudflared/README.md)을 참고한다.
  자체 이미지 5개의 보관·복원과 OCI·LICENSE 검증을 CI·배포 절차에 반영했다.
- `454360b`에서 V6 마이그레이션으로 점검 결과·전달 결과·전달 완료 상태의 DB 제약 3개를 보강했다.
  수정 전에는 필수 값이 `NULL`인 잘못된 조합 9가지가 저장됐다. 이제 `CASE`와 `IS TRUE`로 거부한다.
  운영 DB 적용은 미실행이다. 기존 불완전한 이력이 있으면 V6 적용이 실패하며, 이력을 자동 수정·삭제하지 않는다.
- 이전 작업 브랜치: `codex/grafana-cloud-free`. `3739149`에서
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
| 일시적 DB 장애 응답 `dc83cf7` | web 전체 46개·bootstrap 인증 20개, 총 66개 실행·통과. 실패·건너뜀 없음. 모의 DB 예외와 실제 로컬 HTTP로 503·5초 안내, 기존 500 분류, 인증 우선순위·민감정보 제외·서버 내부 재시도 없음 확인. `:bootstrap:verifyBootJarLicense` 통과, 실행 JAR의 `spring-tx 7.0.9` 확인. HTTP 오류 응답만 바꿔 전체 Java·이미지·DB 강제 중단·실제 배포 검사는 미실행 |
| 여러 대상 조회 `1baac29` | 관련 59개와 `./gradlew test` 확인. 전체 503개 중 480개 실행·변경 없는 domain 23개 결과 재사용, 최종 실패·건너뜀 없음. 첫 API 테스트의 배열 단언 2개를 수정해 해당 범위부터 재검증했다. 실제 PostgreSQL의 요청 대상 선택·중복 제거·리스 매핑과 HTTP의 입력 1~20개·최대 길이·인증 우선순위·미등록 구분 확인. 이미지·공급망·실제 BATON 연동·배포는 변경 범위 밖이거나 연결 정보가 없어 미실행 |
| 점검 진행 상태 `45e518a` | 관련 60개와 `./gradlew test` 통과. 전체 492개 중 466개 실행·앞선 web 26개 결과 재사용, 실패·건너뜀 없음. 고정 시계로 예약·리스 만료 경계, 실제 PostgreSQL의 점유·완료·비활성 전환과 HTTP 응답·인증·내부 정보 미노출 확인. 의존성·이미지 변경이 없어 이미지 빌드·공급망·배포 검사는 미실행 |
| 콜백 재시도 `ec521db` | 관련 89개와 `./gradlew test` 통과. 전체 476개 중 464개 실행·도메인 12개 결과 재사용, 실패·건너뜀 없음. 실제 로컬 HTTP의 초·날짜·잘못된 헤더, PostgreSQL의 예약 시각·재점유·페이로드 보존 확인. 마지막 한국어 오류 문구 수정은 모델 테스트 11개만 재검사. 의존성·이미지 변경이 없어 이미지 빌드·공급망·배포 검사는 미실행 |
| 추가 문구 정리 | Java 9개 파일은 주석만 변경됨을 확인. 문서의 코드 예제·기술 식별자·수치 보존, 로컬 링크·제목 참조·형식 검사 통과. 문서·주석만 변경해 Java·배포 검사는 재실행하지 않음 |
| 문구 정리 `42fd631` | web 전체 22개와 bootstrap 인증·메트릭 19개, 총 41개 테스트 통과. 실패·건너뜀 없음. 이미지 보관·공개 상태 검사 스크립트의 기존 테스트 2종 통과. Java·셸은 안내 문구만 변경, 대시보드는 제목·설명 외 쿼리·단위·설정 동일 확인. 변경 문서 링크 72개·코드 예제 보존·형식 확인. 전체 Java·이미지 빌드·실제 배포 검사는 문구 변경 범위에 해당하지 않아 미실행 |
| 보안 수정 `304bd0c` | `./gradlew test :bootstrap:verifyBootJarLicense` 통과. 447개 중 web·bootstrap 113개 실행, 나머지 334개 기존 결과 재사용. 실패·건너뜀 없음. 실제 JAR의 Tomcat 세 개 모두 11.0.25 확인 |
| 최종 배포 이미지 | linux/arm64 이미지 5개 빌드, OCI·LICENSE·cloudflared 실행·CLI/RPC 테스트 통과. 기존 정책으로 JAR·이미지 SBOM 7개에서 수정 가능한 HIGH·CRITICAL 0건. gosu 완전 삭제 후 바뀐 PostgreSQL·DB 작업 이미지 2개만 재검사하고 나머지 이미지 ID·아카이브 일치 확인 |
| 운영 검증 | Compose 정책·이미지 보관/복원·NGINX 검사 통과. 실제 PostgreSQL V1~V6 적용, 최소 권한, 비밀번호 교체·원복, WATCH 기동·재시작 통과. Docker Desktop 파일 공유 캐시 때문에 비밀 파일 덮어쓰기 대신 단계별 파일 경로를 사용하는 테스트로 수정 |
| Go 라이선스 분류 보정 | 사용하지 않는 테스트 모듈 5개 제거 후 빌드·CLI/RPC 통과. BSD 본문과 Go PATENTS를 확인한 모듈 5개는 Dependency Review 메타데이터 예외와 별도 버전·원문 체크섬 검사 적용. 정상 입력 통과, 버전·체크섬 변경 거부 확인. 최종 cloudflared 공급망 검사 통과 |
| DB 결과 정합성 `454360b` | 회귀·V5 업그레이드 테스트 11개 통과. `./gradlew test` 447개 중 159개 실행·288개 기존 결과 재사용, 실패·건너뜀 없음. 정상 이력 보존과 불완전한 이력의 적용 거부 확인 |
| 중복 코드 정리 `d6b0ca1` | 관련 테스트 69개 통과. `./gradlew test` 436개 중 424개 실행·domain 12개 기존 결과 재사용. 마지막 보조 클래스 통합 후 `:bootstrap:test` 91개 재검증 통과. 실패·건너뜀 없음. 메트릭 시작·기록 실패 시 전달 성공 유지, 리다이렉트 후 오류의 시간·횟수 보존 확인 |
| 이전 문구 정리 | 대시보드 JSON 정상, 제목 외 쿼리·단위·설정 동일 확인. 변경 문서 링크·형식과 `git diff --check` 통과. 문구만 바뀌어 Java·PromQL·배포 검사는 반복하지 않음 |
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
일시적 장애 응답은 `./gradlew :adapter-in-web:test :bootstrap:test --tests '*MonitorApiSecurityIntegrationTest' :bootstrap:verifyBootJarLicense`로 검증했다.
로그는 `.gradle/agent-validation/20260911T224050182451Z-api-temporary-failures/`에 있다.
검증한 코드·의존성 선언은 `dc83cf7`과 같고 이후 변경은 문서뿐이다.
변경 문서 4개의 로컬 링크 58개·API JSON 예제 7개와 형식 검사도 통과했다.
묶음 조회 로그는 `.gradle/agent-validation/20260911T222255884891Z-batch-query-targeted/`,
`20260911T222331145986Z-batch-query-response-tests/`, `20260911T222352483361Z-batch-query-full/`에 있다.
뒤 경로도 같은 로그 디렉터리 아래다. 전체 검사 중 문서 5개를 수정했으나 코드·테스트·설정은
변경하지 않았다. 문서만 검사 시작 버전으로 치환해 재계산한 전체 파일 지문이 시작 기록과
일치하며, 근거는 마지막 디렉터리의 `source-reuse.json`에 있다. 검증한 코드는 `1baac29`와 같다.
변경 문서 6개의 로컬 링크 59개·API JSON 예제 6개와 형식 검사도 통과했다.
점검 진행 상태 로그는 `.gradle/agent-validation/20260911T221018717905Z-check-progress-targeted/`와
`20260911T221049789749Z-check-progress-full/`에 있다. 뒤 경로도 같은 로그 디렉터리 아래에 있으며
각 `result.json`에 명령·기준 리비전·파일 지문을 기록했다. 검증한 코드는 `45e518a`와 같고,
이후 변경 문서 6개의 로컬 링크 58개와 형식 검사를 통과했다. 코드 검증은 재실행하지 않았다.
콜백 재시도 로그는 `.gradle/agent-validation/20260911T215456538064Z-callback-retry-after-targeted/`,
`20260911T215700426341Z-callback-retry-after-full/`, `20260911T215917253215Z-callback-retry-after-model-final/`에 있다.
뒤의 두 경로도 같은 로그 디렉터리 아래에 있으며 각 `result.json`에 명령·리비전·파일 지문을 기록했다.
이후 변경은 README·PRD·ADR·HANDOFF뿐이며 위 코드 검증 결과를 재사용한다.
추가 문구 검사는 `a71c1ad`와 비교했으며 `.gradle/agent-validation/`의 `clear-copy-followup` 기록에
검증 명령·파일 지문·종료 코드를 남겼다.
앞선 API 문구 변경은 `./gradlew :adapter-in-web:test :bootstrap:test --tests '*MonitorApiSecurityIntegrationTest' --tests '*MonitoringMetricsTest'`로 검증했다.
로그는 `.gradle/agent-validation/20260911T141447754615Z-clear-copy-api/`에 있다.
운영 스크립트 로그는 `20260911T141443213796Z-clear-copy-image-messages/`와
`20260911T141443256900Z-clear-copy-public-messages/`, 동작 보존 확인은
`20260911T141428892410Z-clear-copy-structure/`에 있으며 모두 같은 로그 디렉터리 아래에 있다.
이후 문서 수정에는 링크·형식 검사만 적용하고 위 코드 검증 결과를 재사용한다.
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

## 검증 환경

- 기본·번들 Python에는 `yaml`이 없다. YAML 검사는 PyYAML 6.0.3이 설치된
  `/private/tmp/baton-watch-skill-validation-20260905/bin/python`으로 통과했다. 다음 사용 전 경로 존재 여부를 확인한다.
- `actionlint`·ShellCheck는 PATH에 없었다. 로컬에서는 YAML 구문과 등록 명령을 확인했다.
  원격 CI 결과는 아래 병합 기록에 별도로 남겼다.

## 차단 상태와 다시 확인할 조건

| 항목 | 마지막 확인과 다음 조건 |
| --- | --- |
| WATCH 원격 병합 | 9월 8일 [필수 CI](https://github.com/ljkhyeong/baton-watch/actions/runs/34228797671) 통과 후 PR #37을 `main`에 병합했다. 병합 커밋은 `e2ad4b0`이며 당시 로컬·원격 main이 일치함을 확인했다 |
| 공식 cloudflared 후보 | 2026.8.3 공식 이미지에는 필요한 의존성 수정이 없어 공식 소스를 패치 버전의 의존성으로 빌드한다. 필요한 수정이 포함된 공식 이미지가 나오면 같은 공급망 검사를 통과한 뒤 별도 빌드를 제거할 수 있다 |
| 공식 이미지 패치 확인 | 기존 `MVP 이후 우선순위 정리` 작업에 매일 오전 9시 확인이 설정돼 있음. 새 자동화 추가 전 기존 설정을 조회하며, 같은 후보의 다운로드·검사를 중복 수행하지 않음 |
| 공개 스테이징 | `watch-staging.b4ton.com` DNS·스테이징 환경과 실제 연동 설정이 준비되면 [공개 검증 절차](docs/runbooks/baton-resource-health-verification.md) 재개 |
| BATON 원격 `main` | 9월 8일 `a9d1feb9` 병합·푸시와 원격 참조 확인. 최신 화면·문구와 WATCH 상태 조회·재점검을 함께 유지. 원본 체크아웃의 별도 작업은 보존 |

현재 상태 재확인 요청이 없다면 위 조건이 그대로인 작업은 재시도하지 않는다.
이 기록은 당시 확인 결과이며 현재 원격 상태나 배포 완료를 보장하지 않는다.

## 구현과 후속 작업

- 대상 GET은 헤더만 확인하고 본문을 읽지 않는다. 인증된 수동 재점검 API는 기존 일정·리스를 유지하며
  새 예약에 30초 간격을 적용한다. V5 마이그레이션이 필요하다.
- BATON 자료 상태 표시·재점검과 WATCH 독립 복원은 [연동 확인](docs/runbooks/baton-integration-review.md),
  [스냅샷 복원](docs/runbooks/baton-snapshot-recovery.md)에 구현·검증 범위를 기록했다.
- 공개 배포에는 이그레스 정책, 지원 규모·SLO, 공개 HTTPS 제한 검증, 대시보드·알림,
  암호화 백업·보존·복구 담당자와 Cloudflare·BATON 설정이 필요하다.
  [스테이징 배포](docs/runbooks/staging-deployment.md)와 [공개 이벤트 전달](docs/runbooks/public-staging-event-delivery.md) 절차를 따른다.
- 과거 커밋별 검증과 판단 근거는 [이전 인계 기록](docs/history/handoff-2026-09-05.md)에 보관했다.
