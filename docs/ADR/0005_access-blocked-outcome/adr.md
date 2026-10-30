# ADR-0005: 자동 점검 차단 응답의 결과 분류

상태: 제안됨. BATON이 새 결과 값을 받아들인 뒤 채택한다.

날짜: 2026-10-16

## 배경

고정 `User-Agent`([ADR-0002](../0002_monitoring-mvp-storage-and-execution/adr.md)의 외부 요청 식별자)를 보낸 뒤에도
Cloudflare 관리형 챌린지는 자동 요청에 403을 반환한다. 2026-10-16 확인에서 Stack Overflow·npm은
`403`과 `cf-mitigated: challenge`를, Medium은 이 헤더 없이 Cloudflare `403`을 반환했다.
현재는 이 응답이 `HTTP_CLIENT_ERROR` 확정 실패라 세 번 뒤 `BROKEN`이 되고, 브라우저에서 열리는 링크가 BATON에
깨진 링크로 표시된다.

BATON은 `lastOutcome`을 `WatchCheckOutcome` enum으로 엄격하게 읽고, 모르는 값이면 그 자료를 조회 불가로
처리한다(`RestClientWatchInspectionClient`, 2026-10-16 BATON `main`). WATCH가 새 값을 먼저 보내면 해당 자료의
상태 표시가 사라지므로 WATCH만 먼저 바꿀 수 없다.

## 제안

- 결과 분류 `ACCESS_BLOCKED`를 추가한다. 최종 응답이 403이고 `cf-mitigated: challenge` 헤더가 정확히 하나일 때만
  쓰며 상태 코드 403을 저장한다. 헤더가 없는 403과 다른 봇 차단 서비스는 근거 없이 대상 실패를 숨기지 않도록
  `HTTP_CLIENT_ERROR`로 둔다.
- 확정하지 않는 결과로 다뤄 상태·연속 실패 수·`lastConclusiveAt`을 바꾸지 않는다. 차단이 계속되면 기존 규칙대로
  10분 뒤 `UNKNOWN`이 된다. 차단은 곧 풀리지 않으므로 내부 실패의 30초 재시도 대신 일반 점검 간격을 쓴다.
- 대상이 응답한 결과로 분류해 외부 연결 장애 판정과 `WatchCheckEgressFailure` 경보에서 응답 수신으로 센다.
- 결과 값 목록을 가진 DB CHECK 제약은 적용된 V1을 고치지 않고 Flyway V2로 넓힌다. API `lastOutcome`과 메트릭
  레이블 `access_blocked`에 값을 더한다. 브라우저 가장이나 챌린지 풀이는 하지 않는다.

## 배포 순서와 BATON 변경

1. BATON이 `WatchCheckOutcome`에 `ACCESS_BLOCKED`와 화면 문구(예: 자동 점검이 차단된 사이트, 브라우저로 확인 필요)를
   추가해 먼저 배포한다. 모르는 결과 값을 조회 불가 대신 일반 실패 문구로 표시할지도 함께 정한다.
2. WATCH가 V2 마이그레이션과 새 분류를 배포한다. 순서가 바뀌면 해당 자료가 BATON에서 조회 불가가 된다.
3. 이벤트 콜백 본문에는 결과 분류가 없으므로 전달 계약은 바꾸지 않는다.

## 결과

챌린지를 반환하는 사이트는 `BROKEN` 대신 `UNKNOWN`으로 남아 깨진 링크 오탐이 줄지만, WATCH로는 계속 확인할 수
없다. 이 결정은 BATON 변경과 함께 채택하며 그전까지 WATCH 동작은 바꾸지 않는다.
