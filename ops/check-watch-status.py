#!/usr/bin/env python3
"""공개 상태 응답이 WATCH의 정상 상태 JSON인지 확인한다."""

import json
import sys


def unique_json_object(pairs):
    item = dict(pairs)
    if len(item) != len(pairs):
        raise ValueError()
    return item


try:
    with open(sys.argv[1], encoding="utf-8") as response:
        payload = json.load(response, object_pairs_hook=unique_json_object)
except (OSError, UnicodeError, ValueError, RecursionError):
    raise SystemExit(1) from None

raise SystemExit(0 if isinstance(payload, dict)
                 and payload.get("service") == "baton-watch"
                 and payload.get("status") == "UP" else 1)
