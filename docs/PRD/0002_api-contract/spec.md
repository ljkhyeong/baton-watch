# PRD-0002: BATON WATCH API 계약

상태: 유지 관리 계약

수정일: 2026-09-05

## 시스템 상태

`GET /api/v1/system/status`는 인증 없이 접근할 수 있으며 HTTP 200과
`application/json`을 반환한다.

~~~json
{
  "service": "baton-watch",
  "status": "UP",
  "observedAt": "2026-08-01T00:00:00Z"
}
~~~

`observedAt`은 서버가 생성한 UTC 시각이며 ISO 8601 형식으로 직렬화된다.
이 경로는 리소스 데이터를 노출하지 않는다.

## 채택된 모니터링 경로

### 공개 진입 경로의 요청 속도 제한

스테이징 터널 오버레이의 NGINX는 상태 경로와 나머지 `/api/v1/` 경로를
서로 다른 전체 요청 예산으로 제한한다. 초기값은 상태 경로 초당 10건·버스트 20건,
나머지 API 초당 5건·버스트 10건이며, 승인된 운영 처리량을 뜻하지 않는다.
제한 초과는 WATCH 인증 전에 HTTP 429, `application/problem+json`,
`Cache-Control: no-store`와 다음 고정 응답을 반환한다.

~~~json
{"type":"urn:baton-watch:problem:rate-limited","title":"요청이 너무 많습니다","status":429,"code":"RATE_LIMITED"}
~~~

이 응답에는 요청 경로나 식별자를 넣지 않는다. 프록시를 통과한 요청의 Bearer 인증과
인증 후 16 KiB 본문 제한은 기존 WATCH 계약을 유지한다. 내부 직접 접근과 로컬
Compose에는 이 프록시 제한이 적용되지 않는다. NGINX 자체의 잘못된 HTTP 요청,
연결 실패 등 다른 프록시 오류는 WATCH의 Problem Details 응답으로 보장하지 않는다.
구성과 공개 배포 전 검증은 [요청 제한 런북](../../runbooks/request-rate-limit.md)을 따른다.

### 모니터 동기화와 조회

다음 경로는 PRD-0003에 따라 구현되어 있다.

- `PUT /api/v1/resource-monitors/{resourceReference}`는 스냅샷을 동기화하고
  현재 프로젝션과 함께 HTTP 200을 반환한다.
- `GET /api/v1/resource-monitors/{resourceReference}`는 해당 프로젝션을
  반환하며, 없으면 HTTP 404를 반환한다.

두 경로 모두 `Authorization: Bearer <token>`이 필요하다. 구성된 토큰은
RFC 6750 `token68` 문자로 구성되고 패딩 제외 문자가 32자 이상이며 전체 길이는
200자 이하이다. Spring Security의
표준 Bearer 리졸버가 이를 파싱한다. PUT은 `resourceReference`와
`sourceRevision`의 조합으로 멱등성을 보장하므로 별도의 멱등성 키를 받지
않는다. 참조는 `A-Z`, `a-z`, `0-9`, `.`, `_`, `:` 및 `-`로 구성된
1~128자 문자열이다.

Bearer 인증 스킴은 HTTP 인증 의미에 따라 대소문자를 구분하지 않고 일치시킨다.
자격 증명 검증에 실패하면 HTTP 401 문제 응답과 함께
`WWW-Authenticate` Bearer 챌린지를 반환한다.

정확히 일치하는 시스템 상태 GET 경로만 공개된다. `/api/v1/**` 아래에서
문법적으로 허용되는 그 밖의 모든 요청은 라우팅 전에 무상태 서비스 인증 경계를
통과한다. 이 경계는 서블릿 컨텍스트를 기준으로 적용되므로 WATCH를 컨텍스트
경로 아래에 배포하더라도 모니터링 경로가 노출되지 않는다.

PUT은 `application/json`을 받는다. 정확한 모니터 PUT 경로의 JSON
본문은 16 KiB까지 허용하며, `Content-Length`가 이 한도를 초과하거나
스트림 본문이 이 한도를 넘으면 Jackson 객체화 전에 HTTP 413으로
거부한다. 이 제한은 인증 성공 뒤에 적용되므로, 자격 증명이 없거나
유효하지 않은 대용량 요청은 본문 크기나 JSON을 판단하기 전에 기존
HTTP 401 문제 응답을 반환한다. 활성 스냅샷은 다음과 같다.

