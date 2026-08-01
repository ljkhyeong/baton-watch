# BATON WATCH

BATON WATCH is an independent Java/Spring service boundary for future asynchronous health checks of BATON RoleResource URLs.

## Current implementation

This repository is a buildable project skeleton. It implements only:

- GET /api/v1/system/status
- a hexagonal six-module Gradle layout
- focused application and HTTP contract tests

URL scheduling, outbound checks, persistence, derived resource health, and health-change delivery are planned and are not implemented.

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
./gradlew :bootstrap:bootRun
curl http://localhost:8080/api/v1/system/status
~~~

For a local container run:

~~~bash
cp .env.example .env
docker compose up --build
~~~

Compose starts only BATON WATCH. There is no frontend, database, or broker in the current skeleton.

## Maintained documents

- [Product baseline](docs/PRD/0001_product-baseline/spec.md)
- [API contract](docs/PRD/0002_api-contract/spec.md)
- [Monitoring MVP](docs/PRD/0003_monitoring-mvp/spec.md)
- [Microservice boundary ADR](docs/ADR/0001_microservice-boundary/adr.md)
- [MVP storage and execution ADR](docs/ADR/0002_monitoring-mvp-storage-and-execution/adr.md)
- [Active handoff](HANDOFF.md)
