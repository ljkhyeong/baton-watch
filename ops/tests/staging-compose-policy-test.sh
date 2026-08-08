#!/usr/bin/env bash

set -euo pipefail
set +x
umask 077

readonly TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly REPOSITORY_ROOT="$(cd "$TEST_DIR/../.." && pwd)"
readonly TEMP_DIR="$(mktemp -d)"
readonly BASE_CONFIG="$TEMP_DIR/base.json"
readonly TUNNEL_CONFIG="$TEMP_DIR/tunnel.json"

cleanup() {
    rm -rf "$TEMP_DIR"
}
trap cleanup EXIT

if ! grep -Fxq 'ops/staging.env' "$REPOSITORY_ROOT/.dockerignore"; then
    printf '[staging-compose-policy-test] ops/staging.env must stay outside the Docker build context\n' >&2
    exit 1
fi

render_config() {
    local output_file="$1"
    shift

    env \
        -u WATCH_COMPOSE_PROJECT_NAME \
        -u WATCH_DB_PASSWORD_FILE \
        -u WATCH_API_TOKEN_FILE \
        -u WATCH_TUNNEL_TOKEN_FILE \
        -u WATCH_DB_NAME \
        -u WATCH_DB_USER \
        -u WATCH_JAVA_TOOL_OPTIONS \
        -u WATCH_PERSISTENCE_TRANSACTION_TIMEOUT \
        -u WATCH_PERSISTENCE_LOCK_TIMEOUT \
        -u SPRING_JDBC_TEMPLATE_QUERY_TIMEOUT \
        WATCH_IMAGE_REVISION=0000000000000000000000000000000000000001 \
        WATCH_POSTGRES_VOLUME_NAME=baton-watch-compose-policy-test \
        docker compose \
            --project-directory "$REPOSITORY_ROOT" \
            --env-file "$REPOSITORY_ROOT/ops/staging.env.example" \
            "$@" \
            config --format json >"$output_file"
}

render_config \
    "$BASE_CONFIG" \
    --file "$REPOSITORY_ROOT/compose.staging.yml"
render_config \
    "$TUNNEL_CONFIG" \
    --file "$REPOSITORY_ROOT/compose.staging.yml" \
    --file "$REPOSITORY_ROOT/compose.staging-tunnel.yml"

python3 - "$BASE_CONFIG" "$TUNNEL_CONFIG" <<'PY'
import json
import re
import sys
from pathlib import Path


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"[staging-compose-policy-test] {message}")


def load(path: str) -> dict:
    with Path(path).open(encoding="utf-8") as stream:
        return json.load(stream)


def assert_no_host_ports(configuration: dict, label: str) -> None:
    for service_name, service in configuration["services"].items():
        require(
            not service.get("ports"),
            f"{label} service {service_name} must not publish a host port",
        )


base = load(sys.argv[1])
tunnel = load(sys.argv[2])

require(set(base["services"]) == {"postgres", "watch"}, "base services changed")
require(
    set(tunnel["services"]) == {"postgres", "watch", "cloudflared"},
    "tunnel services changed",
)
assert_no_host_ports(base, "base")
assert_no_host_ports(tunnel, "tunnel")

networks = tunnel["networks"]
require(networks["watch-db"].get("internal") is True, "database network must be internal")
require(
    networks["watch-edge"].get("internal", False) is False,
    "edge network must retain outbound connectivity",
)
expected_networks = {
    "postgres": {"watch-db"},
    "watch": {"watch-db", "watch-edge"},
    "cloudflared": {"watch-edge"},
}
for service_name, expected in expected_networks.items():
    actual = set(tunnel["services"][service_name].get("networks", {}))
    require(actual == expected, f"{service_name} network boundary changed: {actual}")

postgres = tunnel["services"]["postgres"]
watch = tunnel["services"]["watch"]
cloudflared = tunnel["services"]["cloudflared"]

