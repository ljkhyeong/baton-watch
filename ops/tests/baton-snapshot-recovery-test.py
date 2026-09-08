#!/usr/bin/env python3
"""독립 복원의 대조·재전송·인증값 보호 검증."""

import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch

SPEC = importlib.util.spec_from_file_location("recovery", Path(__file__).parents[1] / "reconcile-baton-snapshots.py")
recovery = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(recovery)
REFERENCE = "baton-manager:pilot:role-resource:aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
SNAPSHOT = {"resourceReference": REFERENCE, "sourceRevision": 7, "monitoringState": "ACTIVE", "targetUrl": "https://docs.example.com/guide"}


def remote(revision=7, state="ACTIVE"):
    return 200, {"resourceReference": REFERENCE, "sourceRevision": revision, "monitoringState": state}


class SnapshotRecoveryTest(unittest.TestCase):
    def test_audit_does_not_mutate_missing_older_matching_or_conflicting_monitors(self):
        """조회 모드는 누락·낮은 리비전·일치·충돌을 구분하고 PUT을 보내지 않는다."""
        for response, expected in [((404, {"code": "MONITOR_NOT_FOUND"}), "MISSING"),
                                   (remote(6), "REMOTE_BEHIND"), (remote(), "REVISION_MATCH_UNVERIFIED"),
                                   (remote(8), "REMOTE_AHEAD"), (remote(7, "INACTIVE"), "PAYLOAD_CONFLICT")]:
            with self.subTest(expected=expected):
                client = Mock()
                client.request.return_value = response
                self.assertEqual(list(recovery.reconcile(client, [SNAPSHOT]))[0]["status"], expected)
                client.request.assert_called_once_with("GET", REFERENCE)

    def test_replay_uses_exact_snapshot_and_never_overrides_ahead_or_conflicting_state(self):
        """복구는 같은 본문과 리비전을 재전송하고 더 높은 리비전·상태 충돌은 건드리지 않는다."""
        for first in [(404, {"code": "MONITOR_NOT_FOUND"}), remote(6), remote()]:
            client = Mock()
            client.request.side_effect = [first, remote()]
            self.assertEqual(list(recovery.reconcile(client, [SNAPSHOT], True))[0]["status"], "REPLAYED")
            self.assertEqual(client.request.call_args.args, ("PUT", REFERENCE,
                             {key: value for key, value in SNAPSHOT.items() if key != "resourceReference"}))
        for first in [remote(8), remote(7, "INACTIVE")]:
            client = Mock()
            client.request.return_value = first
            list(recovery.reconcile(client, [SNAPSHOT], True))
            client.request.assert_called_once_with("GET", REFERENCE)

    def test_equal_revision_url_conflict_and_concurrent_newer_revision_are_reported(self):
        """조회에 없는 URL의 같은 리비전 충돌과 대조 뒤 새 변경을 보고한다."""
        for code, expected in [("SOURCE_REVISION_CONFLICT", "PAYLOAD_CONFLICT"),
                               ("STALE_SOURCE_REVISION", "REMOTE_AHEAD")]:
            client = Mock()
            client.request.side_effect = [remote(), (409, {"code": code})]
            self.assertEqual(list(recovery.reconcile(client, [SNAPSHOT], True))[0]["status"], expected)
        client.request.side_effect = [remote(), (200, {})]
        self.assertEqual(list(recovery.reconcile(client, [SNAPSHOT], True))[0]["status"], "REPLAY_FAILED")

    def test_namespace_duplicates_and_apply_digest_fail_before_network(self):
        """파일 전체의 이름공간·중복과 사전 검토 해시를 확인한 뒤에만 통신한다."""
        raw = json.dumps(SNAPSHOT).encode()
        with self.assertRaises(recovery.RecoveryError):
            recovery.read_snapshots(raw, "different")
        with self.assertRaises(recovery.RecoveryError):
            recovery.read_snapshots(raw + b"\n" + raw, "pilot")
        with tempfile.TemporaryDirectory() as temporary:
            manifest = Path(temporary) / "snapshots.jsonl"
            manifest.write_bytes(raw)
            manifest.chmod(0o600)
            with patch.object(recovery, "WatchClient") as client, contextlib.redirect_stderr(io.StringIO()):
                result = recovery.main(["--snapshots", str(manifest), "--source-namespace", "pilot", "--origin",
                                        "https://watch.example.com", "--token-file", str(manifest), "--apply"])
                self.assertEqual(result, 1)
                client.assert_not_called()
            manifest.chmod(0o644)
            with self.assertRaises(recovery.RecoveryError):
                recovery.private_file(manifest, 10000)

    def test_transport_keeps_token_off_argv_and_pins_without_redirects(self):
        """토큰은 표준 입력으로만 전달하고 공개 주소 고정·리디렉션 금지·시간 및 크기 제한을 유지한다."""
        token = "watch-test-token-with-at-least-32-characters"
        client = recovery.WatchClient("https://watch.example.com", token)
        payload = {key: value for key, value in SNAPSHOT.items() if key != "resourceReference"}
        with patch.object(recovery, "public_address", return_value="93.184.216.34"), \
             patch.object(recovery.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, b"{}\n200")) as run:
            client.request("PUT", REFERENCE, payload)
        command = run.call_args.args[0]
        self.assertNotIn(token, " ".join(command))
        self.assertIn(token, run.call_args.kwargs["input"].decode())
        self.assertIn("watch.example.com:443:93.184.216.34", command)
        self.assertNotIn("--location", command)
        self.assertNotIn("--insecure", command)
        self.assertEqual(command[command.index("--max-redirs") + 1], "0")
        self.assertEqual(command[command.index("--max-filesize") + 1], "8192")
        self.assertEqual(run.call_args.kwargs["timeout"], 12)

    def test_non_public_dns_or_mixed_answers_are_rejected(self):
        """DNS 응답에 사설·루프백·멀티캐스트가 하나라도 있으면 접속하지 않는다."""
        for addresses in [["127.0.0.1"], ["224.0.0.1"], ["93.184.216.34", "10.0.0.1"]]:
            with patch.object(recovery.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, json.dumps(addresses).encode())):
                with self.assertRaises(recovery.RecoveryError):
                    recovery.public_address("watch.example.com")

    def test_one_failed_request_does_not_hide_remaining_items(self):
        """한 항목의 통신 실패 뒤에도 나머지 대조 결과를 남긴다."""
        client = Mock()
        client.request.side_effect = [recovery.RecoveryError("실패"), remote()]
        results = list(recovery.reconcile(client, [SNAPSHOT, SNAPSHOT]))
        self.assertEqual([item["status"] for item in results], ["LOOKUP_OR_REPLAY_FAILED", "REVISION_MATCH_UNVERIFIED"])


if __name__ == "__main__":
    unittest.main()
