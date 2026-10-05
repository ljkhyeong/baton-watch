---
name: baton-watch-outbound-http
description: BATON WATCH의 외부 HTTP 요청 안전성을 바꿀 때 사용한다. 대상 URL 구문(`TargetUrl`·`TargetUriPolicy`), 리다이렉트 재검증·순환, DNS 조회와 전역 주소 정책, 검증한 IP 고정·TLS, 연결·응답·전체 시간 제한, 실행기 취소, 대상 GET 본문 미소비, BATON 콜백 목적지·요청 제한이 대상이다.
---

# BATON WATCH 외부 통신

기준은 [PRD-0001 외부 HTTP 요청 안전 기준](../../../docs/PRD/0001_product-baseline/spec.md),
대상 점검은 [PRD-0003 대상 및 요청 정책](../../../docs/PRD/0003_monitoring-mvp/spec.md),
콜백은 [PRD-0004 목적지와 요청 안전성](../../../docs/PRD/0004_health-change-event-delivery/spec.md)이다.
수치·예외 조건은 PRD를 기준으로 하고 이 스킬에 반복하지 않는다.

## 위치

| 역할 | 위치 |
| --- | --- |
| 정적 URL 구문 | `domain/.../monitoring/TargetUrl`(등록), `adapter-out-external/.../check/TargetUriPolicy`(리다이렉트 연결) |
| 대상 점검 흐름 | `check/SafeUrlCheckEngine`(홉·순환), `ApacheHttpHopTransport`, `ApacheUrlChecker` |
| 주소 정책·DNS | `check/GlobalAddressPolicy`, `BoundedDnsLookup`. IANA 기준은 `ops/check-iana-registry.sh`·`ops/iana-registry-sha256.txt` |
| IP 고정·클라이언트 | `http/PinnedDnsResolver`, `PinnedApacheClientFactory`(실행기 내부), `ApacheHttpClientLimits` |
| 실행·취소·응답 수명 | `http/ApacheHttpRequestExecutor.executePinned`(두 전송의 공통 진입점), `ApacheResponseLifecycle`, `ResponseBodyDiscarder` |
| 콜백 | `delivery/DeliveryEndpointPolicy`, `SafeEventDeliveryEngine`, `ApacheEventDeliveryTransport`. 설정은 `bootstrap/.../EventDeliveryProperties`·`EventDeliveryConfiguration` |
| 결과 분류 | `domain/.../monitoring/CheckOutcome` |

## 규칙

- 등록과 리다이렉트는 같은 정적 구문 정책을 공유한다. 한 경로에만 검사를 추가하지 말고 `TargetUrl` 기준을 고친 뒤 두 경로를 함께 검증한다.
- 리다이렉트 참조는 URI 해석 전에 검사하고 해석 후 절대 대상으로 다시 검사한다. 잘못된 이동은 추가 DNS 조회·연결 전에 `REDIRECT_REJECTED`로 끝낸다. 경로의 점 구간 처리, 연속 슬래시·인코딩·쿼리 보존, 순환 판정 규칙을 바꾸면 PRD-0003의 예시와 대조한다.
- DNS 결과 중 하나라도 전역 주소가 아니면 전체를 거부한다. 클라우드 내부 주소의 명시 거부를 유지한다. 허용 범위를 넓히는 변경은 IANA 체크섬·경계 테스트를 함께 검토하며 자동으로 넓히지 않는다.
- 검증한 IP에만 연결하고 HTTP `Host`·SNI·TLS 호스트 이름 검증에는 원래 호스트를 유지한다. 연결 직전 DNS를 다시 조회하는 경로를 만들지 않는다.
- 자동 재시도·쿠키·인증 캐시·프록시 탐색·응답 압축 해제를 비활성화한다. 콜백은 리다이렉트를 따르지 않는다.
- 대상 GET은 헤더를 받으면 본문을 읽거나 비우지 않고 연결을 닫는다. 콜백 응답은 크기 제한 안에서만 소비하고 초과하면 비우지 않고 중단한다. 응답 본문은 저장하지 않는다.
- 각 단계 제한은 남은 전체 기한으로 줄여 적용한다. 전체 기한은 DNS와 모든 리다이렉트(콜백은 직렬화 포함)를 묶은 단일 마감이다. 소켓 제한은 최소 1밀리초로 두어 0(무제한) 변환을 막는다.
- 기한 만료·인터럽트 시 대기 작업을 큐에서 제거하고 실제 HTTP 요청을 취소한다. 이미 인터럽트된 호출은 실행기에 제출하지 않고 `INTERNAL_FAILURE`를 반환하며 호출자의 인터럽트 상태를 지우지 않는다.
- 시간·헤더·바이트·DNS·동시성·큐 상한은 런타임 설정으로 끌 수 없다. 설정 상한을 바꾸면 `ConfigurationPropertiesValidationTest`와 README 설정 설명을 함께 확인한다.
- 콜백 URL은 공개 HTTPS 기본 포트로 고정한다. 설정 문자열을 JDK URI로 해석해 퍼센트 인코딩을 보존하고, 설정 오류에 URL 원문·원인 예외를 넣지 않는다.
- 새 실패는 기존 `CheckOutcome` 분류에 맞춘다. 헤더 제한 위반은 `RESPONSE_TOO_LARGE`다. 로그·메트릭에 URL·호스트·IP·예외 메시지를 넣지 않는다.

## 검증

- 실제 공개 대상에 요청하지 않는다. 로컬 HTTP·TLS 서버(`LoopbackHttpTestServer`, `PinnedApacheClientFactoryTlsIntegrationTest`)와 DNS 대역으로 재현한다.
- 시간 경계는 1ns·999999ns처럼 밀리초 변환 경계를 포함하고, 취소는 헤더 대기 중·본문 수신 중을 구분한다.

```bash
python3 ops/run-validation.py run --label <주제>-reproduction -- \
  ./gradlew :adapter-out-external:test --tests '*SafeUrlCheckEngineTest'
python3 ops/run-validation.py run --label <주제>-external -- ./gradlew :adapter-out-external:test
```

- `TargetUrl`을 바꾸면 `:domain:test`와 등록 API(`:adapter-in-web:test`, 필요 시 `MonitorApiSecurityIntegrationTest`)를 포함한다.
- 콜백 설정을 바꾸면 `:bootstrap:test --tests '*EventDeliveryConfigurationTest'`로 실제 Spring 시작 과정의 거부와 원문 비노출을 확인한다.
- 주소 정책 기준을 바꿀 때만 `ops/check-iana-registry.sh`를 실행한다. IANA 원본을 내려받는 외부 통신이므로 같은 입력으로 반복하지 않는다.
