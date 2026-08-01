---
name: baton-watch-documentation-flows
description: Documentation workflow for BATON WATCH README.md, HANDOFF.md, AGENTS.md, PRDs, ADRs, links, implementation status, and service-boundary language. Use for documentation-only work or for synchronizing maintained documents with an implemented behavior change.
---

# BATON WATCH Documentation Flows

## Keep authority clear

- Use README.md for setup and the concise current capability summary.
- Use PRD-0001 for product scope, ownership, safety, and current versus planned behavior.
- Use PRD-0002 for adopted HTTP routes and DTO semantics.
- Use ADRs for accepted durable architecture and trade-offs.
- Use HANDOFF.md only for active state, verification, and the next useful entry point.
- Use AGENTS.md for repository workflow and invariants, not product claims.

## Preserve honesty

- Compare implementation-shaped claims with code, tests, configuration, and routes.
- State that the PRD-0003 monitoring MVP is implemented, including schedules,
  checks, bounded history, derived health, and durable event recording.
- Label health-change event delivery and production deployment as unimplemented.
- Never claim WATCH owns BATON authorization, stores response bodies, blocks BATON transactions, uses a broker, has a frontend, or is deployed.
- Keep event transport and production infrastructure undecided until adopted;
  PRD-0003 owns the current thresholds, retention, and authentication contract.

Update the canonical document first, then synchronize only affected summaries. Check relative links, search for stale current/planned wording, and use git diff --check. Run application tests only when code or executable configuration also changed.
