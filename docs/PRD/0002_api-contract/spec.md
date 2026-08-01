# PRD-0002: BATON WATCH API Contract

Status: maintained contract

Updated: 2026-08-01

## Current endpoint

### GET /api/v1/system/status

Returns HTTP 200 with application/json:

~~~json
{
  "service": "baton-watch",
  "status": "UP",
  "observedAt": "2026-08-01T00:00:00Z"
}
~~~

observedAt is a server-generated UTC instant serialized as ISO 8601. The endpoint currently has no authentication layer and exposes no resource data.

## Planned contracts

No URL-check command, schedule, result-query, webhook, or event-delivery HTTP route is adopted or implemented. Before adding one, define authentication, idempotency, pagination, error shape, compatibility, and whether the caller supplies a resource reference or URL snapshot. Update this document and add focused HTTP contract tests in the same change.

All future application routes must remain under /api/v1, use named transport DTOs, and delegate to an inbound application port. Do not expose response bodies, resolved IP addresses, credentials, or raw internal exception messages.
