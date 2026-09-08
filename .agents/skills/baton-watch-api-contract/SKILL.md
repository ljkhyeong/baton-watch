---
name: baton-watch-api-contract
description: BATON WATCH의 인바운드 HTTP 경로, DTO, 인증, 오류 응답과 계약 테스트를 변경할 때 사용한다.
---

# BATON WATCH API 계약

기준은 [PRD-0002](../../../docs/prd/0002_api-contract/spec.md)와 변경 대상 컨트롤러·HTTP 테스트다. 외부로 보내는 BATON 콜백은 [PRD-0004](../../../docs/prd/0004_health-change-event-delivery/spec.md)의 별도 계약이다.

- `/api/v1` 경로와 명시적인 전송 DTO를 사용한다. 컨트롤러는 애플리케이션 포트에 위임하고, 요청 형식 검증은 웹 어댑터에 둔다.
- 공개 상태 조회와 인증이 필요한 모니터 API를 구분한다. Spring MVC 이전 인증·방화벽 오류도 공통 Problem Details 형식을 유지한다. 응답에 대상 본문·자격 증명·해석된 IP·원본 예외·BATON 인가 판단을 노출하지 않는다.
- 요청 바인딩·Bearer 해석·예외 처리는 기존 Spring MVC·Security 확장점을 사용한다. 같은 필드를 중복 검증하는 코드를 추가하지 않는다.
- 수동 재점검은 `202` 예약 접수, 기존 도래 일정·유효 리스 합류, 리소스별 새 예약 간격 30초를 유지한다. 상세 조건은 [PRD-0003](../../../docs/prd/0003_monitoring-mvp/spec.md)을 따른다.
- 계약을 바꾸면 해당 PRD와 관련 MockMvc 테스트를 함께 갱신한다. 변경한 인증·상태 코드·콘텐츠 타입·필드·시간 형식·호환성을 확인한다.

`./gradlew :adapter-in-web:test`를 실행한다. 런타임 인증·조립도 바꾸면 관련 `bootstrap` 테스트를 포함한다.
