# BATON WATCH Handoff

## Current state

- The Java 21 / Spring Boot 4.1.0 monitoring MVP is implemented across six
  hexagonal Gradle modules.
- GET /api/v1/system/status and authenticated PUT/GET
  /api/v1/resource-monitors/{resourceReference} are implemented.
- Spring Security applies stateless service-token authentication to every
  non-status `/api/v1/**` request with context-path-aware matching. After
  authentication, Spring MVC 400/404/405/406/415 rejections use the stable
  redacted Problem Details contract while preserving standard `Allow` and
  `Accept` response headers. The strict HTTP firewall remains in front of that
  authentication boundary; suspicious path forms fail closed with a fixed
  redacted HTTP 400 Problem Details response while retaining Spring Security's
  request-rejection observation.
- Framework failures detected after an HTTP response is already committed do
  not re-enter Spring's default exception writer, preventing its fallback WARN
  log from restoring a raw exception message. Unexpected server failures still
  log only their bounded exception class.
- Application time comes from an injected UTC Clock.
- PostgreSQL stores revision-safe schedules, leases, immutable attempts/results,
  current derived health, and durable health-change events with pending/delivered
  state, retry timing, delivery attempts, and expiring leases.
- JDBC monitoring persistence is split along the application ports: monitor
  synchronization/projection/staleness and check claim/finalization/retention
  have separate adapters, while row mapping and caller-transaction event
  appending remain package-internal shared collaborators.
- The worker performs SSRF-safe, DNS-pinned outbound checks outside database
  transactions and handles staleness and bounded attempt retention. Monitor
  synchronization and redirect hops share the same static `TargetUrl` policy;
  legacy unsafe encodings can be rehydrated but are rejected before DNS or I/O.
- IPv6 destination approval fails closed against the IANA global-unicast
  allocation registry snapshot dated 2025-10-10. Unlisted, reserved, and
  carved-out special-purpose ranges are rejected before connection in both
  target checks and callback delivery. Deployment-specific RFC 6052 NAT64
  prefixes cannot be inferred from an address alone and remain subject to the
  required infrastructure egress policy.
- Target checks and event delivery share a neutral request-scoped Apache client,
  bounded deadline executor, pinned resolver, and bounded body discarder while
  retaining separate GET/redirect and POST/acknowledgement semantics.
- Apache HttpClient raw header, wire, implementation, and TLS diagnostic logger
  categories are fixed at `OFF`. Those child levels remain protective when root
  or the broader Apache HTTP package is raised to `DEBUG`; operators must not
  override the protected child categories.
- A real local TLS handshake verifies that the shared client connects only to
  the approved pinned address while preserving the original HTTP Host and TLS
  SNI. A certificate trusted by the test client succeeds only for its DNS SAN;
  the same certificate under a mismatched hostname is classified as
  `TLS_FAILURE` before the server handles an HTTP request.
- Response-byte accounting never consumes a probe byte beyond the configured
  allowance. Unknown-length responses that reach the allowance are rejected
  conservatively, oversized and header-limit failures are classified as
  `RESPONSE_TOO_LARGE`, and failed responses are aborted without Apache draining
  the remaining body.
- Allocation-sensitive response, header, DNS/request executor, and queue
  settings have hard implementation ceilings validated at bootstrap and again
  in the external adapter before resource allocation. Production check,
  delivery, and maintenance batch settings have separate bootstrap ceilings
  enforced before lease arithmetic and service execution. Bootstrap delegates
  non-sensitive independent numeric bounds and nested-property presence to
  Spring Boot configuration-properties Bean Validation, while secrets,
  positive-duration checks, conditional rules, cross-field comparisons, and
  overflow handling remain explicit redacted constructor checks.
