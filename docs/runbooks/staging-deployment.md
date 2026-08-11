# Cloudflare Tunnel Staging Deployment

Status: operator runbook; repository artifacts are not proof of a live deployment

Updated: 2026-08-08

## Purpose and current boundary

This runbook prepares the current Mac as a single staging origin for
`https://watch-staging.b4ton.com`. The intended edge path is a remotely managed
Cloudflare Tunnel into the Docker `watch-edge` network. The origin publishes no
host port when the tunnel overlay is used.

The repository contains the staging Compose definitions and this procedure,
but the Cloudflare account has not been authenticated from the origin, no
tunnel token has been installed, and no live deployment or public HTTPS smoke
has been verified. The Mac must remain powered, awake, connected, and running
Docker for the staging origin to be available. This is not a production or
high-availability topology.

The deployment uses:

- [compose.staging.yml](../../compose.staging.yml) for PostgreSQL and WATCH;
- [compose.staging-tunnel.yml](../../compose.staging-tunnel.yml) to add
  `cloudflared` without changing the no-host-port boundary;
- [ops/staging.env.example](../../ops/staging.env.example) as a non-secret
  environment template;
- one locally built, immutable `baton-watch:<full-git-sha>` image;
- one operator-created external PostgreSQL volume; and
- three mode-0600 secret files mounted as Compose secrets.

Health-change delivery stays disabled in this staging slice. The Compose file
fixes `WATCH_EVENT_DELIVERY_ENABLED=false`; do not add a BATON callback or
delivery token while following this runbook. Use the separate
[public staging delivery validation runbook](public-staging-event-delivery.md)
only after a compatible BATON receiver has been provisioned.

## Network and persistence invariants

With both Compose files applied:

- PostgreSQL has no host port and joins only the internal `watch-db` network.
- WATCH has no host port, joins `watch-db` and `watch-edge`, and exposes only
  port 8080 to containers on those networks.
- The management server remains bound to `127.0.0.1:8081` inside the WATCH
  container and is not reachable through the tunnel.
- `cloudflared` joins only `watch-edge`, has no host port, and reaches WATCH at
  `http://watch:8080`.
- The Mac needs no inbound firewall or router port forwarding. Permit outbound
  UDP 7844 for QUIC and TCP 7844 for the tunnel fallback. WATCH separately
  needs the already intended DNS and public HTTP/HTTPS egress for target checks.
- PostgreSQL data resides in the external volume named by
  `WATCH_POSTGRES_VOLUME_NAME`. Routine shutdown and rollback must not delete
  that volume.

The base staging Compose file also publishes no host ports. Internal diagnostics
use `docker compose exec` or a one-off container on `watch-edge`. A public
staging deployment must always use both files because the base file alone has
no public ingress.

## One-time Cloudflare setup

Use a remotely managed tunnel. The Cloudflare dashboard/API is the source of
truth for ingress; do not add an origin certificate, tunnel credentials JSON,
or a local ingress configuration file to this repository.

1. Authenticate an operator account that can manage the `b4ton.com` zone.
2. Create a dedicated staging tunnel and obtain its connector token. Store
   only that token in the secret file described below.
3. Configure these ordered ingress rules:

   | Order | Hostname | Path | Service |
   | --- | --- | --- | --- |
   | 1 | `watch-staging.b4ton.com` | `^/api/v1/system/status$` | `http://watch:8080` |
   | 2 | `watch-staging.b4ton.com` | `^/api/v1/resource-monitors/.*$` | `http://watch:8080` |
   | 3 | catch-all | catch-all | `http_status:404` |

   Keep the catch-all last. Do not route `/actuator`, the management port, or
   an unrestricted hostname to WATCH.
4. Ensure the public hostname creates a proxied DNS route to the tunnel. It
   must not expose the Mac's address.
5. Add a Cloudflare cache rule that bypasses cache for
   `watch-staging.b4ton.com`. Status and authenticated monitor responses must
   never be served from an edge cache.
