# Public Staging Health-Change Delivery Validation

Status: operator runbook; no live execution is implied

Updated: 2026-08-08

## Purpose

Use a dedicated public staging environment to prove the implemented WATCH to
BATON delivery boundary. The exercise verifies first delivery, an
acknowledgement-loss replay with the same event ID, and backlog drain. It does
not authorize production rollout.

## Required environment

- A deployed WATCH instance and a compatible BATON receiver at public DNS
  names with valid HTTPS on port 443.
- Delivery enabled in WATCH with the exact BATON callback and a URL-safe
  32-to-200-character bearer token. The delivery token must be different from
  the WATCH monitor API token and every BATON operator or workspace secret.
- A dedicated staging source namespace shared by BATON and WATCH.
- Operator-only access to WATCH PostgreSQL, BATON MySQL, the WATCH management
  port, and both services' logs. The management port must remain private.
- A controlled public check target that can return a stable successful
  response.
- A staging-only fault ingress in front of the BATON receiver. It must be able
  to forward one request until BATON commits, then discard or delay only that
  acknowledgement beyond WATCH's five-second total timeout. It must not retry
  upstream requests, follow redirects, log request bodies or authorization,
  or expose its control plane publicly without separate authentication.

Do not use `/etc/hosts`, an IP literal, a private callback, a non-default HTTPS
port, or a relaxed WATCH destination policy to make the exercise pass.

## Safe preflight

`https://watch-staging.b4ton.com` is the selected WATCH public-staging base
URL. Treat it only as the intended configuration value until its DNS record,
valid HTTPS routing, deployed instance, and external status response have all
been verified.

Load these values from the staging secret store without enabling shell tracing:

~~~bash
set +x
export WATCH_PUBLIC_BASE_URL=https://watch-staging.b4ton.com
export WATCH_EVENT_DELIVERY_ENABLED=true
export WATCH_EVENT_DELIVERY_ENDPOINT=https://baton.staging.example.com/api/v1/internal/resource-health-events
read -r -s -p 'WATCH API token: ' WATCH_API_TOKEN && export WATCH_API_TOKEN
read -r -s -p 'WATCH delivery token: ' WATCH_EVENT_DELIVERY_TOKEN && export WATCH_EVENT_DELIVERY_TOKEN
./ops/staging-event-delivery-preflight.sh
~~~

The preflight validates the compatible token shape and separation, checks the
public WATCH status endpoint, and sends a deliberately malformed JSON callback
request without credentials. The receiver must return `401`; a parser-level
4xx instead fails the preflight because authentication did not demonstrably
precede JSON deserialization. The script never sends either token and discards
response bodies. Its executable test fixes the callback method, content type,
malformed body, user-curl-configuration isolation, HTTPS-only protocol,
no-proxy behavior, TLS floor, deadlines, and output discard. A passing preflight
proves reachability and the externally observed receiver authentication
boundary only. It does not prove valid-credential acceptance, the deployed
WATCH configuration, or delivery behavior.

## Evidence rules

Run the exercise in a dedicated environment with an initial delivery backlog
of zero. Record only bounded counts, statuses, and timestamps in the retained
report. Event IDs may be used transiently to correlate database rows, but do
not retain target URLs, callback URLs, resource references, payloads, tokens,
headers, exception messages, or raw logs as evidence.

Prometheus names used below are:

- `baton_watch_event_delivery_backlog`
- `baton_watch_event_delivery_attempts_total{outcome="..."}`
- `baton_watch_event_delivery_finalizations_total{status="..."}`

The backlog gauge is refreshed by the one-minute maintenance schedule, so the
PostgreSQL row state is authoritative while waiting for the gauge to converge.
An absent counter series means zero before its first increment.

The scheduler's `replayed` log field is not BATON inbox replay evidence. It
counts WATCH finalizations that found an event already delivered.

The fault ingress must compare the first and replayed BATON `202` receipt in
memory and expose only bounded aggregate evidence: request count, unique event
count, receiver insert/replay/conflict counts, dropped acknowledgement count,
and a `sameReceipt` boolean. It must not retain or expose either raw receipt,
event ID, resource reference, payload, or authorization value.

