# BATON 상태 표시와 독립 복원 구현 확인

확인일: 2026-09-05

BATON `72eede3ad8d5db29ce74755aed1cffc2abb90813` (`codex/watch-state-recovery`)에 역할 자료 상태 조회와 수동 재점검 UI를 추가했다.
WATCH에는 최신 불변 BATON 스냅샷의 대조·재전송 도구를 추가했다. 아래 내용은 저장소 구현과
로컬 검증 결과이며 공개 운영 활성화나 실제 콜백 전달의 증거가 아니다.

## 역할 자료 상태 표시

BATON은 원본 변경과 같은 트랜잭션의 불변 아웃박스, 별도 비동기 전달·재시도·만료 리스 복구,
원본과 자체 아웃박스의 정기 보정, 상태 이벤트의 불변 인박스를 유지한다.

새 화면은 이벤트를 상태 순서대로 재생하지 않는다. 워크스페이스 프로젝션과 별도 GET으로
WATCH의 현재 상태를 조회하고 다음 경계를 적용한다.

- 조회 전후에 팀·시즌·자료 소유권·공유 키와 최신 아웃박스의 URL을 확인한다.
  네트워크를 기다리는 동안 DB 트랜잭션을 유지하지 않는다.
- 원격 리비전·감시 상태 불일치 또는 누락은 `UNKNOWN/PENDING`, 조회 장애와 동시성 한도는
  `UNKNOWN/UNAVAILABLE`, 5분 이상 오래되거나 미래의 점검은 `UNKNOWN/STALE`이다.
- 보관 자료·종료 시즌·연동 또는 감시 중지·비활성 최신 스냅샷은 점검 대상에서 제외한다.
  WATCH 상태가 BATON 자료의 존재나 접근 권한을 결정하지 않는다.
- 별도 클라이언트는 조회·재점검을 합쳐 동시 4건·대기열 없음, 연결 1초·읽기 2초,
  응답 8KiB와 리디렉션 금지를 사용한다.
- 열린 역할 상세는 자료별 상태·최근 점검 시각·다시 점검 버튼을 표시하며 30초마다
  조회한다. 백그라운드 주기 조회는 하지 않는다. React Query의 범위별 캐시와 요청 중단을 사용한다.
- 수동 재점검은 현재 원격 리비전과 자료를 다시 확인해 동기화가 덜 된 요청을 보류한다.
  접수와 완료를 구별하고 WATCH의 429 대기 초를 전달한다. GET과 POST 사이의 동시 변경을
  원자적으로 막는 새 계약은 아니며 원본 아웃박스가 최종 감시 목표로 수렴시킨다.

`eventId`는 중복 제거용이며 `sourceRevision`은 BATON 목표 스냅샷 번호다. 어느 것도 같은
스냅샷에서 발생한 상태 변경의 순번으로 사용하지 않는다. 인박스는 고유 이벤트를 계속 보존하고
UI는 이 인박스를 적용하지 않으므로 역순 이벤트로 최신 상태를 덮지 않는다. `healthRevision`
필드나 별도 상태 저장 테이블은 추가하지 않았다.

구현 근거:

- [BATON 상태 조회 결정](https://github.com/ljkhyeong/baton/blob/72eede3ad8d5db29ce74755aed1cffc2abb90813/docs/ADR/0021_watch-current-state-query/adr.md)
- [BATON 상태 조회 서비스](https://github.com/ljkhyeong/baton/blob/72eede3ad8d5db29ce74755aed1cffc2abb90813/application/src/main/java/com/personal/baton/application/workspace/WorkspaceResourceHealthService.java)
- [BATON 자료 상태 화면](https://github.com/ljkhyeong/baton/blob/72eede3ad8d5db29ce74755aed1cffc2abb90813/frontend/src/features/watch/ResourceHealthStatus.tsx)

## WATCH 독립 복원

BATON의 정기 보정은 자체 아웃박스와 원본을 비교한다. WATCH DB만 과거로 복원해 이미 전달한
모니터가 사라져도 정기 보정만으로는 이를 알 수 없으므로
[불변 스냅샷 대조·재전송 절차](baton-snapshot-recovery.md)를 사용한다.

`ops/export-baton-watch-snapshots.sql`은 BATON MySQL에서 자료별 마지막 불변 아웃박스를 읽기
전용으로 내보낸다. `ops/reconcile-baton-snapshots.py`는 기본 조회 모드로 차이를 보고하고,
검토한 파일 해시를 지정한 재전송 모드에서 같은 원본 리비전과 본문을 PUT한다.
더 높은 원격 리비전이나 같은 리비전의 다른 본문은 자동으로 덮어쓰지 않는다.
BATON 원본·아웃박스 전달 상태·WATCH 미전달 이벤트를 삭제하거나 직접 수정하지 않는다.

GET이 URL을 노출하지 않으므로 리비전 일치만으로 전체 본문 일치를 주장하지 않는다.
같은 리비전의 PUT 200까지 확인해야 URL 일치가 검증된다. WATCH에만 남은 참조나 유실된
과거 이력·이벤트를 복원하는 도구는 아니다.

## 검증과 배포 순서

BATON 전체 일반 테스트 726개와 REST Docs 159개가 통과했고, 이후 원격 리비전 보강의 관련
테스트를 다시 통과했다. 프런트 빌드·생성 API 계약·데스크톱/모바일/WebKit 9개 검증도 통과했다.
WATCH 복구 도구 회귀 7개와 실제 MySQL V16 스키마의 최신 두 스냅샷 내보내기·원본 세 행
불변 검증을 통과했다. 모의 HTTP 응답과 폐기 가능한 로컬 DB를 사용한 결과다.

WATCH 재점검 API와 본문 없는 대상 GET은 `3a04e7b`부터 제공한다. BATON 상태 UI를 활성화할
환경에는 이 계약을 먼저 배포하고 서비스 전용 토큰을 설정한다. BATON에 남아 있던 64KiB
본문 때문에 정상 문서를 실패로 볼 수 있다는 문구도 최신 동작으로 수정했다.
공개 HTTPS, 실제 자격 증명, 콜백 중복 전달·적체 해소와 운영 복원 훈련은 별도 확인이 필요하다.