- PRD-0004 direct delivery is implemented for one operator-configured BATON
  HTTPS callback: exact payload and idempotency header, separate bearer service
  authentication, public-global DNS pinning, no redirects, bounded resources,
  capped retries with a 30-day hard configuration ceiling, and delivered-only
  retention. The sender boundary accepts only immutable event payload data;
  lease and retry metadata stay internal. A shared application retry policy
  rejects larger delays before any event is claimed and owns the safely capped
  exponential calculation.
- A compatible BATON receiver with separate bearer authentication and an atomic
  immutable `eventId` inbox is implemented in the BATON repository. Its
  deployment and public WATCH-to-BATON integration are not yet verified.
- Delivery is disabled by default. Undelivered events remain durable while it
  is disabled or BATON is unavailable.
- Target checks, callback delivery, and maintenance run on separate named
  single-thread schedulers, so a slow callback batch cannot starve check polling
  or cleanup. Every scheduler inherits the 65-second graceful shutdown wait;
  local and staging Compose grant the process 110 seconds before forced
  termination, covering the default 30-second web shutdown phase, scheduler
  drain, and a bounded margin. Delivered-event cleanup and backlog refresh are
  independent methods on the maintenance lane, so one failure does not prevent
  the other task from remaining scheduled.
- Adapter-owned persistence transactions have a five-second JDBC statement
  deadline and a transaction-local one-second PostgreSQL row-lock timeout. They
  reject an existing outer Spring transaction before work starts, preserving
  the network-outside-transaction boundary.
- Boot's shared `JdbcTemplate` and auto-configured `JdbcClient` apply the same
  five-second statement deadline to non-transactional projection and event
  backlog reads.
- Separate-port Actuator health/Prometheus endpoints and low-cardinality check,
  scheduler, backlog, oldest-event-age, finalization, and delivery-outcome
  telemetry are configured; Compose does not publish the management port. No
  external alerting stack, frontend, broker, or production deployment is
  present. Scheduler errors use Spring's `tasks.scheduled.execution` timer;
  failures reach that observation before a redacting handler logs only their
  class and keeps the fixed-delay task scheduled. The redundant WATCH-owned
  scheduler-failure counter has no repository consumer and is removed. The
  management allowlist remains exactly `health,prometheus`; `scheduledtasks` is
  not exposed because Spring's internal task diagnostics retain the original
  exception.
- The GitHub Actions `Verify` job uses SHA-pinned official checkout, Java, and
  Gradle actions, requires Docker, runs a clean uncached test/build, and parses
  fresh JUnit XML to require all six PostgreSQL suites plus the production-root
  context smoke. Both Testcontainers entry points use the library's fail-closed
  Docker policy, so missing Docker is a test failure rather than a skip.
- The primary GitHub repository is public. `main` strictly requires the
  GitHub Actions `verify` check from app ID `15368`, enforces that requirement
  for administrators, and disallows force pushes and branch deletion without
  requiring a solo-repository review.
- Repository Actions policy allows only GitHub-owned actions and the
  SHA-pinned `gradle/actions/setup-gradle` action, requires full commit-SHA
  references, keeps the default workflow token read-only, and requires
  approval before any external contributor's fork workflow runs. Secret
  scanning and push protection are enabled with no open secret alert.
- The public repository was seeded only from the author/committer-email-
  normalized history. The previous repository identity remains a private
  history-only archive, and representative pre-rewrite commit SHAs are not
  resolvable through the public repository API.
- Staging deployment artifacts now define a SHA-tagged local WATCH image,
  mode-0600 Compose secret files, an external PostgreSQL volume, separate
  internal database and egress-capable edge networks, health checks, bounded
  resources and logs, and a Cloudflare Tunnel overlay. Both base and tunnel
  configurations publish no host ports. The staging configuration fixes
  health-change delivery to disabled. The runbook keeps the active external
  volume name in a separate single-assignment mode-0600 state file so a schema
  rollback remains selected across operator shells and repository updates.
- `https://watch-staging.b4ton.com` remains an intended staging URL, not a live
  deployment claim. The current Mac has not authenticated a Cloudflare Tunnel
  connector, a remotely managed tunnel and its connector token have not been
  provisioned, and live DNS/TLS/origin routing has not been externally
  verified.