~~~json
{
  "sourceRevision": 42,
  "monitoringState": "ACTIVE",
  "targetUrl": "https://example.com/health"
}
~~~

비활성 스냅샷은 `"monitoringState": "INACTIVE"`를 사용하며 `targetUrl`을
생략하거나 null로 설정해야 한다.

PUT과 GET은 `application/json`을 반환한다.

~~~json
{
  "resourceReference": "role-resource-123",
  "sourceRevision": 42,
  "monitoringState": "ACTIVE",
  "health": "UNKNOWN",
  "consecutiveFailures": 0,
  "lastOutcome": null,
  "lastCheckedAt": null,
  "lastConclusiveAt": null,
  "nextCheckAt": "2026-08-01T00:00:00Z"
}
~~~

`lastCheckedAt`은 결과 분류와 관계없이 마지막으로 완료 처리한 점검 시각이다.
`lastConclusiveAt`은 상태 도출에 반영할 수 있는 마지막 확정 결과의 완료 시각이며,
내부 실패처럼 확정할 수 없는 결과만 이어지면 이전 값을 유지한다. 두 값이 없으면
응답에서 해당 필드를 생략할 수 있다.

검증 실패는 HTTP 400, 오래된 리비전 또는 같은 리비전의 페이로드 충돌은
HTTP 409, 유효하지 않은 대상 정책은 HTTP 422, 인증된 JSON PUT의 16 KiB
본문 한도 초과는 HTTP 413, 존재하지 않는 모니터는 HTTP 404,
누락되었거나 유효하지 않은 자격 증명은 HTTP 401, 예기치 않았지만
안전하게 처리된 서버 실패는 HTTP 500을 반환한다. 오류는 안정적인 `type`,
`title`, `status`, `code` 필드를 포함하는 `application/problem+json`을
사용한다. 대상 URL, 조회된 주소, 자격 증명, 응답 본문, 원시 예외 또는 BATON의
인가 결정을 포함해서는 안 된다.

인증에 성공한 뒤 본문 제한 필터나 Spring MVC가 요청을 거부할 때도
다음과 같이 동일한 안정적 문제 계약을 사용한다.

- 잘못된 JSON 또는 요청 검증 실패: HTTP 400,
  `urn:baton-watch:problem:invalid-request`, `INVALID_REQUEST`
- JSON PUT 본문 한도 초과: HTTP 413,
  `urn:baton-watch:problem:payload-too-large`, `PAYLOAD_TOO_LARGE`
- 알 수 없는 `/api/v1/**` 경로: HTTP 404,
  `urn:baton-watch:problem:route-not-found`, `ROUTE_NOT_FOUND`
- 지원하지 않는 메서드: HTTP 405,
  `urn:baton-watch:problem:method-not-allowed`, `METHOD_NOT_ALLOWED`
- 허용할 수 없는 응답 미디어 타입: HTTP 406,
  `urn:baton-watch:problem:not-acceptable`, `NOT_ACCEPTABLE`
- 지원하지 않는 요청 미디어 타입: HTTP 415,
  `urn:baton-watch:problem:unsupported-media-type`,
  `UNSUPPORTED_MEDIA_TYPE`

그 밖의 Spring MVC 클라이언트 요청 거부는 HTTP 4xx 상태를 그대로 유지하고
`urn:baton-watch:problem:request-rejected`와 `REQUEST_REJECTED`를 사용한다.
분류되지 않은 프레임워크 실패는 애플리케이션 실패와 같은 안전한 HTTP 500
`INTERNAL_ERROR` 계약으로 축소하며, 프레임워크가 생성한 상세 정보와 거부된
값은 반환하지 않는다. 이 축소는 응답이 확정되기 전에 적용한다. 상태와 본문
바이트가 이미 확정된 뒤 프레임워크 실패가 보고되면 WATCH는 해당 응답을
유지하고 두 번째 문제 본문을 작성하지 않으며, 메시지나 스택 트레이스 없이
예외 클래스만 기록한다.

이러한 라우팅, 본문 크기·JSON 파싱 및 미디어 타입 판단보다 인증이
계속 먼저 수행되므로
자격 증명이 없거나 유효하지 않으면 기존 HTTP 401 문제를 반환한다. `Allow`와
`Accept`처럼 HTTP가 정의한 기능 헤더는 보존한다. 문제 응답의 `instance`가
있다면 원시 요청 경로가 아니라 고정된 비식별 URN
`urn:baton-watch:request`를 사용한다.

