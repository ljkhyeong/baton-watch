---
name: baton-watch-api-contract
description: BATON WATCH의 인바운드 HTTP 경로·DTO·Bearer 인증·Problem Details 오류 응답·요청 본문 제한과 계약 테스트를 바꿀 때 사용한다. `/api/v1` 모니터 API, 공개 상태 API(`GET`·`HEAD`), 수동 재점검 요청, `MonitorApi*` 보안 필터·예외 처리기가 대상이다.
---

# BATON WATCH API 계약

기준은 [PRD-0002](../../../docs/PRD/0002_api-contract/spec.md)와 변경 대상 컨트롤러·HTTP 테스트다.
외부로 보내는 BATON 콜백은 [PRD-0004](../../../docs/PRD/0004_health-change-event-delivery/spec.md)의 별도 계약이며 [외부 통신](../baton-watch-outbound-http/SKILL.md) 스킬을 사용한다.

## 위치

| 역할 | 위치 |
| --- | --- |
| 컨트롤러·전송 DTO | `adapter-in-web/.../adapter/in/web/monitoring`, `.../system` |
| 오류 응답 | `MonitorApiProblem`, `monitoring/MonitorApiExceptionHandler`, `security/MonitorApiProblemWriter` |
| 인증·본문 제한·방화벽 거부 | `adapter-in-web/.../security`. 필터 체인 조립은 `bootstrap/.../MonitorApiSecurityConfiguration` |
| 계약 테스트 | 웹 모듈 MockMvc 테스트. 실제 Tomcat 인증·거부는 `bootstrap/.../MonitorApiSecurityIntegrationTest` |

## 규칙

- `/api/v1` 경로와 명시적인 전송 DTO를 사용한다. 컨트롤러는 입력 포트(`application/.../port/in`)에 위임하고 요청 형식 검증은 웹 어댑터에 둔다. 업무 로직 없이 상수와 시각만 돌려주는 공개 상태 API는 `Clock`을 직접 쓴다. 서비스 구현체·출력 포트를 직접 쓰면 ArchUnit이 실패한다.
- 공개 상태 조회와 인증이 필요한 모니터 API를 구분한다. 공개는 정확한 상태 경로의 `GET`·`HEAD`만 허용하고 하위 경로는 인증을 유지한다.
- Spring MVC 이전의 인증·방화벽·본문 제한 오류도 공통 Problem Details 형식을 유지한다. 응답에 대상 본문·자격 증명·해석된 IP·원본 예외·BATON 인가 판단을 노출하지 않는다.
- 요청 바인딩·Bearer 해석·예외 처리는 기존 Spring MVC·Security 확장점을 사용한다. 같은 필드를 중복 검증하지 않는다. 다만 웹 형식 검증과 도메인 값 타입(`TargetUrl` 등) 검증은 서로 다른 경계이므로 둘 다 유지한다.
- JSON 입력의 중복 필드·잘못된 타입·소수 리비전은 거부하는 기존 기준을 유지한다. 인증 실패가 본문 오류보다 먼저 판정되는 우선순위를 바꾸지 않는다.
- 수동 재점검은 `202` 예약 접수, 기존 도래 일정·유효 리스 합류, 리소스별 새 예약 간격 30초를 유지한다. 상세 조건은 [PRD-0003](../../../docs/PRD/0003_monitoring-mvp/spec.md)을 따른다.
- 요청 URL·쿼리·토큰·리소스 참조가 로그에 남지 않아야 한다. 로거를 추가하거나 바꾸면 [관측성](../baton-watch-observability/SKILL.md) 스킬의 고정 로거 규칙을 확인한다.

## 테스트 선택

- MockMvc는 핸들러 연결·필드·상태 코드를 확인한다. Spring MVC 이전 거부, `HEAD` 본문 생략, 본문 제한, 실제 인증 순서는 `MonitorApiSecurityIntegrationTest`처럼 실제 Tomcat으로 확인한다.
- 계약을 바꾸면 PRD-0002와 관련 테스트를 함께 갱신한다. 인증·상태 코드·콘텐츠 타입·필드·시간 형식·하위 호환성을 확인한다.

```bash
python3 ops/run-validation.py run --label <주제>-web -- ./gradlew :adapter-in-web:test
python3 ops/run-validation.py run --label <주제>-security -- \
  ./gradlew :bootstrap:test --tests '*MonitorApiSecurityIntegrationTest'
```

런타임 인증·조립을 바꾸면 `:bootstrap:test` 전체를 포함한다. 결과 재사용과 개수 집계는 [검증](../baton-watch-validation/SKILL.md) 스킬을 따른다.