6. Confirm the edge certificate for `watch-staging.b4ton.com` is Active before
   deployment. The public smoke below must complete normal hostname and trust
   verification; never use `curl -k` or disable TLS verification.

Do not add Cloudflare Access in front of these routes. WATCH's status endpoint
is intentionally public, and monitor routes use the BATON-to-WATCH bearer
contract. A separate edge authentication flow would change their observable
HTTP contract.

Cloudflare configuration, DNS, and an Active certificate still do not prove
that the origin is running. Retain both internal and external smoke evidence.

## Prepare the Mac

Run from a clean checkout of the exact commit to deploy. Keep shell tracing
disabled throughout secret handling.

~~~bash
set -euo pipefail
set +x
umask 077
test -z "$(git status --porcelain)"
DEPLOY_SHA="$(git rev-parse --verify HEAD)"
test "$(printf '%s' "$DEPLOY_SHA" | wc -c | tr -d ' ')" = 40
export DEPLOY_SHA
export WATCH_IMAGE_REVISION="$DEPLOY_SHA"
export WATCH_IMAGE="baton-watch:${WATCH_IMAGE_REVISION}"
export STAGING_CONFIG_DIR="${HOME}/.config/baton-watch/staging"
export STAGING_ENV_FILE="${STAGING_CONFIG_DIR}/staging.env"
export STAGING_STATE_FILE="${STAGING_CONFIG_DIR}/staging-state.env"
~~~

Install the non-secret environment file and create the secret directory. Keep
all five files outside the repository. The separate state file retains the
currently active PostgreSQL volume across operator shells and repository
updates. Populate the three secret files from an operator-controlled secret
manager without echoing their values or enabling shell history expansion. The
WATCH API token must contain at least 32 non-padding RFC 6750 `token68`
characters; the database password and tunnel token must be independent values.

~~~bash
install -d -m 0700 "$STAGING_CONFIG_DIR"
install -d -m 0700 "$STAGING_CONFIG_DIR/secrets"
install -m 0600 ops/staging.env.example "$STAGING_ENV_FILE"
if [[ ! -e "$STAGING_STATE_FILE" ]]; then
  STATE_TMP="$(mktemp "${STAGING_CONFIG_DIR}/staging-state.env.XXXXXX")"
  printf '%s\n' \
    'WATCH_POSTGRES_VOLUME_NAME=baton-watch-staging-postgres-data' \
    > "$STATE_TMP"
  chmod 0600 "$STATE_TMP"
  mv "$STATE_TMP" "$STAGING_STATE_FILE"
  unset STATE_TMP
fi
test -e "$STAGING_CONFIG_DIR/secrets/postgres-password" || \
  install -m 0600 /dev/null "$STAGING_CONFIG_DIR/secrets/postgres-password"
test -e "$STAGING_CONFIG_DIR/secrets/watch-api-token" || \
  install -m 0600 /dev/null "$STAGING_CONFIG_DIR/secrets/watch-api-token"
test -e "$STAGING_CONFIG_DIR/secrets/cloudflare-tunnel-token" || \
  install -m 0600 /dev/null "$STAGING_CONFIG_DIR/secrets/cloudflare-tunnel-token"
~~~

These guards create missing placeholders but never truncate an existing
secret. Rotate the PostgreSQL password only through a separate procedure that
updates the database role and file as one controlled change; replacing the file
alone breaks authentication for an initialized volume.

After the secret manager has written each value with one final newline, verify
metadata and non-empty files without printing contents:

