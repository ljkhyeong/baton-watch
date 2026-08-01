# PRD-0002: BATON WATCH API Contract

Status: maintained contract

Updated: 2026-08-01

## System status

`GET /api/v1/system/status` is unauthenticated and returns HTTP 200 with
`application/json`:

~~~json
{
  "service": "baton-watch",
  "status": "UP",
  "observedAt": "2026-08-01T00:00:00Z"
}
~~~

`observedAt` is a server-generated UTC instant serialized as ISO 8601. The route
exposes no resource data.

## Adopted monitoring routes

The following routes are adopted by PRD-0003 but are not implemented at the
time of this contract change:

- `PUT /api/v1/resource-monitors/{resourceReference}` synchronizes a snapshot
  and returns HTTP 200 with the current projection;
- `GET /api/v1/resource-monitors/{resourceReference}` returns that projection
  or HTTP 404.

Both require `Authorization: Bearer <token>`. PUT is idempotent by the tuple of
`resourceReference` and `sourceRevision`, so it does not accept a separate
idempotency key. The reference is 1-128 characters from `A-Z`, `a-z`, `0-9`,
`.`, `_`, `:`, and `-`.

PUT accepts `application/json`. An active snapshot is:

~~~json
{
  "sourceRevision": 42,
  "monitoringState": "ACTIVE",
  "targetUrl": "https://example.com/health"
}
~~~

An inactive snapshot uses `"monitoringState": "INACTIVE"` and must omit or set
`targetUrl` to null.

PUT and GET return `application/json`:

~~~json
{
  "resourceReference": "role-resource-123",
  "sourceRevision": 42,
  "monitoringState": "ACTIVE",
  "health": "UNKNOWN",
  "consecutiveFailures": 0,
  "lastOutcome": null,
  "lastCheckedAt": null,
  "nextCheckAt": "2026-08-01T00:00:00Z"
}
~~~

Validation failures return HTTP 400, stale revisions or equal-revision payload
conflicts return HTTP 409, invalid target policy returns HTTP 422, missing
monitors return HTTP 404, missing or invalid credentials return HTTP 401, and an
unexpected safe server failure returns HTTP 500. Errors use
`application/problem+json` with stable `type`, `title`, `status`, and `code`
fields. They never include a target URL, resolved address, credential, response
body, raw exception, or BATON authorization decision.

No attempt-history, manual-check, webhook, or event-delivery route is adopted.
A later query route must define cursor pagination before implementation.

All application routes remain under `/api/v1`, use named transport DTOs, and
delegate to an inbound application port.
