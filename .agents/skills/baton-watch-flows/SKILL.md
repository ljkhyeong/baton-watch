---
name: baton-watch-flows
description: Repository workflow for broad BATON WATCH Java and Spring changes spanning modules or service boundaries. Use for check scheduling and execution, derived resource health, health-change delivery, shared Gradle structure, runtime composition, or cross-layer work that is not fully owned by a narrower WATCH skill.
---

# BATON WATCH Flows

## Establish context

- Read HANDOFF.md, AGENTS.md, README.md, the affected PRD, and relevant ADR.
- Separate current behavior from planned behavior. The PRD-0003 monitoring MVP
  and PRD-0004 direct event delivery are implemented; production deployment is
  not.
- Keep packages under com.personal.baton.watch and dependencies flowing bootstrap -> adapters -> application -> domain.

## Preserve ownership

- Let WATCH own asynchronous RoleResource URL schedules, attempts/results,
  derived UNKNOWN/HEALTHY/DEGRADED/BROKEN status, durable health-change events,
  and their at-least-once delivery to the adopted BATON callback.
- Let BATON remain authoritative for RoleResource data and authorization.
- Never block a BATON transaction or projection on WATCH. Synchronize only after BATON commits.
- Never store response bodies, credentials, cookies, or authorization headers.

## Implement safely

1. Put invariants in domain, use cases and ports in application, HTTP in adapter-in-web, storage in adapter-out-persistence, outbound checks in adapter-out-external, and wiring in bootstrap.
2. Claim work in a short transaction, perform network I/O without a transaction, then finalize attempt/result and any state-change event in another short transaction.
3. Inject Clock and use UTC instants for schedules, leases, attempts, and events.
4. Apply scheme, destination, DNS-rebinding, redirect, TLS, timeout, byte, and concurrency defenses before enabling any checker.
5. For event delivery, keep the fixed HTTPS callback, exact payload and
   idempotency header, separate bearer token, public-global DNS pinning, no
   redirects, durable retry lease, capped backoff, and delivered-only retention.
6. Add focused policy, use-case, adapter, and wiring tests; run the narrowest task before ./gradlew test.

Use baton-watch-api-contract, baton-watch-persistence, baton-watch-observability, or baton-watch-ops when one of those concerns is primary.
