# ADR-0003: Direct HTTPS Health-Change Event Delivery

Status: accepted

Date: 2026-08-02

## Context

PRD-0003 and ADR-0002 establish a PostgreSQL outbox whose events are inserted
atomically with derived-health changes. Recording alone does not notify BATON,
and deleting or posting an event inside the health-finalization transaction
would either lose changes or make database work wait on a remote service.

The first consumer is one BATON service. The repository has no adopted need for
fan-out, replay by multiple consumers, or broker operations. Delivery still
needs durable retries, crash recovery, outbound-request safety, idempotency,
bounded resource use, retention, and operator visibility.

## Decision

Deliver each health-change event directly to one operator-configured BATON
callback using HTTPS `POST`. Do not introduce a message broker or an inbound
WATCH webhook route.

The callback uses a static bearer service token distinct from the monitor API
token, an `Idempotency-Key` header, and the exact, versioned JSON shape in
PRD-0004. The header and JSON `eventId` contain the same UUID. Any 2xx response
is an acknowledgement; redirects and every other outcome are delivery
failures. The guarantee is at least once, so BATON must make durable event-ID
deduplication atomic with its event effect. No delivery-order guarantee is
added.

The destination is a fixed absolute HTTPS URL on port 443 with a DNS hostname.
For every attempt, WATCH resolves the hostname, rejects the complete answer if
any address is not public-global, and pins the approved resolution while
preserving Host, SNI, and TLS hostname verification. Redirects, automatic
client retries, cookies, proxy discovery, and decompression are disabled.
Connection, read, total time, response headers and bytes, DNS work, HTTP
concurrency, and queues are bounded. Response content and raw exception text
are neither persisted nor exposed as metric dimensions.

Extend the durable outbox with mutable delivery lifecycle state. A dispatcher:

1. claims a bounded set of due, undelivered events in a short PostgreSQL
   transaction and assigns expiring leases;
2. commits before performing DNS or HTTP work;
3. finalizes each matching lease in another short transaction, marking a 2xx
   attempt delivered or persisting a bounded failure outcome and next retry.

Finalization is idempotent, and an expired claimant cannot overwrite a newer
lease. Failures use a bounded exponential backoff. There is no retry-count
discard threshold: undelivered events remain durable. Cleanup deletes bounded
batches of delivered events only after a configured retention period.

Export low-cardinality backlog, oldest-undelivered lag, and delivery-outcome
telemetry. Do not label metrics with callback URLs, hosts, resource references,
event IDs, exception messages, or response content.

## Consequences

The design closes the single-consumer delivery loop without operating a broker
and preserves the existing short-transaction boundary. It also makes callback
availability and correctness an explicit integration dependency. A prolonged
outage or bad token grows the retained backlog rather than losing events, so
operators must monitor lag and storage growth.

At-least-once delivery permits duplicates when acknowledgement is uncertain,
and bounded concurrency and retries may reorder events. BATON must deduplicate
and tolerate reordering; consumers needing the current value can reconcile
through WATCH's current projection.

The public-global destination rule means a private-only BATON endpoint is not a
valid callback. That is a deliberate SSRF and DNS-rebinding boundary, not an
operator bypass. Fan-out, broker-based delivery, private-network destinations,
payload expansion, or a different authentication mechanism requires another
adopted decision.

This architecture and its local implementation do not establish production
deployment, external alerts, secret rotation automation, or a frontend.

## Rejected alternatives

- Posting during health finalization was rejected because remote latency and
  failure would extend or roll back the database transaction.
- Marking before posting was rejected because a process stop could lose an
  event; marking only after acknowledgement is what creates at-least-once
  behavior.
- Polling alone was rejected because it leaves durable changes undispatched.
- A broker was deferred because the adopted requirement has one consumer and
  does not yet justify broker lifecycle and fan-out complexity.
