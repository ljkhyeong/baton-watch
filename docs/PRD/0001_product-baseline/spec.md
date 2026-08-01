# PRD-0001: BATON WATCH Product Baseline

Status: maintained baseline

Updated: 2026-08-01

## Purpose

BATON WATCH is a separate service boundary for observing whether URLs attached to BATON RoleResource records remain reachable. It must keep monitoring latency and failures outside BATON transactions and projections.

## Current capability

The current implementation is a project skeleton with GET /api/v1/system/status only. It does not schedule, perform, persist, or publish URL checks.

## Planned ownership

WATCH is planned to own:

- asynchronous URL-check schedules for resource references supplied by BATON;
- immutable attempt and result history with bounded retention;
- derived resource health: UNKNOWN, HEALTHY, DEGRADED, or BROKEN;
- durable events emitted only when the derived health changes.

Exact schedules, retry thresholds, derivation windows, retention periods, authentication, and event transport are undecided and require an adopted follow-up contract.

## Explicit non-ownership

WATCH must never:

- decide whether a principal may access a BATON workspace, role, or resource;
- become the source of truth for RoleResource content or authorization;
- store response bodies, credentials, cookies, or authorization headers;
- make a BATON command, transaction, or projection wait for a network check.

BATON remains authoritative for resources and authorization. A missing or stale WATCH result must not deny BATON access by itself.

## Planned execution boundary

A worker must claim eligible work in a short transaction, release the transaction, perform the network request, then finalize the attempt/result and any durable health-change event in another short transaction. Claims require leases or equivalent recovery so crashed workers do not strand work. All time-dependent decisions use an injected Clock and UTC instants.

## Outbound-request safety baseline

Before connecting, a checker must:

1. allow only adopted schemes and ports;
2. parse and normalize the host without accepting embedded credentials or ambiguous alternate IP forms;
3. resolve DNS and reject loopback, link-local, private, multicast, unspecified, metadata, and other non-public destinations;
4. connect only to an address from the validated resolution set while preserving correct HTTP host and TLS verification;
5. re-run the complete policy for every redirect and cap redirect count;
6. cap connection, read, and total time, response bytes, and concurrency.

This policy must account for DNS rebinding and changes between validation and connection. Never persist a response body. Logs must redact URL user-info, query, and fragment. Metrics must use bounded identifiers or result classes, never raw URLs, hosts, resource IDs, or exception messages as labels.

## Health semantics

UNKNOWN means no conclusive current assessment. HEALTHY, DEGRADED, and BROKEN are derived states, not direct HTTP status aliases. Their thresholds and treatment of redirects, TLS, DNS, timeouts, and transient failures remain planned until a follow-up product decision is adopted.
