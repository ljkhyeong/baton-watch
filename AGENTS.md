# BATON WATCH Agent Guide

## Start here

Read HANDOFF.md, README.md, the affected PRD, and relevant ADR before editing. Treat code, tests, PRDs, and ADRs as durable truth; use HANDOFF.md only for active transfer state. Use the matching skill under .agents/skills/.

## Preserve ownership

WATCH may asynchronously check BATON RoleResource URLs, retain schedules/attempt/result history, derive UNKNOWN, HEALTHY, DEGRADED, or BROKEN, and publish durable health-change events. WATCH must not authorize BATON resources, store response bodies, or block BATON transactions or projections.

URL checking is planned, not implemented. Do not document planned behavior as live. Do not add a frontend or message broker without an adopted requirement.

## Preserve architecture

Keep dependencies flowing bootstrap -> adapters -> application -> domain under com.personal.baton.watch.

- Put invariants and value types in domain.
- Put use cases, services, and ports in application.
- Put HTTP controllers and transport DTOs in adapter-in-web.
- Put database implementations in adapter-out-persistence.
- Put outbound HTTP and remote integrations in adapter-out-external.
- Put Spring assembly and runtime configuration in bootstrap.

Keep controllers thin. Inject Clock into time-sensitive application or domain code.

## Future check safety

Before any outbound request, enforce scheme and port policy, resolve and reject non-public destinations, pin the approved address, revalidate every redirect, and bound redirects, time, and bytes. Treat DNS rebinding and alternate IP forms as hostile. Never store bodies or use raw URLs as metric labels.

Claim work in a short transaction, perform network I/O outside a transaction, then finalize the attempt/result and durable state-change event in another short transaction.

## Verify

Run the narrowest affected task, then ./gradlew test for cross-module changes. Use fixed clocks for time behavior. Run docker compose config when Compose changes. Never claim a deployment or URL-check capability from repository artifacts alone.

