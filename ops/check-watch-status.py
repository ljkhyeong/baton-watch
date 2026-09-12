#!/usr/bin/env python3
"""공개 상태 응답이 WATCH의 정상 상태 JSON인지 확인한다."""

import json
import sys

try:
    with open(sys.argv[1], encoding="utf-8") as response:
        payload = json.load(response)
except (OSError, UnicodeError, ValueError, RecursionError):
    raise SystemExit(1) from None

raise SystemExit(0 if isinstance(payload, dict)
                 and payload.get("service") == "baton-watch"
                 and payload.get("status") == "UP" else 1)