~~~bash
chmod 0600 "$STAGING_CONFIG_DIR/secrets/postgres-password"
chmod 0600 "$STAGING_CONFIG_DIR/secrets/watch-api-token"
chmod 0600 "$STAGING_CONFIG_DIR/secrets/cloudflare-tunnel-token"
test -s "$STAGING_CONFIG_DIR/secrets/postgres-password"
test -s "$STAGING_CONFIG_DIR/secrets/watch-api-token"
test -s "$STAGING_CONFIG_DIR/secrets/cloudflare-tunnel-token"
test -O "$STAGING_CONFIG_DIR/secrets/postgres-password"
test -O "$STAGING_CONFIG_DIR/secrets/watch-api-token"
test -O "$STAGING_CONFIG_DIR/secrets/cloudflare-tunnel-token"
test -r "$STAGING_CONFIG_DIR/secrets/postgres-password"
test -r "$STAGING_CONFIG_DIR/secrets/watch-api-token"
test -r "$STAGING_CONFIG_DIR/secrets/cloudflare-tunnel-token"
test -O "$STAGING_ENV_FILE"
test -O "$STAGING_STATE_FILE"
test -r "$STAGING_ENV_FILE"
test -r "$STAGING_STATE_FILE"
stat -f '%Lp %N' "$STAGING_CONFIG_DIR/secrets/postgres-password"
stat -f '%Lp %N' "$STAGING_CONFIG_DIR/secrets/watch-api-token"
stat -f '%Lp %N' "$STAGING_CONFIG_DIR/secrets/cloudflare-tunnel-token"
stat -f '%Lp %N' "$STAGING_ENV_FILE"
stat -f '%Lp %N' "$STAGING_STATE_FILE"
~~~

Each `stat` line must start with `600`. The template environment file
intentionally contains only the image revision, file paths, database
identifiers, and bounded application timeout settings. The state file must
contain exactly one non-secret active-volume assignment. Load and validate that
value without sourcing either file:

~~~bash
test "$(wc -l < "$STAGING_STATE_FILE" | tr -d ' ')" = 1
STATE_CONTENT="$(< "$STAGING_STATE_FILE")"
[[ "$STATE_CONTENT" =~ ^WATCH_POSTGRES_VOLUME_NAME=[a-zA-Z0-9][a-zA-Z0-9_.-]+$ ]]
WATCH_POSTGRES_VOLUME_NAME="${STATE_CONTENT#WATCH_POSTGRES_VOLUME_NAME=}"
export WATCH_POSTGRES_VOLUME_NAME
unset STATE_CONTENT
~~~

The shell's `WATCH_IMAGE_REVISION` selects the clean commit being deployed;
the state file selects the durable database volume without storing either in
Git.

Create the external database volume once, then inspect it before every deploy:

~~~bash
docker volume create "$WATCH_POSTGRES_VOLUME_NAME"
docker volume inspect "$WATCH_POSTGRES_VOLUME_NAME"
~~~

## Build the exact local revision

Pull the two digest-pinned runtime dependencies explicitly. WATCH alone uses
`pull_policy: never`, so deployment cannot silently substitute its locally
built SHA-tagged image from a registry.

~~~bash
VERIFY_RUN_COUNT="$(gh run list --repo ljkhyeong/baton-watch \
  --workflow Verify --commit "$DEPLOY_SHA" --status success --limit 20 \
  --json headSha --jq 'length')"
test "$VERIFY_RUN_COUNT" -ge 1
./gradlew clean test :bootstrap:bootJar --no-daemon --no-build-cache
docker pull postgres:18.4-alpine@sha256:9a8afca54e7861fd90fab5fdf4c42477a6b1cb7d293595148e674e0a3181de15
docker pull cloudflare/cloudflared:2026.7.3@sha256:e39ee8da81ad5e05d77f38d2f51c60ca51bf2a8450ac3abab50c17fdb91d91bf
docker build --pull \
  --label "org.opencontainers.image.revision=${DEPLOY_SHA}" \
  --tag "$WATCH_IMAGE" \
  .
BUILT_IMAGE_REVISION="$(docker image inspect "$WATCH_IMAGE" \
  --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}')"
test "$BUILT_IMAGE_REVISION" = "$DEPLOY_SHA"
~~~

