# PRD-0003: URL Monitoring MVP

Status: accepted

Date: 2026-08-01

Implementation: complete; event delivery is implemented separately by PRD-0004

## Goal

The MVP closes one operational loop: BATON can synchronize a URL snapshot for
a RoleResource after BATON commits, WATCH checks it asynchronously, and BATON
can read the latest derived health without waiting for the target network.

This contract does not make WATCH authoritative for RoleResource content or
authorization.

## Monitor synchronization

- A monitor is identified by an opaque `resourceReference` supplied by BATON.
- Every synchronization includes a non-negative, monotonically increasing
  `sourceRevision` owned by BATON.
- A higher revision replaces the current snapshot. A lower revision is rejected
  as stale. Repeating an equal revision with an equal payload is idempotent;
  reusing it for a different payload is a conflict.
- `ACTIVE` requires a target URL. A new monitor, or a monitor whose target
  changed, starts as `UNKNOWN` and is due immediately.
- `INACTIVE` has no target URL, stops future checks, invalidates any lease, and
  retains bounded history. A higher-revision `ACTIVE` synchronization enables
  it again.
- The current projection never exposes the target URL, response body, resolved
  address, or internal exception text.

Monitor routes use a static bearer token supplied through runtime
configuration. The system status route remains unauthenticated. Token rotation,
per-workspace authorization, and end-user access are outside this MVP.

## Scheduling and execution

- The normal check interval is a runtime-wide duration, initially 60 seconds.
- New and changed active monitors are due immediately.
- A worker claims at most a configured batch of due monitors in a short
  transaction using a 30-second lease.
- Claim creates an immutable attempt snapshot containing the source revision and
  target at that moment, then commits before DNS or HTTP work begins.
- Finalization occurs in another short transaction and is idempotent by attempt
  identifier.
- An expired lease is eligible for another worker. A stale worker cannot update
  current health after a newer claim or source revision replaced its lease.
- MVP execution is intentionally single-check-per-process. Database locking and
  leases still allow multiple processes to claim disjoint work.
- A monitor without a conclusive result for 10 minutes becomes `UNKNOWN` in a
  bounded background sweep.

## Target and request policy

- Only absolute `http` and `https` URLs are accepted.
- Only their default ports, 80 and 443, are allowed.
- URL user-info, fragments, IP-literal hosts, ambiguous authorities, control
  characters, and backslashes are rejected. Query strings may be checked but
  must not be logged or exposed by the API.
- Synchronization and every redirect use the same static target syntax policy,
  including rejection of percent-encoded ASCII control octets, DEL, and
  backslashes. Redirect references are checked before URI resolution and again
  after resolution as absolute targets.
- Before each connection, WATCH resolves the DNS hostname, rejects the complete
  answer if any address is non-global, and pins the connection to the approved
  resolution while retaining the original host for HTTP and TLS verification.
- Redirects are handled explicitly. Every target is revalidated, HTTPS-to-HTTP
  downgrade is rejected, redirect loops are rejected, and the chain is limited
  to three redirects.
- Automatic retries, cookies, authentication caching, proxy discovery, and
  response decompression are disabled.
- Connection time, response time, total check time, response headers, and
  consumed response bytes are bounded. A declared oversized body is rejected
  before consumption. An unknown-length body that reaches the remaining byte
  limit is rejected without reading a probe byte beyond that limit, and the
  failed response is aborted rather than drained. Header-limit violations are
  classified as `RESPONSE_TOO_LARGE`. Response bodies are discarded and never
  persisted.

The default limits are a 2-second connection timeout, 3-second response
timeout, 5-second total timeout, 64 KiB consumed response limit, at most 100
response headers of at most 8 KiB per line, two DNS threads with eight queued
lookups, and one HTTP request thread with one queued request. Runtime settings
may not exceed 1 MiB of response bytes, 200 response headers, 16 KiB per header
line, eight DNS threads with 64 queued lookups, or four HTTP request threads
with 16 queued requests. Check claims are capped at 100 items and maintenance
batches at 1,000 items. These limits may not be disabled. DNS resolution runs
in a bounded executor because the JVM resolver has no per-call cancellation
contract.

## Outcomes and health

The persisted outcome taxonomy is bounded:

- `SUCCESS`: a final HTTP response in the 200-399 range;
- `HTTP_CLIENT_ERROR`: a final 400-499 response;
- `HTTP_SERVER_ERROR`: a final 500-599 response;
- `DESTINATION_REJECTED`, `DNS_FAILURE`, `CONNECT_TIMEOUT`, `READ_TIMEOUT`,
  `TLS_FAILURE`, `REDIRECT_REJECTED`, `TOO_MANY_REDIRECTS`,
  `RESPONSE_TOO_LARGE`, `NETWORK_FAILURE`, or `INTERNAL_FAILURE`.

Health is derived from consecutive conclusive outcomes:

- no current conclusive check, or a stale projection: `UNKNOWN`;
- a successful check: `HEALTHY` and the failure counter resets;
- one or two consecutive target failures: `DEGRADED`;
- three or more consecutive target failures: `BROKEN`.

`INTERNAL_FAILURE` does not blame the target or change health; it schedules a
retry after 30 seconds. All other failures are conclusive target outcomes.

Every claim and completed result is immutable. Finalization updates current
health and inserts a durable health-change event atomically only when derived
health changes. The stale sweep follows the same rule. Event transport is not
part of this MVP; PRD-0004 separately adopts and implements the direct BATON
HTTPS callback. BATON can still read the current projection over HTTP.

## Retention and observability

- Attempts and results are retained for 30 days by default.
- MVP attempt/result cleanup deletes bounded batches and never removes the
  current projection. PRD-0004 separately permits bounded retention cleanup of
  delivered health-change events only.
- Stale-projection marking and attempt/result retention run as independent
  maintenance tasks, so a failure in either operation does not prevent the
  other from remaining scheduled.
- Logs may include attempt correlation and bounded outcome/status values, but
  never raw URLs, hosts, queries, resource references, resolved addresses,
  response bodies, or exception messages.
- Apache HTTP header, wire, implementation, and TLS diagnostic logger
  categories remain disabled even when a broader application or Apache logger
  is configured at DEBUG. Operators must not override those protected logger
  categories.
- Unexpected scheduler failures propagate through Spring's scheduled-task
  observation before a redacting error handler suppresses them so the next
  fixed-delay execution remains scheduled. The handler logs only the exception
  class, never its message or stack trace.
- Metrics use only bounded outcome, protocol, health, scheduled class/method,
  and exception-class labels. They never use request data or exception messages.
- The Actuator `scheduledtasks` endpoint is not exposed. Runtime task diagnostics
  may retain the original exception for internal bookkeeping, while the adopted
  management surface remains limited to redacted health and Prometheus output.

## Acceptance criteria

1. Synchronizing a valid active monitor returns its `UNKNOWN` projection and
   makes it due.
2. Stale revisions and equal-revision conflicts cannot overwrite current state.
3. A background worker claims without holding a transaction during network I/O.
4. A successful final response produces immutable attempt/result records and
   `HEALTHY`.
5. Three consecutive failures produce `DEGRADED`, then `BROKEN`.
6. A health change and its durable event commit atomically.
7. Non-global or ambiguous destinations are rejected before connection,
   including after redirects.
8. Repeating finalization or losing a lease does not duplicate results or
   overwrite a newer projection.
9. The authenticated query route exposes only the documented projection.
