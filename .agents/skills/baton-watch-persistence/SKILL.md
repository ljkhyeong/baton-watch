---
name: baton-watch-persistence
description: Persistence and transaction workflow for BATON WATCH schedules, leases, attempts, results, derived health, and durable health-change events. Use for schema migrations, mappings, repositories, retention, concurrency, idempotency, transaction boundaries, or storage-related tests.
---

# BATON WATCH Persistence

## Model durable ownership

- Read PRD-0001 and ADR-0001 before selecting storage or schema.
- Persist schedule/lease state, attempt metadata, bounded result history,
  current derived health, and durable state-change events. For event delivery,
  preserve immutable payload fields separately from mutable `PENDING` /
  `DELIVERED`, attempt count, due time, lease, bounded outcome, HTTP status, and
  delivery time.
- Store UTC instants from an injected Clock. Never store response bodies, credentials, cookies, authorization headers, or BATON authorization state.
- Keep immutable attempt/result history distinct from the current derived-health projection.

## Preserve failure boundaries

1. Claim a bounded set of due items in a short transaction with an expiring lease or equivalent recovery.
2. Commit before DNS or network I/O.
3. Finalize one check idempotently in a short transaction.
4. Update derived health and insert its durable change event atomically only when the state changes.
5. Claim delivery in a short transaction, POST outside a transaction, and
   finalize the matching lease idempotently in another short transaction.
6. Use capped exponential retry and never discard an undelivered event because
   of its attempt count.

Use a new migration for each adopted schema change; never rewrite an applied
migration. Match mappings, nullability, indexes, uniqueness, lease queries, and
cleanup queries. Chunk retention work and avoid unbounded loads or deletes.
Event cleanup may select only delivered rows older than the strict cutoff;
pending or expired-leased rows remain durable.

## Verify

- Test check and delivery claim races, lease recovery, duplicate/stale
  finalization, state-change deduplication, capped retry boundaries,
  delivered-only retention chunks, and before/at/after time boundaries.
- Use the real adopted database for migration and concurrency integration tests.
- Run the narrowest persistence test, then ./gradlew test for cross-module changes.