The exact SHA must have a successful GitHub `Verify` workflow and pass the full
local test task before the image is built. Do not deploy a dirty worktree,
`latest`, a shortened SHA, or an image built for a different revision.

Define one helper so every operation uses the tunnel overlay:

~~~bash
staging_compose() {
  docker compose \
    --env-file "$STAGING_ENV_FILE" \
    -f compose.staging.yml \
    -f compose.staging-tunnel.yml \
    "$@"
}
~~~

Validate the merged model before starting anything:

~~~bash
staging_compose config --quiet
staging_compose config
~~~

Inspect the rendered `postgres`, `watch`, and `cloudflared` services. None may
contain a `ports` entry. The rendered WATCH environment must keep
`WATCH_EVENT_DELIVERY_ENABLED: "false"`; secret contents must not appear.

## Backup before an update

The first empty deployment has nothing to back up. Before every later update,
create a mode-0600 logical backup while the existing database is healthy. The
command reads PostgreSQL credentials inside the container and does not put the
password on the command line.

~~~bash
umask 077
BACKUP_DIR="${HOME}/baton-watch-staging-backups"
mkdir -p "$BACKUP_DIR"
BACKUP_FILE="${BACKUP_DIR}/baton-watch-$(date -u +%Y%m%dT%H%M%SZ).dump"
staging_compose exec -T postgres sh -c \
  'exec pg_dump --format=custom --no-owner --no-privileges --username="$POSTGRES_USER" "$POSTGRES_DB"' \
  > "$BACKUP_FILE"
chmod 0600 "$BACKUP_FILE"
test -s "$BACKUP_FILE"
staging_compose exec -T postgres pg_restore --list \
  < "$BACKUP_FILE" >/dev/null

verify_backup_restore() {
  local restore_database="watch_restore_test_$(date -u +%Y%m%dT%H%M%SZ)_$$"
  local restore_evidence

  staging_compose exec -T --env RESTORE_DATABASE="$restore_database" \
    postgres sh -c \
    'exec createdb --username="$POSTGRES_USER" "$RESTORE_DATABASE"'

  if ! staging_compose exec -T --env RESTORE_DATABASE="$restore_database" \
    postgres sh -c \
    'exec pg_restore --exit-on-error --single-transaction --no-owner --no-privileges --username="$POSTGRES_USER" --dbname="$RESTORE_DATABASE"' \
    < "$BACKUP_FILE"; then
    staging_compose exec -T --env RESTORE_DATABASE="$restore_database" \
      postgres sh -c \
      'exec dropdb --if-exists --username="$POSTGRES_USER" "$RESTORE_DATABASE"'
    return 1
  fi

  if ! restore_evidence="$(staging_compose exec -T \
    --env RESTORE_DATABASE="$restore_database" postgres sh -c \
    'exec psql --username="$POSTGRES_USER" --dbname="$RESTORE_DATABASE" --tuples-only --no-align --command="SELECT count(*) > 0 AND bool_and(success) FROM flyway_schema_history"')"; then
    staging_compose exec -T --env RESTORE_DATABASE="$restore_database" \
      postgres sh -c \
      'exec dropdb --if-exists --username="$POSTGRES_USER" "$RESTORE_DATABASE"'
    return 1
  fi

  staging_compose exec -T --env RESTORE_DATABASE="$restore_database" \
    postgres sh -c \
    'exec dropdb --username="$POSTGRES_USER" "$RESTORE_DATABASE"'
  test "$restore_evidence" = t
}

verify_backup_restore
unset -f verify_backup_restore
~~~

Copy the backup to the operator's protected backup location and record only its
timestamp, size, checksum, deployed SHA, and restore-test result. A dump that
has not been restored into an isolated database is not a verified backup.

## Deploy

Start the complete stack and require all three health checks to pass:

~~~bash
staging_compose up -d --no-build --wait --wait-timeout 180
staging_compose ps
~~~

If the command fails, inspect bounded logs without enabling debug logging. Do
not bypass an unhealthy PostgreSQL, WATCH, or tunnel dependency.

