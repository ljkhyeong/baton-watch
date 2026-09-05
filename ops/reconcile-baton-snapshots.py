#!/usr/bin/env python3
"""BATON의 불변 최신 스냅샷과 WATCH를 대조하고 같은 리비전으로 복구한다."""

import argparse
import collections
import hashlib
import importlib.util
import ipaddress
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import time
from urllib.parse import quote, urlsplit
import uuid

MAX_MANIFEST_BYTES = 32 * 1024 * 1024
MAX_SNAPSHOTS = 10_000
MAX_RESPONSE_BYTES = 8_192
FIELDS = {"resourceReference", "sourceRevision", "monitoringState", "targetUrl"}
SPEC = importlib.util.spec_from_file_location("url_policy", Path(__file__).with_name("staging-url-policy.py"))
URL_POLICY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(URL_POLICY)


class RecoveryError(Exception):
    """원문이나 인증값을 포함하지 않는 운영 오류."""


def private_file(path, limit):
    try:
        descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        with os.fdopen(descriptor, "rb") as source:
            metadata = os.fstat(source.fileno())
            if not stat.S_ISREG(metadata.st_mode) or metadata.st_uid != os.geteuid() or metadata.st_mode & 0o077:
                raise RecoveryError("입력 파일은 현재 사용자가 소유한 0600 이하 권한의 일반 파일이어야 합니다")
            raw = source.read(limit + 1)
        if len(raw) > limit:
            raise RecoveryError("입력 파일 크기 제한을 초과했습니다")
        return raw
    except OSError:
        raise RecoveryError("입력 파일을 안전하게 읽지 못했습니다") from None


def read_snapshots(raw, namespace):
    if not re.fullmatch(r"[A-Za-z0-9._-]{1,63}", namespace):
        raise RecoveryError("소스 이름공간 형식이 올바르지 않습니다")
    snapshots, seen = [], set()
    prefix = f"baton-manager:{namespace}:role-resource:"
    try:
        for line in raw.decode("utf-8").splitlines():
            if not line.strip():
                raise ValueError()
            item = json.loads(line)
            if not isinstance(item, dict) or set(item) != FIELDS:
                raise ValueError()
            reference = item["resourceReference"]
            revision = item["sourceRevision"]
            if not isinstance(reference, str) or not reference.startswith(prefix) or len(reference) > 128:
                raise ValueError()
            if str(uuid.UUID(reference[len(prefix):])) != reference[len(prefix):] or reference in seen:
                raise ValueError()
            if type(revision) is not int or not 1 <= revision <= 2**63 - 1:
                raise ValueError()
            state, target = item["monitoringState"], item["targetUrl"]
            if state == "ACTIVE":
                if not isinstance(target, str) or not 1 <= len(target) <= 2048:
                    raise ValueError()
            elif state != "INACTIVE" or target is not None:
                raise ValueError()
            seen.add(reference)
            snapshots.append(item)
            if len(snapshots) > MAX_SNAPSHOTS:
                raise ValueError()
        if not snapshots:
            raise ValueError()
        return snapshots
    except (ValueError, TypeError, UnicodeError):
        raise RecoveryError("스냅샷의 형식·이름공간·중복 또는 최대 10000건 제한을 확인하세요") from None


def public_address(host):
    # DNS 대기도 전체 기한을 넘기지 않도록 짧게 실행하는 별도 프로세스로 격리한다.
    resolver = "import socket,json,sys; print(json.dumps(sorted({r[4][0] for r in socket.getaddrinfo(sys.argv[1],443,type=socket.SOCK_STREAM)})))"
    try:
        result = subprocess.run([sys.executable, "-c", resolver, host], capture_output=True, timeout=3, check=True)
        addresses = [ipaddress.ip_address(value) for value in json.loads(result.stdout)]
        if not addresses or any(not address.is_global or address.is_multicast for address in addresses):
            raise ValueError()
        return str(sorted(addresses, key=lambda address: (address.version, int(address)))[0])
    except (OSError, subprocess.SubprocessError, ValueError):
        raise RecoveryError("WATCH 목적지의 공개 주소를 제한 시간 안에 확인하지 못했습니다") from None


class WatchClient:
    def __init__(self, origin, token, interval=0.5):
        if not URL_POLICY.is_allowed_url("origin", origin):
            raise RecoveryError("WATCH 주소는 기본 HTTPS 포트의 DNS 오리진이어야 합니다")
        self.origin = origin.rstrip("/")
        self.host = urlsplit(origin).hostname
        self.token = token
        self.interval = interval
        self.last_request = None

    def request(self, method, reference, payload=None):
        if self.last_request is not None:
            time.sleep(max(0, self.interval - (time.monotonic() - self.last_request)))
        address = public_address(self.host)
        pinned = f"[{address}]" if ":" in address else address
        url = f"{self.origin}/api/v1/resource-monitors/{quote(reference, safe='')}"
        config = [f"url = {json.dumps(url)}", f"header = {json.dumps('Authorization: Bearer ' + self.token)}",
                  'header = "Accept: application/json, application/problem+json"']
        if payload is not None:
            config += ['header = "Content-Type: application/json"',
                       f"data = {json.dumps(json.dumps(payload, ensure_ascii=True, separators=(',', ':')))}"]
        command = ["curl", "--disable", "--config", "-", "--silent", "--noproxy", "*",
                   "--proto", "=https", "--tlsv1.2", "--connect-timeout", "3", "--max-time", "10",
                   "--max-filesize", str(MAX_RESPONSE_BYTES), "--max-redirs", "0",
                   "--resolve", f"{self.host}:443:{pinned}", "--request", method,
                   "--write-out", "\n%{http_code}"]
        self.last_request = time.monotonic()
        try:
            response = subprocess.run(command, input="\n".join(config).encode(), capture_output=True, timeout=12, check=True)
            body, code = response.stdout.rsplit(b"\n", 1)
            if len(body) > MAX_RESPONSE_BYTES:
                raise ValueError()
            return int(code), json.loads(body) if body else None
        except (OSError, subprocess.SubprocessError, ValueError):
            raise RecoveryError("WATCH 요청 또는 응답 확인에 실패했습니다") from None


