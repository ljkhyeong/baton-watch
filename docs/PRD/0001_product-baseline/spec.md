# PRD-0001: BATON WATCH Product Baseline

Status: maintained baseline

Updated: 2026-08-02

## Purpose

BATON WATCH is a separate service boundary for observing whether URLs attached to BATON RoleResource records remain reachable. It must keep monitoring latency and failures outside BATON transactions and projections.

## Current capability

The monitoring MVP from PRD-0003 is implemented. WATCH accepts revisioned active
or inactive URL snapshots, schedules and performs bounded asynchronous checks,
persists attempt/result metadata, derives current health, and records durable
health-change events. PRD-0004 delivery to one configured BATON HTTPS callback
is also implemented with durable leases, at-least-once retries, delivered-event
retention, and low-cardinality operator telemetry. Production deployment is not
established.

## Ownership

WATCH owns:

- asynchronous URL-check schedules for resource references supplied by BATON;
- immutable attempt and result history with bounded retention;
- derived resource health: UNKNOWN, HEALTHY, DEGRADED, or BROKEN;
- durable events recorded only when the derived health changes;
- asynchronous, at-least-once delivery of those events to the adopted BATON
  callback.

PRD-0003 adopts the first schedule, retry, health, retention, authentication,
and HTTP-check contracts for the monitoring MVP. PRD-0004 adopts the direct
HTTPS callback, payload, authentication, idempotency, retry/lease, retention,
safety, and observability contract for event delivery.

## Explicit non-ownership

WATCH must never:

- decide whether a principal may access a BATON workspace, role, or resource;
- become the source of truth for RoleResource content or authorization;
- store response bodies, credentials, cookies, or authorization headers;
- make a BATON command, transaction, or projection wait for a network check.

BATON remains authoritative for resources and authorization. A missing or stale WATCH result must not deny BATON access by itself.

## Execution boundary

A check worker must claim eligible work in a short transaction, release the
transaction, perform the target request, then finalize the attempt/result and
any durable health-change event in another short transaction. An event
dispatcher follows the same transaction boundary around the separate callback
POST. Both use expiring leases and idempotent finalization so a stopped worker
does not strand work. All time-dependent decisions use an injected Clock and
UTC instants.

## Outbound-request safety baseline

Before connecting, a checker must:

1. allow only adopted schemes and ports;
2. parse and normalize the host without accepting embedded credentials or ambiguous alternate IP forms;
3. resolve DNS and reject loopback, link-local, private, multicast, unspecified, metadata, and other non-public destinations;
4. connect only to an address from the validated resolution set while preserving correct HTTP host and TLS verification;
5. for target checks, re-run the complete policy for every redirect and cap
   redirect count; for event delivery, reject redirects;
6. cap connection, read, and total time, response bytes, and concurrency.

This policy must account for DNS rebinding and changes between validation and connection. Never persist a response body. Logs must redact URL user-info, query, and fragment. Metrics must use bounded identifiers or result classes, never raw URLs, hosts, resource IDs, or exception messages as labels.

## Health semantics

UNKNOWN means no conclusive current assessment. HEALTHY, DEGRADED, and BROKEN
are derived states, not direct HTTP status aliases. PRD-0003 defines the MVP
thresholds and the treatment of redirects, TLS, DNS, timeouts, and internal
failures.

## Event delivery semantics

Health-change events are delivered at least once, so BATON must durably
deduplicate the matching `Idempotency-Key` and JSON `eventId`. The callback
uses a separate bearer service token and a restricted payload; it does not
carry a target URL, response body, credential, or authorization decision.
WATCH retains every undelivered event and deletes only delivered events through
bounded retention work. PRD-0004 is authoritative for the full contract.
