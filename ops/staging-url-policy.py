#!/usr/bin/env python3

import re
import sys
from urllib.parse import urlsplit

MAX_URL_LENGTH = 2048
MAX_HOST_LENGTH = 253
HOST_LABEL = re.compile(r"[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?")
NUMERIC_ADDRESS_COMPONENT = re.compile(r"(?:0[xX][0-9A-Fa-f]+|[0-9]+)")
EVENT_DELIVERY_PATH = "/api/v1/internal/resource-health-events"


def is_dns_hostname(host: str) -> bool:
    if len(host) > MAX_HOST_LENGTH:
        return False
    labels = host.split(".")
    if ":" in host or all(NUMERIC_ADDRESS_COMPONENT.fullmatch(label) for label in labels):
        return False
    return all(HOST_LABEL.fullmatch(label) for label in labels)


def has_unsafe_raw_character(value: str) -> bool:
    return any(
        character == "\\"
        or ord(character) <= 0x20
        or 0x7F <= ord(character) <= 0x9F
        for character in value
    )


def is_allowed_url(mode: str, value: str) -> bool:
    try:
        parsed = urlsplit(value)
        port = parsed.port
    except ValueError:
        return False

    if (
        not value
        or len(value) > MAX_URL_LENGTH
        or has_unsafe_raw_character(value)
        or "?" in value
        or "#" in value
        or parsed.scheme != "https"
        or parsed.hostname is None
        or port not in (None, 443)
        or not is_dns_hostname(parsed.hostname)
    ):
        return False

    expected_authority = parsed.hostname if port is None else f"{parsed.hostname}:{port}"
    if parsed.netloc.lower() != expected_authority.lower():
        return False

    if mode == "origin":
        return parsed.path in ("", "/")
    if mode == "event-delivery-endpoint":
        return parsed.path == EVENT_DELIVERY_PATH
    return False


def main() -> int:
    if len(sys.argv) != 2:
        return 64
    value = sys.stdin.read(MAX_URL_LENGTH + 1)
    return 0 if is_allowed_url(sys.argv[1], value) else 1


if __name__ == "__main__":
    raise SystemExit(main())