def inspect_remote(client, snapshot):
    status, body = client.request("GET", snapshot["resourceReference"])
    if status == 404 and isinstance(body, dict) and body.get("code") == "MONITOR_NOT_FOUND":
        return "MISSING", None
    if status != 200 or not isinstance(body, dict):
        return "LOOKUP_FAILED", None
    revision = body.get("sourceRevision")
    if body.get("resourceReference") != snapshot["resourceReference"] or type(revision) is not int or revision < 1:
        return "LOOKUP_FAILED", None
    if body.get("monitoringState") not in ("ACTIVE", "INACTIVE"):
        return "LOOKUP_FAILED", None
    if revision > snapshot["sourceRevision"]:
        return "REMOTE_AHEAD", revision
    if revision < snapshot["sourceRevision"]:
        return "REMOTE_BEHIND", revision
    if body["monitoringState"] != snapshot["monitoringState"]:
        return "PAYLOAD_CONFLICT", revision
    return "REVISION_MATCH_UNVERIFIED", revision


def reconcile(client, snapshots, apply=False):
    for snapshot in snapshots:
        try:
            status, remote_revision = inspect_remote(client, snapshot)
            if apply and status in ("MISSING", "REMOTE_BEHIND", "REVISION_MATCH_UNVERIFIED"):
                payload = {key: value for key, value in snapshot.items() if key != "resourceReference"}
                http_status, body = client.request("PUT", snapshot["resourceReference"], payload)
                if (http_status == 200 and isinstance(body, dict)
                        and body.get("resourceReference") == snapshot["resourceReference"]
                        and type(body.get("sourceRevision")) is int
                        and body["sourceRevision"] == snapshot["sourceRevision"]
                        and body.get("monitoringState") == snapshot["monitoringState"]):
                    # PUT의 200은 같은 리비전의 원본 URL까지 동일하다는 계약 확인이다.
                    status = "REPLAYED"
                elif http_status == 409 and isinstance(body, dict) and body.get("code") in ("STALE_SOURCE_REVISION", "SOURCE_REVISION_CONFLICT"):
                    status = "REMOTE_AHEAD" if body["code"] == "STALE_SOURCE_REVISION" else "PAYLOAD_CONFLICT"
                else:
                    status = "REPLAY_FAILED"
        except RecoveryError:
            status, remote_revision = "LOOKUP_OR_REPLAY_FAILED", None
        yield {"resourceReference": snapshot["resourceReference"], "sourceRevision": snapshot["sourceRevision"],
               "remoteRevision": remote_revision, "status": status}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--snapshots", required=True, help="최신 불변 스냅샷 JSONL 파일")
    parser.add_argument("--source-namespace", required=True, help="BATON에 설정한 소스 이름공간")
    parser.add_argument("--origin", required=True, help="WATCH HTTPS 오리진")
    parser.add_argument("--token-file", required=True, help="WATCH API 토큰 파일")
    parser.add_argument("--apply", action="store_true", help="기존 리비전과 페이로드로 재전송")
    parser.add_argument("--expected-sha256", help="재전송할 때 요구하는 사전 검토 파일 SHA-256")
    args = parser.parse_args(argv)
    try:
        raw = private_file(args.snapshots, MAX_MANIFEST_BYTES)
        digest = hashlib.sha256(raw).hexdigest()
        snapshots = read_snapshots(raw, args.source_namespace)
        if args.apply and digest != args.expected_sha256:
            raise RecoveryError("재전송에는 사전 검토한 스냅샷 파일의 SHA-256이 필요합니다")
        token = private_file(args.token_file, 202).decode("ascii").rstrip("\r\n")
        if not re.fullmatch(r"[A-Za-z0-9._~-]{32,200}", token):
            raise RecoveryError("WATCH API 토큰 형식을 확인하세요")
        client = WatchClient(args.origin, token)
        print(json.dumps({"mode": "REPLAY" if args.apply else "AUDIT", "manifestSha256": digest, "count": len(snapshots)}), flush=True)
        counts = collections.Counter()
        for result in reconcile(client, snapshots, args.apply):
            counts[result["status"]] += 1
            print(json.dumps(result, ensure_ascii=False), flush=True)
        print(json.dumps({"summary": dict(counts)}))
        successful = {"REPLAYED"} if args.apply else {"REVISION_MATCH_UNVERIFIED"}
        return 0 if set(counts).issubset(successful) else 2
    except (RecoveryError, UnicodeError) as exception:
        print(str(exception) if isinstance(exception, RecoveryError) else "입력 파일의 문자 인코딩을 확인하세요", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
