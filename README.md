# BATON WATCH

BATON WATCH is an independent Java/Spring service for asynchronous health checks
of BATON RoleResource URL snapshots.

## Current implementation

The monitoring MVP implements:

- GET /api/v1/system/status
- authenticated PUT and GET /api/v1/resource-monitors/{resourceReference}
- revision-safe ACTIVE/INACTIVE monitor synchronization
- PostgreSQL schedules, leases, immutable attempts/results, current health, and
  durable health-change events
- a background checker with DNS pinning, SSRF and redirect defenses, bounded
  time/headers/bytes, stale projection handling, and bounded retention
- a hexagonal six-module Gradle layout
- focused domain, application, HTTP, outbound-policy, and PostgreSQL integration
  tests

Health-change event delivery is not implemented. Events remain in the database
until a transport contract is adopted. WATCH has no frontend or broker and is
not evidence of a production deployment.

## Technology and modules

- Java 21
- Spring Boot 4.1.0
- Gradle 9.2.1

Production dependencies point inward:

bootstrap -> adapters -> application -> domain

The modules are domain, application, adapter-in-web, adapter-out-persistence, adapter-out-external, and bootstrap.

## Build and run

The repository includes a Gradle wrapper pinned to 9.2.1.

~~~bash
./gradlew test
~~~

For the recommended local container run, copy the environment template and
replace both placeholder secrets before starting:

~~~bash
cp .env.example .env
docker compose up --build
curl http://localhost:8080/api/v1/system/status
~~~

Compose starts WATCH and a private PostgreSQL 18.4 service. The database port is
not published. To synchronize a monitor:

~~~bash
curl -X PUT http://localhost:8080/api/v1/resource-monitors/role-resource-123 \
  -H 'Authorization: Bearer replace-with-your-token' \
  -H 'Content-Type: application/json' \
  -d '{"sourceRevision":1,"monitoringState":"ACTIVE","targetUrl":"https://example.com/"}'
~~~

BATON must call synchronization only after its own transaction commits. A later
revision may use `INACTIVE` with no `targetUrl` to stop future checks while
retaining bounded history.

## Maintained documents

- [Product baseline](docs/PRD/0001_product-baseline/spec.md)
- [API contract](docs/PRD/0002_api-contract/spec.md)
- [Monitoring MVP](docs/PRD/0003_monitoring-mvp/spec.md)
- [Microservice boundary ADR](docs/ADR/0001_microservice-boundary/adr.md)
- [MVP storage and execution ADR](docs/ADR/0002_monitoring-mvp-storage-and-execution/adr.md)
- [Active handoff](HANDOFF.md)