require(watch.get("build") is None, "staging WATCH must use a prebuilt image")
require(
    re.fullmatch(r"baton-watch:[0-9a-f]{40}", watch.get("image", "")) is not None,
    "WATCH image must be a local full-Git-SHA tag",
)
require(
    watch.get("image") == "baton-watch:0000000000000000000000000000000000000001",
    "WATCH image revision interpolation changed",
)
require(watch.get("pull_policy") == "never", "WATCH image must be the locally selected immutable tag")
require(
    cloudflared.get("image")
    == "cloudflare/cloudflared:2026.7.3@sha256:e39ee8da81ad5e05d77f38d2f51c60ca51bf2a8450ac3abab50c17fdb91d91bf",
    "cloudflared image digest changed",
)
require("@sha256:" in postgres.get("image", ""), "PostgreSQL image must be digest-pinned")

watch_environment = watch["environment"]
require(
    watch_environment.get("SPRING_CONFIG_IMPORT") == "configtree:/run/secrets/",
    "WATCH must import file secrets through Spring configtree",
)
require(
    watch_environment.get("MANAGEMENT_SERVER_ADDRESS") == "127.0.0.1",
    "management server must remain container-loopback only",
)
require(
    watch_environment.get("SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE") == "30s",
    "Spring shutdown phase budget changed",
)
require(
    watch_environment.get("WATCH_EVENT_DELIVERY_ENABLED") == "false",
    "event delivery must remain disabled until the BATON callback is deployed",
)
require(
    not any("PASSWORD" in key or "TOKEN" in key for key in watch_environment),
    "WATCH secrets must not be injected as environment variables",
)
require(
    postgres["environment"].get("POSTGRES_PASSWORD_FILE")
    == "/run/secrets/spring.datasource.password",
    "PostgreSQL must consume its password from a file secret",
)

def secret_targets(service: dict) -> set[tuple[str, str]]:
    return {
        (secret["source"], secret["target"])
        for secret in service.get("secrets", [])
    }


require(
    secret_targets(postgres)
    == {("watch-db-password", "spring.datasource.password")},
    "PostgreSQL secret target changed",
)
require(
    secret_targets(watch)
    == {
        ("watch-db-password", "spring.datasource.password"),
        ("watch-api-token", "watch.api-token"),
    },
    "WATCH secret targets changed",
)
require(
    secret_targets(cloudflared)
    == {("cloudflare-tunnel-token", "cloudflare-tunnel-token")},
    "cloudflared secret target changed",
)

expected_secret_files = {
    "watch-db-password": Path.home()
    / ".config/baton-watch/staging/secrets/postgres-password",
    "watch-api-token": Path.home()
    / ".config/baton-watch/staging/secrets/watch-api-token",
    "cloudflare-tunnel-token": Path.home()
    / ".config/baton-watch/staging/secrets/cloudflare-tunnel-token",
}
for secret_name, expected_file in expected_secret_files.items():
    actual_file = tunnel["secrets"][secret_name].get("file")
    require(
        actual_file == str(expected_file),
        f"{secret_name} example path did not resolve under the operator home",
    )

volume = tunnel["volumes"]["watch-postgres-data"]
require(volume.get("external") is True, "PostgreSQL data volume must be external")
require(
    any(
        mount.get("source") == "watch-postgres-data"
        and mount.get("target") == "/var/lib/postgresql"
        for mount in postgres.get("volumes", [])
    ),
    "PostgreSQL data mount changed",
)

