# WATCH 독립 복원 후 BATON 스냅샷 대조와 재전송

WATCH DB만 과거로 복원하면 BATON에서 이미 전달 완료한 모니터가 누락되거나 예전 리비전으로
남을 수 있다. BATON의 정기 보정은 원본 자료와 자체 아웃박스를 비교하므로 이 차이를 자동으로
찾지 못한다. 이 절차는 BATON MySQL의 자료별 마지막 **불변 아웃박스**를 내보내고 WATCH와 대조한다.

## 준비와 범위

- `python3`, `curl`, BATON MySQL 읽기 권한과 WATCH API 토큰을 준비한다.
- BATON 원본과 아웃박스 보정을 먼저 끝내고, 자료 변경을 멈춘 복원 작업 시간에 최신 파일을 내보낸다.
  자료 URL이나 감시 상태를 직접 재구성하거나 새 리비전을 발급하지 않는다.
- 대상은 설정된 소스 이름공간 하나다. BATON DB 자체가 과거로 복원되어 WATCH 리비전이 더 높다면
  이 도구는 이를 덮어쓰지 않는다. 두 서비스의 복원 지점과 BATON 원본부터 확인한다.
- 입력은 최대 10000개·32MiB다. 빈 파일, 잘못된 행, 중복 참조와 다른 이름공간이 하나라도 있으면
  모든 HTTP 호출 전에 중단한다. 파일을 나눌 때도 전체 참조를 빠짐없이 처리한 증거를 남긴다.
- 토큰과 스냅샷은 현재 사용자가 소유한 0600 이하 권한의 일반 파일이어야 한다. 심볼릭 링크는
  허용하지 않는다. 스냅샷에는 원본 URL이 있으므로 Git·로그·공개 CI 산출물에 올리지 않는다.
- 토큰은 명령 인자와 오류에 노출하지 않고 `curl` 표준 입력으로만 전달한다. 출력에는 자료 참조,
  리비전과 안정적인 결과 코드만 남기며 토큰·원본 URL·원격 오류 본문은 출력하지 않는다.

## 1. 최신 불변 스냅샷 내보내기

BATON DB 접속 정보는 소유자 전용 MySQL 설정 파일에 둔다. 다음 예제의 파일 경로·DB 이름은
자신의 환경에 맞춘다. `--defaults-extra-file`은 다른 MySQL 옵션보다 먼저 쓴다.

```bash
umask 077
mysql --defaults-extra-file=/secure/baton-mysql.cnf \
  --batch --raw --skip-column-names baton \
  < ops/export-baton-watch-snapshots.sql \
  > /secure/baton-watch-snapshots.jsonl
```

SQL은 일관된 읽기 전용 트랜잭션에서 자료별 가장 높은 아웃박스 ID만 내보낸다. 전달 상태와
무관하게 확정된 최신 스냅샷을 사용하므로 `INACTIVE` 보상도 보존한다. 원본·아웃박스·이벤트를
갱신하거나 삭제하지 않는다. 내보내기가 실패하면 해당 파일을 사용하지 않는다.

## 2. 변경 없이 대조

```bash
python3 ops/reconcile-baton-snapshots.py \
  --snapshots /secure/baton-watch-snapshots.jsonl \
  --source-namespace study-pilot \
  --origin https://watch.example.com \
  --token-file /secure/watch-api-token
```

첫 줄에 파일의 `manifestSha256`과 건수를 출력하고 항목별 결과를 즉시 남긴다.

| 결과 | 의미와 다음 행동 |
| --- | --- |
| `MISSING` | WATCH 모니터 없음. 같은 스냅샷으로 복구 가능 |
| `REMOTE_BEHIND` | WATCH 리비전이 낮음. 확정 스냅샷으로 복구 가능 |
| `REVISION_MATCH_UNVERIFIED` | 리비전·감시 상태 일치. GET에 URL이 없어 전체 본문 일치는 아직 미확인 |
| `REMOTE_AHEAD` | WATCH 리비전이 높음. 복원 지점·현재 BATON 원본 확인, 자동 재기준화 금지 |
| `PAYLOAD_CONFLICT` | 같은 리비전의 감시 상태 또는 PUT 본문 충돌. 원본과 복원 지점 확인 |
| `LOOKUP_FAILED`·`LOOKUP_OR_REPLAY_FAILED` | 인증·HTTP 계약·DNS·네트워크 실패 등으로 확인 불가 |

대조의 종료 코드 0은 모든 항목의 **리비전·감시 상태 일치**만 뜻한다. URL까지 확인했다는
의미가 아니다. 조치가 필요한 항목이 있으면 2, 입력 또는 준비 오류는 1이다.

## 3. 검토한 파일 그대로 재전송

위에서 확인한 해시를 다음 인자로 넣는다. 파일이 바뀌면 통신 전에 중단한다.

```bash
python3 ops/reconcile-baton-snapshots.py \
  --snapshots /secure/baton-watch-snapshots.jsonl \
  --source-namespace study-pilot \
  --origin https://watch.example.com \
  --token-file /secure/watch-api-token \
  --apply --expected-sha256 '<대조 결과의 manifestSha256>'
```

각 항목을 다시 GET한 뒤 누락·낮은 리비전·리비전 일치 항목에만 원래 본문과 리비전을 PUT한다.
정확한 200 응답의 자료 참조·리비전·감시 상태까지 일치하면 `REPLAYED`다. 같은 리비전의 PUT은
WATCH가 원본 URL의 동일성까지 확인하며, 다른 URL이면 409로 거절한다. 대조 뒤 새 리비전이
도착한 경합도 409로 보고한다. 기존 점검 결과나 리스를 초기화하려고 리비전을 올리지 않는다.

전체 `REPLAYED`이면 종료 코드 0, 충돌·확인 불가·`REPLAY_FAILED`가 있으면 2다. 한 항목의
실패 후에도 나머지를 확인하며 자동 재시도는 하지 않는다. 재실행은 멱등하다. BATON의 아웃박스
전달 상태는 변경하지 않으므로 기존 작업자가 같은 스냅샷을 다시 보낼 수도 있다.

## 통신 제한과 검증 한계

- 기본 HTTPS 443의 DNS 오리진만 사용한다. 매 요청 전에 DNS를 최대 3초 안에 해석하고
  사설·루프백·멀티캐스트 등 비공개 주소가 섞이면 거절한다. 승인한 주소를 고정하며
  원래 호스트에 대한 TLS 인증을 유지한다. 프록시·리디렉션·인증서 검증 우회는 사용하지 않는다.
- 직렬 요청과 최소 0.5초 간격을 사용한다. 연결 3초·HTTP 전체 10초·응답 8KiB로 제한한다.
- 이 절차는 모니터 목표 스냅샷을 복구한다. 과거 점검 이력이나 유실된 상태 이벤트를 재생성하지 않는다.
- WATCH에는 전체 참조 열거 API가 없어 WATCH에만 남은 참조를 탐지하거나 삭제하지 못한다.
  BATON 원본 복원 지점이 확정되지 않았거나 최신 아웃박스가 누락됐다면 전체 정합성을 주장할 수 없다.
- 로컬 회귀 검증은 다음 명령으로 실행한다. 실제 운영 DB 복원과 공개 HTTPS 전달 성공을 대신하지 않는다.

```bash
python3 ops/tests/baton-snapshot-recovery-test.py
```
