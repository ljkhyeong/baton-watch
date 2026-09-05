# BATON 연동 구현 확인과 복구 조건

확인일: 2026-09-05

이 문서는 로컬 BATON 소스를 읽어 확인한 결과다. 운영 활성화나 공개 콜백 시험의
증거가 아니다. 기준 커밋은 BATON `5f8c45c9071c2fa1847751a234bf98a468e2aaa1`이며,
확인한 WATCH 연동 파일에는 로컬 변경이 없었다. BATON 저장소는 수정하지 않았다.

## 이미 구현된 범위

- BATON 원본 변경과 같은 트랜잭션의 불변 아웃박스, 커밋 후 비동기 전달,
  재시도와 만료 리스 복구가 있다.
- 원본 자료·시즌 상태와 자체 아웃박스의 누락을 보정하는 정기 작업이 있다.
  유효하지 않은 `ACTIVE` 요청에는 더 높은 리비전의 `INACTIVE` 보상을 기록한다.
- 상태 콜백은 이벤트 ID와 봉투를 트랜잭셔널 수신함에 저장한 뒤 202로 응답한다.
  동일 재전송과 ID 충돌을 구분하며 서로 다른 이벤트는 순서와 관계없이 보존한다.
- 상태 프로젝션과 UI는 아직 없다. 수신 순서대로 최신 상태를 덮어쓰는 경로도 없으므로
  현재 수신 계약을 바꾸는 `healthRevision` 필드 추가는 이번 변경에 포함하지 않는다.

근거:

- [BATON 연동 계약](https://github.com/ljkhyeong/baton/blob/5f8c45c9071c2fa1847751a234bf98a468e2aaa1/docs/PRD/0004_watch-integration-contract/spec.md)
- [동기화 전달 서비스](https://github.com/ljkhyeong/baton/blob/5f8c45c9071c2fa1847751a234bf98a468e2aaa1/application/src/main/java/com/personal/baton/application/watch/WatchMonitorOutboxDispatchService.java)
- [정기 보정 서비스](https://github.com/ljkhyeong/baton/blob/5f8c45c9071c2fa1847751a234bf98a468e2aaa1/application/src/main/java/com/personal/baton/application/watch/WatchMonitorReconciliationService.java)
- [상태 이벤트 수신함](https://github.com/ljkhyeong/baton/blob/5f8c45c9071c2fa1847751a234bf98a468e2aaa1/adapter-out-persistence/src/main/java/com/personal/baton/adapter/out/persistence/watch/JdbcWatchHealthEventInboxAdapter.java)
- [순서가 바뀐 이벤트의 보존 테스트](https://github.com/ljkhyeong/baton/blob/5f8c45c9071c2fa1847751a234bf98a468e2aaa1/application/src/test/java/com/personal/baton/application/watch/WatchHealthEventInboxPersistenceTest.java)

## 상태 표시 기능을 추가할 때의 인수 조건

`eventId`는 중복 제거용이며 `sourceRevision`은 BATON 스냅샷 번호다. 두 값 모두
동일 스냅샷에서 발생한 여러 상태 변경의 선후 관계를 정하지 않는다. `changedAt`만
비교하는 방식도 같은 시각이나 인스턴스 시계 편차의 처리를 먼저 정해야 한다.

1. 같은 원본 리비전에서 장애·복구 이벤트가 역순으로 도착해도 복구 상태가 과거로
   되돌아가지 않는지 검증한다. 기존 계약에서는 이벤트를 재조회 신호로 사용하고
   BATON이 인증된 WATCH GET으로 비동기 보정하는 방안을 우선 검토한다.
2. 겹친 GET의 응답 순서가 바뀌어도 오래된 응답을 나중에 적용하지 않도록 리소스별
   갱신을 직렬화하거나 별도의 적용 세대를 사용한다. 워크스페이스 요청 안에서 WATCH를 기다리지 않는다.
3. 이벤트만으로 최신 상태를 선택해야 한다면 모니터별 단조 증가 상태 번호를
   WATCH 프로젝션·이벤트와 BATON 소비자에 함께 도입하는 새 계약을 채택한다.
   현재 BATON 수신 DTO는 계약 밖 필드를 거부하므로 송신자만 먼저 변경하지 않는다.
4. 원본 리비전 변경·비활성화·삭제와 지연 이벤트가 교차해도 BATON 인가나 자료의
   존재를 바꾸지 않는다. GET 404·조회 실패·오래된 결과는 비권위 UNKNOWN으로 처리한다.

## 동기화 장애와 독립 복원

일반 전송 실패는 기존 아웃박스 재시도로 복구한다. 인증·경로 설정 오류와 리비전
충돌은 동일하게 취급하지 않으며 BATON의 실패 코드와 재시작 복구 정책을 따른다.
원본 변경, `INACTIVE`, 응답 유실 후 같은 리비전 재전송은 공개 연동 시험에 포함한다.

정기 보정은 BATON의 원본과 자체 아웃박스를 비교하며 WATCH의 전체 상태를 조회하지
않는다. WATCH DB만 과거로 복원했는데 BATON 아웃박스가 이미 전달 완료라면 이 작업만으로
유실 모니터가 다시 만들어진다고 보장할 수 없다. 독립 복원 때는 다음을 별도로 수행해야 한다.

- BATON의 참조 목록을 기준으로 WATCH 개별 GET을 제한된 속도로 조회한다.
- 누락·낮은 리비전은 원본에서 확정한 스냅샷을 재전송한다. 더 높은 WATCH 리비전과
  동일 리비전의 다른 페이로드는 자동 덮어쓰기하지 않고 복원 지점을 확인한다.
- 자료의 활성·비활성 상태까지 대조하며 미전달 이벤트는 삭제하지 않는다.

이 독립 복원 대조·재전송 도구는 이번 WATCH 변경에 구현하지 않았다. 실제 운영 복원은
두 서비스의 일관된 복원 지점 또는 별도로 검증한 대조 절차가 필요하다.

## 이번 WATCH 계약 변경의 전달 사항

대상 GET은 큰 본문을 내려받지 않고 헤더로 판정한다. BATON 기준 문서에 남은
64 KiB 본문 때문에 정상 문서가 실패할 수 있다는 설명은 WATCH의 이전 동작을 뜻한다.
새 비동기 재점검 API는 [WATCH API 계약](../PRD/0002_api-contract/spec.md)을 따르며,
BATON 최종 사용자 권한 확인과 UI 연결은 별도 구현 범위다.
