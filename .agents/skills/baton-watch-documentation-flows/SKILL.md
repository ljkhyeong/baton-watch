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
- State that only the project skeleton and GET /api/v1/system/status are implemented until that changes.
- Label URL schedules/checks, attempt/result history, derived health, and durable events as planned.
- Never claim WATCH owns BATON authorization, stores response bodies, blocks BATON transactions, uses a broker, has a frontend, or is deployed.
- Keep future thresholds, retention, authentication, event transport, and infrastructure undecided until adopted.

Update the canonical document first, then synchronize only affected summaries. Check relative links, search for stale current/planned wording, and use git diff --check. Run application tests only when code or executable configuration also changed.