Verify application and database health from inside WATCH; port 8081 must remain
inaccessible from the host and tunnel:

~~~bash
staging_compose exec -T watch \
  wget -q -O - http://127.0.0.1:8081/actuator/health
staging_compose exec -T watch sh -c \
  'test "$WATCH_EVENT_DELIVERY_ENABLED" = false'
~~~

The health response must be `UP`, including the database indicator, and the
delivery assertion must exit zero.

## External HTTPS smoke

Run this from a network outside the origin Mac when possible. These requests do
not contain a token or any real resource reference.

~~~bash
PUBLIC_BASE_URL=https://watch-staging.b4ton.com
curl --proto '=https' --tlsv1.2 --noproxy '*' \
  --connect-timeout 5 --max-time 10 --max-filesize 65536 \
  --silent --show-error --fail \
  "$PUBLIC_BASE_URL/api/v1/system/status"

UNAUTHORIZED_STATUS="$(curl --proto '=https' --tlsv1.2 --noproxy '*' \
  --connect-timeout 5 --max-time 10 --max-filesize 65536 \
  --silent --show-error \
  --request PUT --header 'Content-Type: application/json' --data '{' \
  --output /dev/null --write-out '%{http_code}' \
  "$PUBLIC_BASE_URL/api/v1/resource-monitors/staging-auth-smoke")"
test "$UNAUTHORIZED_STATUS" = 401

CATCH_ALL_STATUS="$(curl --proto '=https' --tlsv1.2 --noproxy '*' \
  --connect-timeout 5 --max-time 10 --max-filesize 65536 \
  --silent --show-error \
  --output /dev/null --write-out '%{http_code}' \
  "$PUBLIC_BASE_URL/api/v1/ingress-deny-smoke")"
test "$CATCH_ALL_STATUS" = 404
~~~

The status JSON must identify `baton-watch` as `UP`. The malformed,
unauthenticated monitor request must return `401`, proving that the public
route reaches WATCH and fails closed before parsing. The unrouted `/api/v1/**`
sentinel must return the tunnel's catch-all `404`; unrestricted routing to
WATCH would instead encounter its fail-closed authentication boundary and
return `401`.

Repeat the status request while retaining response headers only. Confirm the
certificate is valid for `watch-staging.b4ton.com`, no redirect changes the
hostname or scheme, and `CF-Cache-Status` is never `HIT`. An absent tunnel,
Cloudflare edge error, cached response, or response from another service fails
the smoke.

## Log redaction audit

Compose bounds each JSON log to three 10 MiB files by default. Keep
`cloudflared` at `info`; debug or request-header logging is not permitted.

Collect a short audit snapshot in a mode-0700 temporary directory with a
mode-0600 log file. Search it locally for the three exact secret values,
`Authorization`, bearer values,
target URLs, resource references, callback URLs, and request payloads. A
scanner must report only the category and pass/fail result, never the matching
line or secret. Any match fails deployment and requires token rotation when a
secret may have escaped.

~~~bash
umask 077
AUDIT_DIR="$(mktemp -d "${TMPDIR:-/tmp}/baton-watch-log-audit.XXXXXX")"
staging_compose logs --no-color --since 15m > "$AUDIT_DIR/compose.log"
chmod 0600 "$AUDIT_DIR/compose.log"
~~~

Do not upload the raw log. After recording bounded results, remove the file and
then its now-empty directory:

~~~bash
rm "$AUDIT_DIR/compose.log"
rmdir "$AUDIT_DIR"
unset AUDIT_DIR
~~~

## Rollback

For an application-only rollback, select the previous verified full commit
SHA and its retained local image. Do not rebuild that old tag from a different
worktree.

