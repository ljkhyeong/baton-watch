# BATON WATCH Agent Guide

## Start here

Read HANDOFF.md, README.md, the affected PRD, and relevant ADR before editing. Treat code, tests, PRDs, and ADRs as durable truth; use HANDOFF.md only for active transfer state. Use the matching skill under .agents/skills/.

## Preserve ownership

WATCH may asynchronously check BATON RoleResource URLs, retain
schedules/attempt/result history, derive UNKNOWN, HEALTHY, DEGRADED, or BROKEN,
and record durable health-change events. WATCH must not authorize BATON
resources, store response bodies, or block BATON transactions or projections.

The monitoring MVP and direct HTTPS health-change event delivery are
implemented. Delivery is at least once to one configured BATON callback; BATON
must deduplicate by event ID. Do not document production deployment, external
alerts, a frontend, or a message broker as live.

## Preserve architecture

Keep dependencies flowing bootstrap -> adapters -> application -> domain under com.personal.baton.watch.

- Put invariants and value types in domain.
- Put use cases, services, and ports in application.
- Put HTTP controllers and transport DTOs in adapter-in-web.
- Put database implementations in adapter-out-persistence.
- Put outbound HTTP and remote integrations in adapter-out-external.
- Put Spring assembly and runtime configuration in bootstrap.

Keep controllers thin. Inject Clock into time-sensitive application or domain code.

## Check safety

Before any outbound request, enforce scheme and port policy, resolve and reject
non-public destinations, pin the approved address, and bound time, headers,
bytes, concurrency, and queues. Revalidate every target-check redirect; never
follow an event-delivery redirect. Treat DNS rebinding and alternate IP forms
as hostile. Never store bodies or use raw URLs, hosts, resource references,
event IDs, or exception messages as metric labels.

Claim check or delivery work in a short transaction, perform network I/O
outside a transaction, then finalize in another short transaction. Keep event
payloads immutable, leases expiring, retry delays capped, finalization
idempotent, and retention limited to delivered events.

## Commit messages

Keep Conventional Commit type prefixes such as `feat:`, `fix:`, `docs:`,
`test:`, `refactor:`, and `chore:` for categorization. Write commit subjects and
bodies in Korean. After successful verification, commit completed changes and
push the current branch unless the user says otherwise. Never force-push
without explicit approval.

## Verify

Run the narrowest affected task, then ./gradlew test for cross-module changes. Use fixed clocks for time behavior. Run docker compose config when Compose changes. Never claim a deployment or URL-check capability from repository artifacts alone.
