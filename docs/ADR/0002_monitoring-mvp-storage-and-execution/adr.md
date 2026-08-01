# ADR-0002: Monitoring MVP Storage and Execution

Status: accepted

Date: 2026-08-01

## Context

PRD-0003 requires durable schedules, leases, immutable attempt/result history,
a current-health projection, and atomic health-change events. The worker must
not hold a database transaction while it performs hostile or slow network I/O.

The repository currently has persistence and external adapter modules but has
not selected a database, migration tool, storage API, or HTTP client.

## Decision

Use PostgreSQL as the MVP database, Flyway for append-only schema migrations,
and Spring JDBC for explicit SQL and transaction boundaries. The persistence
adapter owns four tables:

- `watch_monitor` for source revision, active schedule, lease, and current
  projection;
- `watch_attempt` for the immutable claim snapshot;
- `watch_result` for immutable completed result metadata;
- `watch_health_change_event` for the durable, currently undelivered outbox.

Claim uses a short transaction with `SELECT ... FOR UPDATE SKIP LOCKED`, followed
by lease-token updates and attempt inserts. The application performs checks
after that transaction returns. Finalization locks the monitor, verifies its
lease token and source revision, inserts the result, derives health through the
domain policy, updates the projection, and inserts an event when health changes
in one transaction. The attempt identifier is unique so repeated finalization
is a no-op.

Use Apache HttpClient 5 in the external adapter. Automatic redirects are
disabled. For every hop, a policy component parses and resolves the original
host, rejects non-global addresses, and supplies only the approved addresses to
a request-scoped client DNS resolver. The request URI keeps the original host
so HTTP Host, SNI, and TLS hostname verification remain correct. The application
depends only on an outbound checker port.

Spring scheduling lives in bootstrap and invokes application use cases. One
check runs at a time per process; batch size, lease, interval, timeouts, byte
limit, staleness, retention, and cleanup batch size are bounded configuration
values.

The internal monitor API uses one runtime-supplied bearer token. This is service
authentication only; WATCH still does not make BATON authorization decisions.

## Consequences

The SQL is PostgreSQL-specific and persistence integration tests must exercise
PostgreSQL, especially lease competition, recovery, stale revisions, and
migrations. Spring JDBC keeps locking and transaction scopes visible at the
cost of manual mapping.

DNS pinning requires an HTTP client with an injectable resolver; the JDK HTTP
client is not used because Java 21 does not expose an equivalent per-request
resolver. Redirect handling and response consumption remain explicit and
testable. The JVM resolver itself cannot be forcibly cancelled, so a bounded
resolver executor and infrastructure egress policy remain necessary defense in
depth.

The durable event table provides an atomic record of state changes but no event
delivery. A later adopted contract must select transport, retry, and retention
before adding a dispatcher or broker.

