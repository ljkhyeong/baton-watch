# PRD-0004: Health-Change Event Delivery

Status: accepted

Date: 2026-08-02

Implementation: complete; production deployment remains out of scope

## Goal

WATCH delivers each durable health-change event to one operator-configured
BATON HTTPS callback. Delivery is asynchronous, at least once, and independent
of BATON commands, projections, and end-user authorization.

This contract adds no inbound WATCH route, frontend, or message broker. It does
not make WATCH authoritative for RoleResource content or authorization.

## Activation and destination

- Delivery is active only when the operator enables it and supplies one
  callback URL and one bearer service token. When it is inactive, durable
  events remain pending and no callback is attempted.
- The callback is one fixed, absolute `https` URL with a DNS hostname and
  default port 443. An explicit `:443` is allowed.
- User-info, query, fragment, IP-literal hosts, ambiguous authorities, control
  characters, and backslashes are rejected.
- The token is a runtime secret. It must not appear in the URL, persisted event
  state, logs, metrics, or repository defaults. While delivery is enabled it
  contains at least 32 printable, non-whitespace ASCII characters and must not
  equal the monitor API token.

The callback must resolve through public-global DNS under the outbound policy
below. An operator who needs a private BATON endpoint must provide a compliant
public ingress or adopt a different transport contract; this implementation
does not weaken destination validation for private networks.

## Callback request

WATCH sends `POST` with `Content-Type: application/json`,
`Authorization: Bearer <configured-service-token>`, and
`Idempotency-Key: <eventId>`. The `Idempotency-Key` value is the same UUID as
the JSON `eventId`. The JSON object contains exactly these fields:

~~~json
{
  "eventId": "8cf76651-f98d-4755-b578-1629b0ca2f55",
  "eventType": "RESOURCE_HEALTH_CHANGED",
  "resourceReference": "role-resource-123",
  "sourceRevision": 7,
  "attemptId": "81ccb9da-f9f9-4abc-87fe-cf6193ee5f79",
  "previousHealth": "DEGRADED",
  "currentHealth": "BROKEN",
  "changedAt": "2026-08-02T03:04:05Z"
}
~~~

- `eventId` is the immutable event UUID and idempotency key. BATON rejects a
  request whose `Idempotency-Key` header does not match this field.
- `eventType` is always `RESOURCE_HEALTH_CHANGED` in this contract.
- `resourceReference` is BATON's opaque reference; it conveys no WATCH
  authorization decision.
- `sourceRevision` is the non-negative BATON revision attached to the monitor
  snapshot that produced the event.
- `attemptId` is present only when a completed check produced the change. It is
  omitted for changes such as staleness or monitor resynchronization.
- `previousHealth` and `currentHealth` are distinct values from `UNKNOWN`,
  `HEALTHY`, `DEGRADED`, and `BROKEN`.
- `changedAt` is the event's UTC instant in RFC 3339 form.

The payload never contains a target URL, response body, resolved address,
check outcome, exception, credential, cookie, authorization header, lease
token, or delivery-attempt metadata.

## Acknowledgement and idempotency

- Any final HTTP 2xx response acknowledges the event. WATCH then marks it
  delivered in a short transaction.
- A 3xx response is never followed. Every non-2xx response, policy rejection,
  timeout, DNS/TLS/network failure, size violation, or internal failure leaves
  the event undelivered and schedules another attempt.
- BATON must durably deduplicate by `eventId` before returning 2xx and make the
  deduplication record atomic with any effect it applies.
- WATCH can send the same event more than once, including when BATON accepted a
  request but WATCH lost the response or stopped before finalizing delivery.
- Delivery order is not a correctness guarantee. A consumer that projects
  current health must tolerate reordering and may reconcile through WATCH's
  authenticated current-projection route.

The application does not apply an independent automatic HTTP-client retry. All
retries pass through the durable delivery state.

## Destination and request safety

Before every connection, WATCH must:

1. validate the fixed callback URI against the HTTPS and port policy;
2. resolve its hostname in a bounded DNS executor;
3. reject the entire answer when any address is loopback, link-local, private,
   multicast, unspecified, metadata, or otherwise non-global;
