# PRD-0002: BATON WATCH API Contract

Status: maintained contract

Updated: 2026-08-11

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

The following routes are implemented under PRD-0003:

- `PUT /api/v1/resource-monitors/{resourceReference}` synchronizes a snapshot
  and returns HTTP 200 with the current projection;
- `GET /api/v1/resource-monitors/{resourceReference}` returns that projection
  or HTTP 404.

Both require `Authorization: Bearer <token>`. The configured token contains at
least 32 non-padding RFC 6750 `token68` characters and is parsed by Spring
Security's standard Bearer resolver. PUT is idempotent by the tuple of
`resourceReference` and `sourceRevision`, so it does not accept a separate
idempotency key. The reference is 1-128 characters from `A-Z`, `a-z`, `0-9`,
`.`, `_`, `:`, and `-`.

The Bearer authentication scheme is matched case-insensitively as required by
HTTP authentication semantics. A credential failure returns a
`WWW-Authenticate` Bearer challenge with the HTTP 401 problem response.

Only the exact system-status GET is public. Every other syntactically accepted
request under `/api/v1/**` crosses the stateless service-authentication boundary
before routing. The boundary is relative to the servlet context, so deploying
WATCH under a context path cannot expose a monitoring route.

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

After successful authentication, Spring MVC request rejections use the same
stable problem contract:

- malformed JSON or request validation: HTTP 400,
  `urn:baton-watch:problem:invalid-request`, `INVALID_REQUEST`;
- an unknown `/api/v1/**` route: HTTP 404,
  `urn:baton-watch:problem:route-not-found`, `ROUTE_NOT_FOUND`;
- an unsupported method: HTTP 405,
  `urn:baton-watch:problem:method-not-allowed`, `METHOD_NOT_ALLOWED`;
- an unacceptable response media type: HTTP 406,
  `urn:baton-watch:problem:not-acceptable`, `NOT_ACCEPTABLE`;
- an unsupported request media type: HTTP 415,
  `urn:baton-watch:problem:unsupported-media-type`,
  `UNSUPPORTED_MEDIA_TYPE`.

Any other Spring MVC client rejection preserves its HTTP 4xx status and uses
`urn:baton-watch:problem:request-rejected` with `REQUEST_REJECTED`. Unclassified
framework failures are reduced to the same safe HTTP 500 `INTERNAL_ERROR`
contract as application failures; framework-generated details and rejected
values are not returned. This reduction applies before response commitment. If
a framework failure is reported after status and body bytes are committed,
WATCH preserves that response, writes no second problem body, and logs only the
exception class without its message or stack trace.

Authentication still precedes all of these routing, body, and media-type
decisions, so a missing or invalid credential returns the existing HTTP 401
problem instead. HTTP-defined capability headers such as `Allow` and `Accept`
are preserved. The problem `instance`, when present, is the fixed redacted URN
`urn:baton-watch:request`, never the raw request path.

Requests that Spring Security's strict HTTP firewall rejects before path
matching are outside that authentication-first sequence. Ambiguous separators,
matrix syntax, and other suspicious path forms fail closed before authentication
with HTTP 400, `application/problem+json`,
`urn:baton-watch:problem:request-rejected`, and `REQUEST_REJECTED`. This response
uses the same fixed redacted `instance` and never includes the raw path,
resource reference, or firewall exception. The firewall policy is not relaxed.

No attempt-history, manual-check, inbound webhook, or event-delivery route is
adopted. PRD-0004 direct delivery is an outbound WATCH callback and does not
change these inbound routes.
A later query route must define cursor pagination before implementation.

All application routes remain under `/api/v1`, use named transport DTOs, and
delegate to an inbound application port.
