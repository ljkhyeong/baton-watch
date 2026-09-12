#!/usr/bin/env python3
"""공식 amtool로 수신 경로와 알림 문구를 검사한다. 외부 메시지는 보내지 않는다."""

import json
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
IMAGE = "prom/alertmanager:v0.31.1@sha256:88b605de9aba0410775c1eb3438f951115054e0d307f23f274a4c705f51630c1"
CONFIG = "/etc/alertmanager/watch-telegram.yml"


class AlertmanagerConfigTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="watch-alertmanager-")
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        self.directory.chmod(0o755)
        # 검사 전용 가짜 값이다. 실제 자격 증명이나 사용자 파일을 읽지 않는다.
        for name, value in {
            "watch-telegram-bot-token": "123456789:LOCAL_TEST_TOKEN",
            "watch-telegram-chat-id": "-1001234567890",
        }.items():
            self.write_fixture(name, value)

    def write_fixture(self, name, value):
        path = self.directory / name
        path.write_text(value, encoding="utf-8")
        path.chmod(0o444)

    def amtool(self, *arguments):
        result = subprocess.run(
            [
                "docker", "run", "--rm", "--network", "none", "--read-only",
                "--cap-drop", "ALL", "--security-opt", "no-new-privileges:true",
                "--memory", "256m", "--cpus", "1", "--entrypoint", "/bin/amtool",
                "--volume", f"{ROOT / 'ops/alertmanager'}:/etc/alertmanager:ro",
                "--volume", f"{self.directory}:/run/secrets:ro", IMAGE, *arguments,
            ],
            capture_output=True, text=True, timeout=60,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        return result.stdout

    def test_native_config(self):
        self.amtool("check-config", CONFIG)

    def test_watch_only_routing(self):
        for job, alertname, receiver in (
            ("baton-watch", "WatchScrapeFailed", "watch-telegram"),
            ("baton-watch-ingress", "WatchIngressTargetMissing", "watch-telegram"),
            ("baton-cal", "WatchScrapeFailed", "ignore"),
            ("baton-watch", "UnrelatedAlert", "ignore"),
        ):
            with self.subTest(job=job, alertname=alertname):
                self.amtool(
                    "config", "routes", "test", f"--config.file={CONFIG}",
                    f"--verify.receivers={receiver}", f"job={job}", f"alertname={alertname}",
                )

    def test_grouped_failure_and_recovery_without_private_details(self):
        for status, title, counts in (
            ("firing", "장애", "발생 2건 / 복구 0건"),
            ("resolved", "복구", "발생 0건 / 복구 2건"),
        ):
            with self.subTest(status=status):
                labels = {"alertname": "WatchScrapeFailed", "job": "baton-watch"}
                annotations = {"summary": "WATCH 메트릭 수집 실패", "description": "PRIVATE_DESCRIPTION"}
                fixture = {
                    "receiver": "watch-telegram", "status": status,
                    "groupLabels": {"alertname": labels["alertname"]},
                    "commonLabels": {**labels, "url": "https://PRIVATE_URL.invalid"},
                    "commonAnnotations": annotations,
                    "externalURL": "https://PRIVATE_ALERTMANAGER.invalid",
                    "alerts": [
                        {
                            "status": status,
                            "labels": {**labels, "instance": f"PRIVATE_INSTANCE_{index}",
                                       "resourceReference": "PRIVATE_RESOURCE", "url": "https://PRIVATE_URL.invalid"},
                            "annotations": annotations,
                            "generatorURL": "https://PRIVATE_PROMETHEUS.invalid",
                        }
                        for index in range(2)
                    ],
                }
                self.write_fixture(f"alerts-{status}.json", json.dumps(fixture))
                rendered = self.amtool(
                    "template", "render", "--template.glob=/etc/alertmanager/*.tmpl",
                    '--template.text={{ template "watch.telegram" . }}',
                    f"--template.data=/run/secrets/alerts-{status}.json",
                )
                self.assertIn(f"BATON WATCH · {title}", rendered)
                self.assertIn(counts, rendered)
                self.assertEqual(rendered.count("WATCH 메트릭 수집 실패"), 1)
                self.assertIn("https://watch.b4ton.com/api/v1/system/status", rendered)
                self.assertNotIn("PRIVATE_", rendered)


if __name__ == "__main__":
    unittest.main()
