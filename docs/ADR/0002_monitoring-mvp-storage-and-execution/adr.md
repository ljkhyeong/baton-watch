# ADR-0002: Monitoring MVP Storage and Execution

Status: accepted

Date: 2026-08-01

## Context

PRD-0003 requires durable schedules, leases, immutable attempt/result history,
a current-health projection, and atomic health-change events. The worker must
not hold a database transaction while it performs hostile or slow network I/O.

At the time of this decision, the repository had persistence and external
adapter modules but had not selected a database, migration tool, storage API,
or HTTP client.

## Decision

Use PostgreSQL as the MVP database, Flyway for append-only schema migrations,
and Spring JDBC for explicit SQL and transaction boundaries. The persistence
adapter owns four tables:

- `watch_monitor` for source revision, active schedule, lease, and current
  projection;
- `watch_attempt` for the immutable claim snapshot;
- `watch_result` for immutable completed result metadata;
- `watch_health_change_event` for the durable outbox and, as extended by
  ADR-0003, its pending/delivered state, delivery attempts, due time, expiring
  lease, bounded outcome, acknowledgement time, and retention eligibility.

Claim uses a short transaction with `SELECT ... FOR UPDATE SKIP LOCKED`, followed
by lease-token updates and attempt inserts. The application performs checks
after that transaction returns. Finalization locks the monitor, verifies its
lease token and source revision, inserts the result, derives health through the
domain policy, updates the projection, and inserts an event when health changes
in one transaction. The attempt identifier is unique so repeated finalization
is a no-op.

Every adapter-owned persistence transaction has a five-second Spring
transaction timeout and applies a one-second PostgreSQL `lock_timeout` with
transaction-local scope. The lock limit fails row-lock contention before the
broader statement deadline, and local scope prevents a pooled connection from
carrying the setting into later work. Persistence fails before starting if a
caller already owns a Spring transaction, preserving the rule that application
network I/O cannot accidentally run inside an outer transaction.

Use Apache HttpClient 5 in the external adapter. Automatic redirects are
disabled. For every hop, a policy component parses and resolves the original
host, rejects non-global addresses, and supplies only the approved addresses to
a request-scoped client DNS resolver. The request URI keeps the original host
so HTTP Host, SNI, and TLS hostname verification remain correct. The application
depends only on an outbound checker port. Successful responses are closed
normally, while byte- or header-limit failures abort the response immediately
so Apache cannot drain additional bytes for connection reuse. An
unknown-length body that reaches its remaining byte allowance is rejected
without an extra probe read.

`TargetUrl` owns the static target syntax policy used when a snapshot is
accepted and when each redirect is revalidated. Compatibility-deferred
encoded-character checks remain explicit so historical rows can still be
rehydrated and converted
to `DESTINATION_REJECTED`; new synchronization commands must pass the same
check before persistence. The external adapter adds only raw redirect-reference
validation, resolution, and loop canonicalization before DNS/address approval.

Spring scheduling lives in bootstrap and invokes application use cases. Target
checks, callback delivery, and database maintenance use independent named
single-thread schedulers. One check and one delivery batch run at a time per
process, while a slow callback cannot starve target checks or maintenance.
Batch size, lease, interval, timeouts, byte limit, staleness, retention, and
cleanup batch size are bounded configuration values. Security- and
allocation-sensitive byte, header, executor, queue, and batch settings also
have immutable implementation ceilings that runtime configuration cannot
exceed.

The internal monitor API uses one runtime-supplied bearer token. This is service
authentication only; WATCH still does not make BATON authorization decisions.

## Consequences

The SQL is PostgreSQL-specific and persistence integration tests must exercise
PostgreSQL, especially lease competition, recovery, stale revisions, and
migrations. Spring JDBC keeps locking and transaction scopes visible at the
cost of manual mapping.

Transaction limits apply to transaction-scoped JDBC statements and row-lock
waits. They do not bound connection acquisition or non-transactional projection
and backlog queries, which remain subject to separate datasource and runtime
controls. Independent scheduler lanes can use up to three database connections
concurrently, so pool sizing must retain that minimum operational headroom.

DNS pinning requires an HTTP client with an injectable resolver; the JDK HTTP
client is not used because Java 21 does not expose an equivalent per-request
resolver. Redirect handling and response consumption remain explicit and
testable. The JVM resolver itself cannot be forcibly cancelled, so a bounded
resolver executor and infrastructure egress policy remain necessary defense in
depth.

The durable event table provides the atomic record consumed by the direct HTTPS
dispatcher adopted in PRD-0004 and ADR-0003. Delivery uses separate short claim
and finalize transactions around network I/O, capped exponential retry, and
retention that deletes only acknowledged events. No broker is adopted.
