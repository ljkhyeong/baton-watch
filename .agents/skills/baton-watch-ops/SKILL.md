---
name: baton-watch-ops
description: Runtime and deployment workflow for BATON WATCH outbound-check workers, schedules, containers, configuration, graceful shutdown, concurrency, network policy, secrets, rollout, rollback, and local Compose behavior. Use for operational or infrastructure changes and checker safety controls.
---

# BATON WATCH Operations

## Distinguish current and target state

- Read README.md, HANDOFF.md, PRD-0001, and ADR-0001.
- Treat compose.yml as a local single-service artifact, not proof of production deployment.
- Keep secrets out of Git, images, Compose defaults, logs, and URLs.
- Do not add a frontend or broker without an adopted requirement.

## Secure outbound checks

- Allow only adopted schemes and ports; reject ambiguous hosts and embedded credentials.
- Resolve and reject loopback, link-local, private, multicast, unspecified, metadata, and other non-public destinations.
- Pin connection attempts to the approved resolution while preserving HTTP Host and TLS verification.
- Revalidate every redirect. Bound redirect count, connection/read/total time, bytes, concurrency, and per-target pressure.
- Treat DNS rebinding and alternate IPv4/IPv6 forms as hostile. Prefer outbound network controls as defense in depth.

## Operate workers safely

- Keep claim, check, and finalize as separate phases; never hold a database transaction during network I/O.
- Use leases, idempotent finalization, bounded batches, backpressure, and graceful shutdown that stops claims and drains bounded in-flight work.
- Inject Clock; make schedule zones and timeout units explicit.
- Use immutable image tags or digests, private app/data/management ports, probes, least privilege, and a tested rollback path when deployment is adopted.

Verify ./gradlew test, docker compose config, image build, startup/status smoke check, shutdown, concurrency limits, and rollback in proportion to the change. Report external DNS, firewall, credentials, and live rollout as operator work until actually verified.