MVC 예외 응답과 인증·본문 제한·방화벽 오류 응답은 같은 문제 유형과 필드 생성
규칙을 사용한다. 미인증 HTTP 401 응답에는 `instance`를 넣지 않고
`type`, `title`, `status`, `code` 네 필드만 반환한다. 그 밖의 위 문제 응답은
고정된 `instance`를 포함한 다섯 필드를 반환한다. 응답 생성 규칙을 공유해도
인증 우선순위, 처리 지점별 헤더와 이미 확정된 응답의 처리 방식은 바뀌지 않는다.

Spring Security의 엄격한 HTTP 방화벽이 경로 일치 전에 거부한 요청은 이러한
인증 우선 순서의 바깥에 있다. 모호한 구분자, 매트릭스 구문 및 그 밖의 의심스러운
경로 형식은 인증 전에 안전한 방향으로 거부되며 HTTP 400,
`application/problem+json`, `urn:baton-watch:problem:request-rejected` 및
`REQUEST_REJECTED`를 반환한다. 이 응답은 동일한 고정 비식별 `instance`를
사용하며 원시 경로, 리소스 참조 또는 방화벽 예외를 절대로 포함하지 않는다.
방화벽 정책은 완화하지 않는다.

시도 이력, 인바운드 웹훅 또는 이벤트 전달 경로는 채택하지 않았다.
수동 재점검은 아래 예약 경로만 제공한다.
PRD-0004의 직접 전달은 WATCH의 아웃바운드 콜백이며 이러한 인바운드 경로를
변경하지 않는다. 이후 조회 경로는 구현 전에 커서 페이지네이션을 정의해야 한다.

모든 애플리케이션 경로는 `/api/v1` 아래에 유지하고 이름이 있는 전송 DTO를
사용하며 인바운드 애플리케이션 포트에 위임한다.

## 수동 재점검 요청

`POST /api/v1/resource-monitors/{resourceReference}/check-requests`는 기존 모니터 API와
같은 Bearer 서비스 인증을 사용한다. 요청 본문은 필요하지 않고 대상 URL·리비전을
받지 않는다. 최종 사용자 인가는 BATON의 책임이며 이 API를 최종 사용자에게 직접 공개하지 않는다.

HTTP 202와 `application/json` 응답 예시:

```json
{"status":"SCHEDULED","nextCheckAt":"2026-09-05T00:00:00Z"}
```

| `status` | 의미 | `nextCheckAt` |
| --- | --- | --- |
| `SCHEDULED` | 다음 일정을 요청 시각으로 앞당김 | 앞당긴 시각 |
| `ALREADY_SCHEDULED` | 이미 도래한 기존 일정에 합류 | 기존 예정 시각 |
| `IN_PROGRESS` | 실행 중인 유효 리스에 합류 | null 또는 생략 |

응답은 실제 점검 결과나 시작 시각 보장이 아니다. 활성 작업자가 없으면 접수한 일정이
대기한다. 리비전·대상·상태·결과를 변경하거나 새로운 네트워크 호출을 동기로 수행하지 않는다.
별도 멱등성 키 없이 같은 모니터의 도래 일정과 유효 리스에 합류한다.

오류는 기존의 고정 `instance`를 포함한 5개 필드 Problem Details를 사용한다.

| HTTP | `code` | `type`의 접미사 | 조건 |
| --- | --- | --- | --- |
| 404 | `MONITOR_NOT_FOUND` | `monitor-not-found` | 모니터 없음 |
| 409 | `MONITOR_INACTIVE` | `monitor-inactive` | 비활성 모니터 |
| 429 | `CHECK_REQUEST_RATE_LIMITED` | `check-request-rate-limited` | 직전 새 수동 예약 이후 30초 이내에 다시 앞당기려는 요청 |

`type` 접두사는 `urn:baton-watch:problem:`이다. 429의 `Retry-After`는 남은 시간을
올림한 양의 정수 초다. 30초 정각부터 허용한다. 대기·실행 중 작업에 합류할 때는
202를 반환하며 간격 기준 시각을 갱신하지 않는다. NGINX의 전체 요청 제한은 별도로 유지한다.
인증 실패는 다른 모니터 API와 같은 401이며, 잘못된 참조 형식과 예기치 않은 오류는
기존 400·500 계약을 따른다.
