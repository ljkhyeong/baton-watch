---
name: baton-watch-persistence
description: Persistence and transaction workflow for future BATON WATCH schedules, leases, attempts, results, derived health, and durable health-change events. Use for schema migrations, mappings, repositories, retention, concurrency, idempotency, transaction boundaries, or storage-related tests.
---

# BATON WATCH Persistence

## Model durable ownership

- Read PRD-0001 and ADR-0001 before selecting storage or schema.
- Persist schedule/lease state, attempt metadata, bounded result history, current derived health, and durable state-change events only after their contracts are adopted.
- Store UTC instants from an injected Clock. Never store response bodies, credentials, cookies, authorization headers, or BATON authorization state.
- Keep immutable attempt/result history distinct from the current derived-health projection.

## Preserve failure boundaries

1. Claim a bounded set of due items in a short transaction with an expiring lease or equivalent recovery.
2. Commit before DNS or network I/O.
3. Finalize one check idempotently in a short transaction.
4. Update derived health and insert its durable change event atomically only when the state changes.
5. Deliver events idempotently outside the finalize transaction and retain retry state.

Use a new migration for each adopted schema change; never rewrite an applied migration. Match mappings, nullability, indexes, uniqueness, lease queries, and cleanup queries. Chunk retention work and avoid unbounded loads or deletes.

## Verify

- Test claim races, lease recovery, duplicate finalization, state-change deduplication, retention chunks, and before/at/after time boundaries.
- Use the real adopted database for migration and concurrency integration tests.
- Run the narrowest persistence test, then ./gradlew test for cross-module changes.

