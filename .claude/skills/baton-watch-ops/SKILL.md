---
name: baton-watch-ops
description: BATON WATCH의 작업자 스케줄·실행 예산·종료 처리, 런타임 설정(`WatchProperties`·환경 변수), Dockerfile·Compose·스테이징 스크립트, 공급망 검사, 백업·복구와 배포 절차를 바꿀 때 사용한다. 외부 HTTP 요청 정책 자체는 baton-watch-outbound-http를 사용한다.
---

# BATON WATCH 운영

작업자 동작은 [PRD-0003 일정 및 실행](../../../docs/PRD/0003_monitoring-mvp/spec.md)·[PRD-0004 이벤트 저장과 전달 처리](../../../docs/PRD/0004_health-change-event-delivery/spec.md),
Compose·배포는 [스테이징 절차](../../../docs/runbooks/staging-deployment.md)의 관련 부분을 확인한다.
외부 요청의 URL·DNS·시간 제한은 [외부 통신](../baton-watch-outbound-http/SKILL.md) 스킬을 사용한다.

## 위치

| 역할 | 위치 |
| --- | --- |
| 작업자·유지 관리 | `bootstrap/.../MonitoringScheduler`, `EventDeliveryScheduler`, `*MaintenanceScheduler`, `WorkerSchedulingConfiguration` |
| 실행 예산·종료 대기 | `bootstrap/.../WorkerExecutionBudget` |
| 설정·안전 기본값 | `WatchProperties`, `EventDeliveryProperties`, `PersistenceProperties`, `DatabaseRuntimeProperties`, `RuntimeSafetyEnvironmentPostProcessor`, `bootstrap/src/main/resources/application.yml` |
| 컨테이너 | `Dockerfile`, `compose.yml`, `compose.staging*.yml`, `.env.example`, `ops/staging.env.example` |
| 운영 스크립트 | `ops/staging-*.sh`, `ops/scan-supply-chain.sh`, `ops/verify-runtime-images.py`, `ops/check-runtime-licenses.py` |
| 스크립트 테스트 | `ops/tests/*-test.sh`, `ops/tests/*-test.py` |

## 규칙

- 종료 시 새 점유를 중단하고 실행 중 작업을 제한 시간 안에 마친다. 배치 허용 실행 시간은 종료 대기보다 짧게 유지해 중단된 작업을 만료 리스로 복구할 수 있게 한다. 배치·큐·동시성·리스와 종료 대기의 관계를 바꾸면 `WorkerExecutionBudgetTest`를 함께 고친다.
- 설정 상한과 안전 기본값은 비활성화할 수 없다. 새 설정은 시작 시 검증하고, 오류 메시지·로그에 비밀값·URL 원문을 넣지 않는다. README 설정 설명과 예시 env 파일을 함께 맞춘다.
- 콜백은 모니터 API와 별도 Bearer 토큰을 사용한다. 비밀값을 Git·이미지·Compose 기본값·로그·URL·명령 인자에 넣지 않고 환경 변수나 비밀 파일로 전달한다.
- 이미지 다이제스트 고정, 비공개 앱·DB·관리 포트, 상태 확인, 비루트·최소 권한과 롤백 절차를 유지한다.
- 운영 스크립트는 실패를 그대로 전파한다. 결과 파일이 없거나 비어 있는 경우, 입력 JSON의 중복 필드처럼 성공으로 오판할 수 있는 입력은 실패로 처리하고 후속 요청을 중단한다.
- 배포·DNS·방화벽·외부 서비스 변경은 사용자가 승인한 범위에서만 수행한다. 직접 확인한 결과와 남은 작업을 구분해 보고한다.

## 검증

- Compose를 바꾸면 변경한 파일 조합으로 `docker compose ... config`를 실행하고 `./ops/tests/staging-compose-policy-test.sh`로 정책을 확인한다.
- 셸은 `bash -n`과 ShellCheck, 해당 `ops/tests` 검사를 실행한다. 로컬에 ShellCheck가 없으면 HANDOFF의 검증 환경 기록을 확인하고 CI 결과로 대신한다고 보고한다.
- 이미지 변경은 빌드와 공급망 검사, 실행 방식 변경은 시작·상태·종료, 복구 절차 변경은 실제 복원을 검증한다.
- 작업자·리스·종료 동작을 바꾸면 관련 단위 테스트 뒤 부하·복구 작업을 고른다.

```bash
python3 ops/run-validation.py run --label <주제>-workers -- ./gradlew :bootstrap:test --tests '*Worker*'
python3 ops/run-validation.py run --label <주제>-recovery -- ./gradlew :adapter-out-persistence:processRecoveryTest
python3 ops/run-validation.py run --label <주제>-runtime-load -- \
  ./gradlew :bootstrap:runtimeLoadTest -PwatchRuntimeLoadMonitors=25
```

공급망 검사는 이미지 다이제스트·플랫폼·Trivy 버전·취약점 DB가 같으면 기존 보고서부터 읽고 다시 실행하지 않는다.
