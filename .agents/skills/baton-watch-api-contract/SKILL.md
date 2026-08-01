---
name: baton-watch-api-contract
description: HTTP contract workflow for BATON WATCH. Use when adding or changing /api/v1 routes, request or response DTOs, validation, authentication exposure, error codes, status codes, pagination, idempotency, or focused controller and serialization tests.
---

# BATON WATCH API Contract

## Inspect and preserve the contract

- Read docs/PRD/0002_api-contract/spec.md, the affected controller/DTO, and its HTTP test.
- Read PRD-0001 and ADR-0001 when the endpoint changes product behavior or service ownership.
- Treat GET /api/v1/system/status as the only implemented route until code, tests, and PRD-0002 adopt another.
- Keep routes under /api/v1 and return named transport DTOs. Delegate to an inbound application port.
- Keep transport validation in the web adapter and authorization, state, and transaction rules in application or domain code.

## Change workflow

1. Define authentication, idempotency, response shape, errors, and compatibility before adding a command or query.
2. Do not expose response bodies, credentials, resolved addresses, raw exceptions, or authorization decisions owned by BATON.
3. Update PRD-0002 and focused MockMvc contract tests in the same change.
4. Assert exact status, content type, fields, enum values, and timestamp format.
5. Run ./gradlew :adapter-in-web:test, then widen when application or runtime wiring changed.

Do not invent future schedule, result, webhook, or event routes merely to complete the planned model.

