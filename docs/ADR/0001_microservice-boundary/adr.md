# ADR-0001: BATON WATCH Microservice Boundary

Status: accepted

Date: 2026-08-01

## Context

Checking arbitrary external URLs has variable latency, failure modes, and SSRF risk. Performing those checks inside BATON would couple resource commands and projections to untrusted networks.

## Decision

Create BATON WATCH as an independent Java 21 / Spring Boot 4.1.0 service. Keep its production dependency direction bootstrap -> adapters -> application -> domain across six Gradle modules under com.personal.baton.watch.

WATCH may eventually own asynchronous schedules, check attempts/results, derived UNKNOWN/HEALTHY/DEGRADED/BROKEN status, and durable health-change events. BATON retains RoleResource truth and authorization. BATON must submit or synchronize monitoring work only after its own transaction commits, and its transactions and projections must never wait for WATCH.

Network I/O must not run inside a database transaction. Workers use a short claim transaction, an external check with strict SSRF, DNS-rebinding, redirect, time, and byte defenses, and a short finalize transaction. Finalization stores metadata only, never response bodies, and atomically records a durable event when derived health changes. Time is supplied through Clock. Observability uses low-cardinality outcome labels and never raw URLs.

No frontend or broker is part of this baseline. A broker, database technology, event transport, retry formula, or health threshold requires a later adopted decision.

## Consequences

BATON remains responsive when targets are slow or hostile, and WATCH can scale and fail independently. Results are eventually consistent and may be unknown or stale. The service needs explicit synchronization, lease recovery, idempotent finalization and event delivery, outbound-network controls, retention, and operational monitoring before URL checking can be considered implemented.

## Current implementation note

Only the project skeleton and GET /api/v1/system/status exist today. This ADR defines the boundary for future work; it is not evidence that URL monitoring is live.
