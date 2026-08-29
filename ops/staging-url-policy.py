#!/usr/bin/env python3

import ipaddress
import re
import socket
import sys
from urllib.parse import urlsplit

MAX_URL_LENGTH = 2048
MAX_HOST_LENGTH = 253
HOST_LABEL = re.compile(r"[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?")
EVENT_DELIVERY_PATH = "/api/v1/internal/resource-health-events"


def is_dns_hostname(host: str) -> bool:
    if len(host) > MAX_HOST_LENGTH:
        return False
    try:
        ipaddress.ip_address(host)
    except ValueError:
        pass
    else:
        return False
    try:
        socket.inet_aton(host)
    except OSError:
        pass
    else:
        return False
    return all(HOST_LABEL.fullmatch(label) for label in host.split("."))


def is_allowed_url(mode: str, value: str) -> bool:
    try:
        parsed = urlsplit(value)
        port = parsed.port
    except ValueError:
        return False

    if (
        not value
        or len(value) > MAX_URL_LENGTH
        or parsed.scheme != "https"
        or parsed.hostname is None
        or parsed.username is not None
        or parsed.password is not None
        or port not in (None, 443)
        or parsed.query
        or parsed.fragment
        or not is_dns_hostname(parsed.hostname)
    ):
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
