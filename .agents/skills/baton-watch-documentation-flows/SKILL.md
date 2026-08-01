---
name: baton-watch-documentation-flows
description: Documentation workflow for BATON WATCH README.md, HANDOFF.md, AGENTS.md, PRDs, ADRs, links, implementation status, and service-boundary language. Use for documentation-only work or for synchronizing maintained documents with an implemented behavior change.
---

# BATON WATCH Documentation Flows

## Keep authority clear

- Use README.md for setup and the concise current capability summary.
- Use PRD-0001 for product scope, ownership, safety, and current versus planned behavior.
- Use PRD-0002 for adopted HTTP routes and DTO semantics.
- Use PRD-0003 for check scheduling/execution and PRD-0004 for the outbound
  health-change callback contract.
- Use ADRs for accepted durable architecture and trade-offs.
- Use HANDOFF.md only for active state, verification, and the next useful entry point.
- Use AGENTS.md for repository workflow and invariants, not product claims.

## Preserve honesty

- Compare implementation-shaped claims with code, tests, configuration, and routes.
- State that the PRD-0003 monitoring MVP is implemented, including schedules,
  checks, bounded history, derived health, and durable event recording.
- State that PRD-0004 direct HTTPS event delivery is implemented but disabled
  until an operator supplies its callback and separate bearer token.
- Label production deployment, external alerts, frontend, and broker as
  unimplemented.
- Never claim WATCH owns BATON authorization, stores response bodies, blocks BATON transactions, uses a broker, has a frontend, or is deployed.
- Keep the adopted delivery semantics precise: one public-global HTTPS
  callback, at-least-once delivery, event-ID idempotency at BATON, no redirects,
  durable retry leases, and retention of delivered events only.
- PRD-0003 owns check thresholds and retention; PRD-0004 owns delivery payload,
  authentication, safety, retry, retention, and observability.

Update the canonical document first, then synchronize only affected summaries. Check relative links, search for stale current/planned wording, and use git diff --check. Run application tests only when code or executable configuration also changed.
