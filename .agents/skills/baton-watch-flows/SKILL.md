---
name: baton-watch-flows
description: 모듈이나 서비스 경계에 걸친 광범위한 BATON WATCH Java 및 Spring 변경을 위한 저장소 작업 흐름. 점검 예약과 실행, 도출된 리소스 상태, 상태 변경 전달, 공유 Gradle 구조, 런타임 조립 또는 더 좁은 WATCH 스킬 하나가 온전히 소유하지 않는 계층 간 작업에 사용한다.
---

# BATON WATCH 작업 흐름

## 맥락 확립

- HANDOFF.md, AGENTS.md, README.md, 영향을 받는 PRD와 관련 ADR을 읽는다.
- 현재 동작과 계획된 동작을 구분한다. PRD-0003 모니터링 MVP와 PRD-0004 직접
  이벤트 전달은 구현되어 있지만, 운영 배포는 구현되지 않았다.
- 패키지를 com.personal.baton.watch 아래에 유지하고 의존성 흐름을 bootstrap -> adapters -> application -> domain으로 유지한다.

## 소유권 보존

- 비동기 RoleResource URL 일정, 시도/결과, 도출된 UNKNOWN/HEALTHY/DEGRADED/BROKEN
  상태, 내구성 있는 상태 변경 이벤트와 채택된 BATON 콜백으로의 최소 한 번 전달은
  WATCH가 소유한다.
- RoleResource 데이터와 인가의 권위 있는 원본은 계속 BATON이 맡는다.
- BATON 트랜잭션이나 프로젝션이 WATCH를 기다리게 해서는 안 된다. BATON 커밋 이후에만 동기화한다.
- 응답 본문, 자격 증명, 쿠키 또는 인가 헤더를 절대로 저장하지 않는다.

## 안전한 구현

1. 불변식은 domain, 유스케이스와 포트는 application, HTTP는 adapter-in-web, 저장소는 adapter-out-persistence, 아웃바운드 점검은 adapter-out-external, 조립은 bootstrap에 둔다.
2. 짧은 트랜잭션에서 작업을 선점하고 트랜잭션 없이 네트워크 I/O를 수행한 뒤, 다른 짧은 트랜잭션에서 시도/결과와 모든 상태 변경 이벤트를 확정한다.
3. Clock을 주입하고 일정, 리스, 시도와 이벤트에 UTC 시각을 사용한다.
4. 점검기를 활성화하기 전에 스킴, 목적지, DNS 리바인딩, 리다이렉트, TLS, 타임아웃, 바이트와 동시성 방어를 적용한다.
5. 이벤트 전달에서는 고정 HTTPS 콜백, 정확한 페이로드와 멱등성 헤더, 별도
   베어러 토큰, 공개 글로벌 주소 검증과 승인된 DNS 해석 결과 고정, 리다이렉트 금지, 내구성 있는 재시도 리스,
   상한이 있는 백오프와 전달 완료 이벤트만의 보존을 유지한다.
6. 범위를 좁힌 정책, 유스케이스, 어댑터와 조립 테스트를 추가하고 ./gradlew test보다 먼저 가장 좁은 범위의 작업을 실행한다.

해당 관심사 중 하나가 주된 작업이라면 baton-watch-api-contract, baton-watch-persistence, baton-watch-observability 또는 baton-watch-ops를 사용한다.
