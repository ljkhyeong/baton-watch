# BATON WATCH 작업 지침

## 작업 방식

- 사용자 요청은 이 문서와 스킬의 일반 작업 지침보다 우선한다. 요청 범위 안의 구현·수정·검증은 진행하고, 필수 정보가 없거나 승인되지 않은 외부 작업이 필요할 때만 확인한다. 지시 때문에 멈추면 해당 파일과 문구, 적용 이유를 밝힌다.
- `README.md`와 `HANDOFF.md`의 관련 부분을 확인하고, 계약·설계 변경에 필요한 PRD·ADR만 읽는다. 구현은 코드·테스트, 요구사항은 PRD, 설계 결정은 ADR, 현재 인계 상태는 HANDOFF에서 확인한다.
- 응답·문서·PR 리뷰·주석·커밋은 실무에서 쓰는 간결한 한국어로 작성한다. 수정하는 문장의 영문도 한글로 정리하되 기술 식별자·공식 명칭과 계약·수치·예외의 의미는 유지한다.
- 기존 작업을 보존하고 요청과 무관한 변경은 추가하지 않는다. 리팩터링에서 같은 경계의 중복은 줄이되 인증·DB 제약·리스 경합 검증처럼 서로 다른 경계의 검증은 유지한다.

## 서비스 규칙

- WATCH는 BATON `RoleResource` URL을 비동기로 점검한다. 자료와 접근 권한은 BATON이 관리하며, BATON 커밋 이후 동기화한다. BATON 트랜잭션·프로젝션이 WATCH를 기다리게 하지 않는다.
- `com.personal.baton.watch`의 의존성은 `bootstrap -> adapters -> application -> domain`이다. 불변식·값 타입은 `domain`, 유스케이스·포트는 `application`, HTTP·DTO는 `adapter-in-web`, DB는 `adapter-out-persistence`, 외부 통신은 `adapter-out-external`, Spring 조립·설정은 `bootstrap`에 둔다.
- 시간 판단에는 주입한 `Clock`과 UTC를 사용한다. 점검·전달은 호출 직전 한 건씩 짧은 트랜잭션에서 점유한다. 트랜잭션 밖에서 DNS·HTTP 통신을 하고 별도 짧은 트랜잭션에서 완료한다. 만료 리스로 복구하고 같은 완료 요청은 중복 처리하지 않는다.
- 모든 외부 요청은 스킴·포트와 DNS 결과를 검증하고 공개 주소에만 연결한다. 검증한 IP에 연결을 고정해 DNS 리바인딩을 막고, 자격 증명이 포함된 URL·모호한 호스트·대체 IP 표기를 거부한다. 시간·헤더·바이트·동시성·큐 제한을 유지한다.
- 대상 GET은 리다이렉트마다 재검증하고 응답 헤더를 받으면 본문을 읽거나 비우지 않고 연결을 닫는다. 응답 본문·자격 증명·쿠키·인가 헤더를 저장하거나 기록하지 않는다.
- 상태 변경은 고정된 BATON HTTPS 콜백 하나로 최소 한 번 전달한다. 페이로드는 변경하지 않고 리다이렉트를 따르지 않는다. 재시도는 상한을 둔 지수 백오프를 사용한다. 미전달 이벤트는 보존하고 보존 기간이 지난 전달 완료 이벤트만 삭제한다. BATON은 이벤트 ID로 중복 처리를 막는다.
- 메트릭 레이블에는 URL·호스트·IP·리소스 참조·이벤트 ID·예외 메시지 등 값의 종류가 계속 늘어나는 정보를 넣지 않는다. 배포·외부 알림의 가동 여부는 직접 확인한 범위만 보고한다.

## 스킬 선택

작업에 해당하는 스킬만 읽는다. 일반 Java 변경은 위 규칙을 적용한다.

- HTTP 경로·DTO·인증·오류 응답: [API 계약](.agents/skills/baton-watch-api-contract/SKILL.md)
- SQL·마이그레이션·리스·이벤트 저장: [영속성](.agents/skills/baton-watch-persistence/SKILL.md)
- 외부 통신·작업자·Compose·배포: [운영](.agents/skills/baton-watch-ops/SKILL.md)
- 로그·메트릭·대시보드·경보: [관측성](.agents/skills/baton-watch-observability/SKILL.md)
- README·HANDOFF·PRD·ADR: [문서](.agents/skills/baton-watch-documentation-flows/SKILL.md)

## 검증과 완료

- 문구·문서·스킬만 바꾸면 링크·형식과 `git diff --check`를 확인한다. 구현을 그대로 반복하는 테스트는 추가하지 않는다.
- 코드는 관련 테스트부터 실행한다. 여러 모듈의 동작을 바꾸면 `./gradlew test`, 시간 경계는 고정 `Clock`, Compose 변경은 해당 파일 조합의 `docker compose config`로 검증한다. 필요한 검사가 통과하면 새 변경·실패·미해결 문제가 있을 때만 검사를 늘리거나 반복한다.
- 검증한 변경은 사용자가 달리 지시하지 않는 한 한국어 Conventional Commit으로 커밋하고 작업 브랜치를 푸시한다. `feat:`, `fix:`, `docs:`, `test:`, `refactor:`, `chore:` 등 타입을 유지하며 강제 푸시는 명시적 승인이 필요하다.
- 완료 보고에는 변경 내용, 검증 결과, 남은 차단 사유만 간단히 적는다.
