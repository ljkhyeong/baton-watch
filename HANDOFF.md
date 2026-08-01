# BATON WATCH Handoff

## Current state

- Six-module Java 21 / Spring Boot 4.1.0 skeleton is present.
- GET /api/v1/system/status is the only implemented HTTP capability.
- Application time comes from an injected UTC Clock.
- URL checks, schedules, persistence, derived health, and event delivery remain unimplemented.
- No frontend or broker is present.

## Verification

- Gradle 9.2.1 test: passed.
- Gradle 9.2.1 build and executable boot jar: passed.
- GET /api/v1/system/status boot-jar smoke check: HTTP 200 with the documented JSON fields.
- docker compose config --quiet: passed.
- All six project-local skills: quick_validate.py passed.

- The Gradle wrapper is present and pinned to 9.2.1.

## Next useful slice

Adopt concrete schedule, retry, health-derivation, retention, authentication, and delivery contracts before adding storage or outbound checking. The first checker implementation must include SSRF, DNS-rebinding, redirect, timeout, and response-size defenses from the product baseline and ADR.
