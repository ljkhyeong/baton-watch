---
name: baton-watch-observability
description: Observability workflow for BATON WATCH logs, metrics, traces, dashboards, alerts, request correlation, scheduler health, check outcomes, and durable event delivery. Use when adding telemetry or diagnosing runtime behavior without leaking target URLs or creating unbounded labels.
---

# BATON WATCH Observability

## Protect data and cardinality

- Read PRD-0001, ADR-0001, and the affected telemetry source before editing.
- Never use raw URLs, hosts, queries, fragments, resource or event IDs,
  resolved IPs, exception messages, or stack-trace text as metric labels.
- Prefer bounded labels such as outcome class, protocol, worker operation, and derived status.
- Redact URL user-info, query, and fragment in logs. Never log response bodies, credentials, cookies, or authorization headers.
- Preserve request or attempt correlation without turning high-cardinality identifiers into metric dimensions.

## Cover operational failure

- Add metrics for due backlog age, claim count, in-flight checks, outcome counts,
  duration, timeouts, rejected destinations, lease recovery, and finalization
  failures. Preserve the implemented event-delivery backlog,
  oldest-undelivered age, bounded outcome, and finalization telemetry.
- Distinguish DNS, connect, TLS, redirect-policy, HTTP-class, timeout, size-limit, and internal failures with a bounded taxonomy.
- Alert on sustained user-impacting conditions, not one transient target failure.
- Keep dashboard queries and alerts synchronized with exported metric names.

Add tests for tag sets, redaction, gauge refresh, and counter emission at
critical failure boundaries. Verify the private management health/Prometheus
endpoints and alert configuration when introduced. Do not document a
monitoring stack or external alert as deployed merely because instrumentation
or configuration exists.