## Phase 1: first delivery

1. Put the staging fault ingress in pass-through mode and reset its bounded
   counters.
2. Create a unique canonical reference of the form
   `baton-manager:<namespace>:role-resource:<uuid>` and synchronize an ACTIVE
   monitor at source revision 1 to the controlled public target. Direct WATCH
   synchronization is acceptable for this delivery-boundary exercise; use the
   normal post-commit BATON synchronization path when validating the wider
   integration.
3. Wait for the monitor to move from UNKNOWN to HEALTHY and for the related
   event to become DELIVERED.
4. Confirm one callback request, one BATON inbox insert, no conflict, one WATCH
   delivered finalization, and a return to the zero backlog baseline.

## Phase 2: acknowledgement-loss replay

1. Use a new canonical reference and arm the ingress for exactly one
   `ACK_THEN_DROP` action. It must forward the request to BATON and observe the
   receiver's 2xx response before dropping or delaying that response to WATCH.
2. Synchronize another ACTIVE revision-1 monitor and wait for its first health
   change.
3. Before removing the fault, confirm BATON committed one inbox row while the
   WATCH event remains PENDING with `delivery_attempt = 1`. The bounded WATCH
   outcome may be CONNECT_TIMEOUT, READ_TIMEOUT, or NETWORK_FAILURE depending
   on the transport phase in which the ingress loses the acknowledgement.
4. Return the ingress to pass-through mode and wait for the durable retry.
5. Confirm the ingress observed the same `Idempotency-Key` at least twice and
   reports one receiver insert, one exact replay, no conflict, and
   `sameReceipt = true`. Confirm the BATON inbox still has exactly one row,
   WATCH reports `delivery_attempt >= 2` and DELIVERED, and the backlog returns
   to zero. Any conflict, a second inbox row, or unequal receipt fails the
   exercise.

Useful WATCH evidence query, executed through an operator-only PostgreSQL
session:

~~~sql
SELECT event_id,
       delivery_status,
       delivery_attempt,
       last_delivery_outcome,
       last_http_status_code,
       next_attempt_at,
       delivered_at,
       delivery_lease_token
FROM watch_health_change_event
WHERE resource_reference = :'resource_reference'
ORDER BY changed_at DESC, event_id DESC
LIMIT 1;
~~~

Use the returned event ID only in a protected BATON MySQL session:

~~~sql
SELECT COUNT(*) AS inbox_rows
FROM watch_health_event_inbox
WHERE event_id = UUID_TO_BIN(?);
~~~

The single-row query proves durable deduplication, not replay by itself. The
ingress's two upstream observations and `sameReceipt` comparison provide the
replay evidence.

## Phase 3: backlog drain

1. Put the fault ingress in a mode that returns `503` without forwarding.
2. Create three more unique ACTIVE monitors and wait until PostgreSQL contains
   three PENDING events and the HTTP_SERVER_ERROR attempt counter increases.
3. Restore pass-through mode. Confirm all three events become DELIVERED, BATON
   gains exactly three unique inbox rows, and both PostgreSQL and the delayed
   Prometheus gauge return to the zero baseline.
4. Never delete, rewrite, or force-deliver a pending event to make the backlog
   appear healthy.

## Log and secret audit

Collect logs only into a mode-0600 temporary directory. Search both services
and the ingress for the delivery token, monitor API token, callback URL,
resource references, and request payload fields. Treat any match as a failure,
but do not print the matched line or upload raw logs. The ingress may retain
only bounded aggregate evidence such as request count, unique event count,
receiver insert/replay/conflict counts, dropped acknowledgement count, and
whether duplicate receipts matched.

## Cleanup and rollback

Always restore the fault ingress to pass-through mode. Synchronize every
temporary monitor to a higher INACTIVE revision and wait for any resulting
event to drain. If the receiver or ingress remains unhealthy, disable new
delivery claims and retain the pending rows; do not delete them. Rotate any
secret that appeared in a terminal transcript or log.

The exercise passes only when first delivery, same-event replay, single-row
BATON deduplication, backlog drain, and the log/secret audit all pass. Record
the execution date and bounded results in HANDOFF.md only after the live run.