~~~bash
export PREVIOUS_SHA=replace-with-previous-verified-40-character-sha
test "$(printf '%s' "$PREVIOUS_SHA" | wc -c | tr -d ' ')" = 40
export WATCH_IMAGE_REVISION="$PREVIOUS_SHA"
export WATCH_IMAGE="baton-watch:${WATCH_IMAGE_REVISION}"
ROLLBACK_IMAGE_REVISION="$(docker image inspect "$WATCH_IMAGE" \
  --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}')"
test "$ROLLBACK_IMAGE_REVISION" = "$PREVIOUS_SHA"
staging_compose up -d --no-build --wait --wait-timeout 180
~~~

Repeat internal health, external status/401/404, cache, TLS, and log audits.
The external PostgreSQL volume remains attached, and delivery remains disabled.

If the new release applied a schema that the previous image cannot read, do not
rewrite the active external volume in place. Select the last backup that passed
the restore test above, then restore it into a new explicitly named volume:

~~~bash
export BACKUP_FILE=replace-with-last-verified-backup-file
test -s "$BACKUP_FILE"
FAILED_VOLUME="$WATCH_POSTGRES_VOLUME_NAME"
RESTORED_VOLUME="baton-watch-staging-restore-${PREVIOUS_SHA:0:12}-$(date -u +%Y%m%dT%H%M%SZ)"

staging_compose down --remove-orphans
docker volume inspect "$FAILED_VOLUME"
docker volume create "$RESTORED_VOLUME"
export WATCH_POSTGRES_VOLUME_NAME="$RESTORED_VOLUME"
staging_compose up -d --no-deps --wait --wait-timeout 120 postgres
staging_compose exec -T postgres sh -c \
  'exec pg_restore --exit-on-error --single-transaction --no-owner --no-privileges --username="$POSTGRES_USER" --dbname="$POSTGRES_DB"' \
  < "$BACKUP_FILE"
RESTORED_MIGRATION_EVIDENCE="$(staging_compose exec -T postgres sh -c \
  'exec psql --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" --tuples-only --no-align --command="SELECT count(*) > 0 AND bool_and(success) FROM flyway_schema_history"')"
test "$RESTORED_MIGRATION_EVIDENCE" = t
staging_compose up -d --no-build --wait --wait-timeout 180

persist_active_volume() {
  local state_tmp
  state_tmp="$(mktemp "${STAGING_CONFIG_DIR}/staging-state.env.XXXXXX")"
  if ! printf 'WATCH_POSTGRES_VOLUME_NAME=%s\n' \
    "$WATCH_POSTGRES_VOLUME_NAME" > "$state_tmp"; then
    rm -f "$state_tmp"
    return 1
  fi
  chmod 0600 "$state_tmp"
  mv "$state_tmp" "$STAGING_STATE_FILE"
}

persist_active_volume
unset -f persist_active_volume
test "$(wc -l < "$STAGING_STATE_FILE" | tr -d ' ')" = 1
grep -Fxq \
  "WATCH_POSTGRES_VOLUME_NAME=${WATCH_POSTGRES_VOLUME_NAME}" \
  "$STAGING_STATE_FILE"
~~~

Repeat every health and external smoke before accepting the rollback. Record
both volume names so recovery can be reversed. Delete neither volume until the
restored service and backup have been independently verified. If restore or
startup fails, stop the new stack and preserve both volumes for diagnosis.

## Shutdown and decommission cleanup

A routine staging shutdown removes containers and the project networks but
preserves the external database volume and local images:

~~~bash
staging_compose down --remove-orphans
docker volume inspect "$WATCH_POSTGRES_VOLUME_NAME"
~~~

Never add `--volumes` to routine shutdown. Remove an old SHA-tagged image only
after the rollback window closes, naming that exact tag rather than running a
broad image prune.

For permanent decommission, first disable the public hostname, delete or
disable the remotely managed tunnel, and revoke its connector token in
Cloudflare. Then remove the exact token file. Remove the API/database secret
files and external PostgreSQL volume only after a final verified backup and an
explicit data-retention decision. Repository cleanup alone does not disable a
live tunnel or DNS route.