expected_resource_limits = {
    "postgres": {"cpus": 1, "memory": "805306368", "pids": 128},
    "watch": {"cpus": 1, "memory": "805306368", "pids": 256},
    "cloudflared": {"cpus": 0.5, "memory": "268435456", "pids": 128},
}
for service_name, service in tunnel["services"].items():
    require(service.get("healthcheck"), f"{service_name} healthcheck is required")
    require(service.get("restart") == "unless-stopped", f"{service_name} restart policy changed")
    require(service.get("read_only") is True, f"{service_name} root filesystem must be read-only")
    require(
        service.get("privileged", False) is False,
        f"{service_name} must not be privileged",
    )
    require(not service.get("pid"), f"{service_name} must not share a PID namespace")
    require(not service.get("ipc"), f"{service_name} must not share an IPC namespace")
    require(
        not service.get("network_mode"),
        f"{service_name} must use only the declared Compose networks",
    )
    require("ALL" in service.get("cap_drop", []), f"{service_name} must drop all capabilities")
    require(
        "no-new-privileges:true" in service.get("security_opt", []),
        f"{service_name} must prohibit privilege escalation",
    )
    expected_limits = expected_resource_limits[service_name]
    require(
        service.get("pids_limit") == expected_limits["pids"],
        f"{service_name} PID limit changed",
    )
    limits = service.get("deploy", {}).get("resources", {}).get("limits", {})
    require(limits == expected_limits, f"{service_name} resource limits changed: {limits}")
    logging_options = service.get("logging", {}).get("options", {})
    require(
        logging_options == {"max-size": "10m", "max-file": "3"},
        f"{service_name} log rotation limits changed",
    )

require(
    watch["healthcheck"].get("test")
    == [
        "CMD",
        "wget",
        "-q",
        "-O",
        "/dev/null",
        "http://127.0.0.1:8081/actuator/health",
    ],
    "WATCH healthcheck must include database health",
)
require(watch.get("stop_grace_period") == "1m50s", "WATCH shutdown budget changed")
require(
    postgres.get("cap_add") == ["CHOWN", "DAC_OVERRIDE", "FOWNER", "SETGID", "SETUID"],
    "PostgreSQL capability allowlist changed",
)
require(not watch.get("cap_add"), "WATCH must not add Linux capabilities")
require(not cloudflared.get("cap_add"), "cloudflared must not add Linux capabilities")
require(watch.get("init") is True, "WATCH must retain an init process")
require(cloudflared.get("init") is True, "cloudflared must retain an init process")
require(
    watch.get("tmpfs") == ["/tmp:rw,noexec,nosuid,nodev,size=64m"],
    "WATCH tmpfs boundary changed",
)
require(
    cloudflared.get("tmpfs") == ["/tmp:rw,noexec,nosuid,nodev,size=16m"],
    "cloudflared tmpfs boundary changed",
)
require(
    set(postgres.get("tmpfs", []))
    == {
        "/tmp:rw,noexec,nosuid,nodev,size=16m",
        "/var/run/postgresql:rw,nosuid,nodev,size=16m",
    },
    "PostgreSQL tmpfs boundary changed",
)
require(
    postgres["healthcheck"].get("test")
    == ["CMD-SHELL", 'pg_isready -U "$${POSTGRES_USER}" -d "$${POSTGRES_DB}"'],
    "PostgreSQL healthcheck changed",
)
require(
    cloudflared["healthcheck"].get("test")
    == ["CMD", "cloudflared", "tunnel", "--metrics", "127.0.0.1:2000", "ready"],
    "cloudflared readiness command changed",
)
require(
    cloudflared.get("stop_grace_period") == "40s",
    "cloudflared must have cleanup time after its 30-second drain period",
)

require(
    watch.get("depends_on", {}).get("postgres", {}).get("condition") == "service_healthy",
    "WATCH must wait for healthy PostgreSQL",
)
require(
    cloudflared.get("depends_on", {}).get("watch", {}).get("condition") == "service_healthy",
    "cloudflared must wait for healthy WATCH",
)
cloudflared_command = cloudflared.get("command", [])
require(
    "/run/secrets/cloudflare-tunnel-token" in cloudflared_command
    and "--token-file" in cloudflared_command,
    "cloudflared must consume a token file",
)
require(
    "127.0.0.1:2000" in cloudflared_command,
    "cloudflared metrics must remain container-loopback only",
)

print("[staging-compose-policy-test] base and tunnel deployment policies passed")
PY