## Verification

- Gradle 9.2.1 `clean test :bootstrap:bootJar --no-build-cache` with two workers
  and a 512 MiB Gradle heap: 368 tests passed with no skips,
  failures, or errors, including the Docker-backed PostgreSQL Testcontainers
  suite.
- The real `BatonWatchApplication` root context started against a
  service-connected PostgreSQL 18.4 container with Flyway V1/V2, Spring
  Security, all three persistence adapters, outbound check and delivery
  clients, enabled delivery workers, and all three named schedulers. Its HTTP
  smoke proved public status access, unauthenticated PUT rejection without a
  write, authenticated INACTIVE synchronization, authenticated projection
  readback, and the persisted UNKNOWN/INACTIVE row without attempts or events.
- The same result-evidence parser used by CI verified seven required suites and
  33 tests: six PostgreSQL suites/32 tests and the one production-root smoke.
- The first clean public-repository `Verify / verify` run for commit `f5502a7`
  passed every step in 1 minute 32 seconds, including Docker preflight, clean
  uncached tests, boot jar creation, and required-suite evidence validation.
- Executable boot jar: passed.
- Spring Boot JDBC and transaction auto-configuration: passed with Boot-managed
  `JdbcTemplate`, `JdbcClient`, five-second query timeout, and
  `PlatformTransactionManager`, plus the WATCH-owned bounded PostgreSQL
  `TransactionOperations` wiring all three persistence adapters.
- Outbound checker and callback adapter suite: 238 tests passed without a
  live-internet dependency, including exact consumed-byte accounting,
  no-drain response abort, header count/line classification, resource ceiling
  boundaries, every current IANA-allocated IPv6 range boundary, reserved and
  unallocated IPv6 rejection before check or delivery transport,
  named daemon platform-thread creation for bounded DNS and request executors,
  pinned-address TLS Host/SNI preservation, DNS SAN verification, and
  trusted-certificate hostname-mismatch classification.
- Bootstrap logging regression loads the production configuration through
  Spring Boot's ConfigData and final log-level application path without
  mutating the test JVM's logger state. With root and the broader Apache HTTP
  package forced to `DEBUG`, raw header/wire and representative
  implementation/TLS loggers remain `OFF`.
- PostgreSQL 18.4 Testcontainers suite: 32 tests passed, including V1/V2
  migration, revision races, deterministic locked-row skipping for check and
  delivery claims, disjoint concurrent claims, concurrent finalization
  idempotency, delivery token/attempt stale rejection, batch check- and
  delivery-claim rollback, check and delivery lease recovery, atomic event
  rollback across finalization, synchronization, and staleness, retry
  boundaries, backlog state, delivered-only retention, row-lock timeout,
  transaction-deadline rollback, transaction-local setting restoration,
  bounded non-transactional projection and backlog reads, and outer-transaction
  rejection.
- Named scheduler tests verify independent single-thread execution, owned
  thread prefixes, shutdown policies, explicit routing of all five scheduled
  methods, `outcome=ERROR` framework observation, redacted failure logging, and
  continued fixed-delay execution after a failure.
- Delivery retry policy tests verify first, exponential, and maximum-attempt
  delays plus the exact 30-day accepted and 30-day-plus-one-nanosecond rejected
  configuration boundary before a persistence claim.
- Configuration-properties binding tests verify that Spring rejects invalid
  monitoring and delivery batch limits plus nested HTTP executor bounds with
  field-specific validation failures.
- Executable boot jar and clean Docker multi-stage build: passed.
- Isolated Compose delivery smoke: PostgreSQL became healthy, Flyway V1 and V2
  applied, the application status and separate management health endpoints were
  UP, active sync returned UNKNOWN, and the asynchronous check reached
  SUCCESS/HEALTHY.
