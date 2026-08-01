# ADR-0001: BATON WATCH Microservice Boundary

Status: accepted

Date: 2026-08-01

## Context

Checking arbitrary external URLs has variable latency, failure modes, and SSRF risk. Performing those checks inside BATON would couple resource commands and projections to untrusted networks.

## Decision

Create BATON WATCH as an independent Java 21 / Spring Boot 4.1.0 service. Keep its production dependency direction bootstrap -> adapters -> application -> domain across six Gradle modules under com.personal.baton.watch.

WATCH owns asynchronous schedules, check attempts/results, derived
UNKNOWN/HEALTHY/DEGRADED/BROKEN status, and durable health-change events. BATON
retains RoleResource truth and authorization. BATON must submit or synchronize
monitoring work only after its own transaction commits, and its transactions and
projections must never wait for WATCH.

Network I/O must not run inside a database transaction. Workers use a short claim transaction, an external check with strict SSRF, DNS-rebinding, redirect, time, and byte defenses, and a short finalize transaction. Finalization stores metadata only, never response bodies, and atomically records a durable event when derived health changes. Time is supplied through Clock. Observability uses low-cardinality outcome labels and never raw URLs.

No frontend or broker is part of this baseline. PRD-0004 and ADR-0003 adopt
direct delivery to one fixed BATON HTTPS callback; a broker, fan-out, private
destination policy, or different event transport requires a later adopted
decision.

## Consequences

BATON remains responsive when targets or the callback are slow or hostile, and
WATCH can scale and fail independently. Results and notifications are
eventually consistent and may be unknown, stale, duplicated, or delayed. The
service therefore requires explicit synchronization, lease recovery,
idempotent check finalization, event-ID deduplication at BATON,
outbound-network controls, retention, and operational monitoring.

## Current implementation note

The PRD-0003 monitoring MVP, ADR-0002 PostgreSQL/checker design, and PRD-0004 /
ADR-0003 direct event delivery are implemented. Production deployment is not.