4. pin the connection to the approved resolution while preserving the original
   hostname for HTTP Host, SNI, and TLS hostname verification;
5. disable redirects, cookies, proxy discovery, response decompression, and
   transport-level retries;
6. bound connection, response, and total time, response headers and bytes, DNS
   work, request concurrency, and queued work.

The initial per-process limits are a 2-second connection timeout, 3-second
response timeout, 5-second total timeout, 8 KiB consumed response limit, at
most 100 response headers of at most 8 KiB per line, two DNS threads with eight
queued lookups, and one HTTP request thread with one queued request. These are
bounded runtime settings and may not be disabled while delivery is active.
Response bodies are discarded and never persisted.

## Durable delivery lifecycle

- The event payload is immutable. A new event starts `PENDING`, due at its
  `changedAt` instant. Mutable delivery state records `PENDING` or `DELIVERED`,
  the attempt count, next-attempt time, expiring lease, and a bounded last
  outcome without storing response content or exception text.
- A dispatcher claims a bounded batch of due, undelivered events in a short
  transaction and assigns an expiring lease. The callback POST runs only after
  that transaction commits.
- Success or failure is finalized in another short transaction. Finalization
  verifies the event and lease token, is idempotent, and cannot let an expired
  worker overwrite a newer claim.
- An expired lease becomes claimable again. Disabling delivery stops new claims
  but does not delete or rewrite pending events.
- Failure schedules `min(initialDelay * 2^(attemptCount - 1), maxDelay)` using a
  safely capped exponent. Both delays are positive bounded configuration, and
  retry count is not used to discard an event.

The persisted and metric outcome taxonomy is bounded to `DELIVERED`,
`HTTP_CLIENT_ERROR` for 3xx-4xx, `HTTP_SERVER_ERROR` for 5xx,
`DESTINATION_REJECTED`, `DNS_FAILURE`, `CONNECT_TIMEOUT`, `READ_TIMEOUT`,
`TLS_FAILURE`, `RESPONSE_TOO_LARGE`, `NETWORK_FAILURE`, and
`INTERNAL_FAILURE`. An HTTP status is retained only for the first three outcome
classes; response headers, bodies, and exception text are not retained.

The initial dispatcher settings are a 1-second poll interval, 60-second lease,
batch size 10, 5-second initial retry delay, and 15-minute maximum retry delay.
Delivered-event cleanup runs every minute with a batch size of 100 and a
30-day retention period.

Only delivered events older than the configured positive retention period may
be deleted, in bounded batches. Pending, retrying, and leased-but-unfinalized
events are retained. Cleanup therefore cannot turn a prolonged callback outage
or configuration error into silent event loss.

## Observability

WATCH exposes low-cardinality telemetry for:

- the count of undelivered events and the age or lag of the oldest undelivered
  event;
- callback attempts grouped only by the bounded delivery outcome above;
- bounded claim/finalization state needed to distinguish idle delivery from a
  stuck or failing dispatcher.

Raw callback URLs, hosts, resource references, event IDs, exception messages,
and HTTP response content are never metric labels. Logs may use `eventId` as a
correlation value but must not include the callback URL, bearer token, resource
reference, response body, or raw exception message. Repository telemetry does
not imply that an external monitoring stack or alert is deployed.

## Acceptance criteria

1. Enabling delivery without a valid callback URL and distinct service token
   fails configuration validation; disabling it performs no network request.
2. A claim commits before DNS or HTTP work and an expiring lease recovers work
   after a stopped dispatcher.
3. The callback receives the adopted application headers, including a matching
   `Idempotency-Key`, and only the adopted JSON fields.
4. Public-global DNS validation and pinning happen before connection; redirects
   and private, ambiguous, or oversized responses are rejected.
5. A 2xx response marks the matching leased event delivered exactly once in
   storage even when finalization is repeated.
6. Every unsuccessful attempt persists a bounded outcome and capped
   exponential retry time without deleting the event.
7. A duplicate POST has the same `eventId`, allowing BATON to deduplicate its
   effects.
8. Retention deletes only delivered events older than the cutoff and only in a
   bounded batch.
9. Backlog, oldest-event lag, and attempt outcomes are observable without
   sensitive or unbounded metric labels.