- With delivery disabled, the smoke database contained one PENDING event with
  delivery attempt zero and no lease; the Prometheus delivery backlog gauge was
  `1.0`, matching the database count.
- Smoke logs contained none of the exercised target URL, resource reference, or
  bearer token values. The test containers stopped cleanly, and their network
  and PostgreSQL volume were removed afterward.
- docker compose config --quiet: passed. Smoke containers, network, and test
  volume were removed afterward.
- All six project-local skills passed quick_validate.py after documentation
  synchronization.
- The public-staging preflight sends malformed JSON without credentials and
  requires `401`, externally demonstrating rejection before a JSON parser
  error. Its executable nine-case shell test also fixes token separation,
  child-environment isolation, hostname shape, POST/content-type/body, user
  curl configuration, HTTPS-only/no-proxy/TLS/deadline/output controls, and
  HTTP-status handling. A live receiver or public delivery run has not yet
  been verified.
- `https://watch-staging.b4ton.com` is the selected public-staging WATCH base
  URL. Its DNS record, valid HTTPS routing, deployed instance, and externally
  successful status response have not been provisioned or verified, so the
  hostname is not evidence of a live deployment.
- Public-staging readiness was checked without printing values. None of the
  five required local variables was present, and the GitHub repository had no
  Actions secrets or environments. No BATON callback, distinct operator
  tokens, database/log access, or acknowledgement-loss ingress was available,
  so the runbook was not executed against an external system.
- The monitor API authentication boundary has a real embedded-server test under
  a non-empty servlet context path. It verifies the Bearer challenge, exact 401
  problem fields,
  fail-closed `/api/v1/**` handling, statelessness, public status access, and
  authenticated PUT without CSRF. The same server test verifies that strict
  firewall rejection of semicolon and duplicate-slash paths returns a fixed
  redacted HTTP 400 problem without exposing the rejected path or resource
  reference. A focused exception-handler test proves that a committed response
  cannot trigger Spring's raw exception-message fallback log.
- Staging Compose artifacts are available as `compose.staging.yml`,
  `compose.staging-tunnel.yml`, and `ops/staging.env.example`. Their merged
  configuration is designed to require an immutable WATCH image, external
  PostgreSQL volume, and three file-backed secrets while publishing no host
  port in either mode. This is static artifact evidence only; no live
  Cloudflare connector or external endpoint test has passed yet.
- The executable staging Compose policy test passed for both the base and
  tunnel-rendered models. An isolated base-origin smoke then used mode-0600 test
  secrets, a dedicated external volume, and the hardened image: PostgreSQL and
  WATCH became healthy, Flyway V1/V2 were present, internal status and
  readiness were `UP`, the edge-network status path succeeded, unauthenticated
  monitor access returned `401`, and both containers had empty host port
  bindings. WATCH environment inspection contained no password or token value,
  and bounded logs contained none of the exercised secrets or prohibited
  request-data categories.
- A graceful stop preserved the external volume; the same image and volume
  restarted healthy with both migration records intact. Compose shutdown again
  preserved the volume before the explicitly named test volume, test image,
  containers, networks, and temporary secret files were removed. This proves a
  local origin path only, not a Cloudflare connector or public deployment.

## Next useful slice

Authenticate the current Mac to the `b4ton.com` Cloudflare account, create the
remotely managed staging tunnel, install its connector token and the independent
database/API secret files with mode 0600, initialize the active-volume state,
create the external PostgreSQL volume, and build the selected clean Git SHA
locally. Configure
`watch-staging.b4ton.com` with status and monitor paths routed to
`http://watch:8080`, a final `404` catch-all, cache bypass, and an Active edge
certificate. Then deploy with both staging Compose files and verify internal
application/database health plus external status, unauthenticated `401`, TLS, cache, and log
redaction while delivery remains disabled. Only after that should the separate
BATON callback and acknowledgement-loss delivery exercise be provisioned. Do
not infer a deployment from repository artifacts or Cloudflare configuration
alone.
