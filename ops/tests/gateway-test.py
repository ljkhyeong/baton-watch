#!/usr/bin/env python3
"""격리된 실제 NGINX로 요청 제한·프록시 경계·로그 비노출을 검사한다."""

import concurrent.futures
from contextlib import closing
import http.client
import json
import os
from pathlib import Path
import subprocess
import unittest
import uuid


class GatewayTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.project = "watch-gateway-test-" + uuid.uuid4().hex[:12]
        cls.command = ["docker", "compose", "--project-name", cls.project, "--file",
                       str(Path(__file__).with_name("compose.gateway-test.yml"))]
        cls.environment = os.environ | {"WATCH_TUNNEL_TOKEN_FILE": "/dev/null"}
        cls.addClassCleanup(cls.compose, "down", "--volumes", "--remove-orphans")
        cls.compose("config", "--quiet")
        cls.compose("up", "--detach", "--wait", "--wait-timeout", "60")
        cls.port = int(cls.compose("port", "watch-gateway", "8080").strip().rsplit(":", 1)[1])

    @classmethod
    def compose(cls, *args):
        return subprocess.check_output(cls.command + list(args), env=cls.environment, text=True)

    def request(self, path, method="GET", body=None, headers=None):
        with closing(http.client.HTTPConnection("127.0.0.1", self.port, timeout=5)) as client:
            client.request(method, path, body=body, headers=headers or {})
            response = client.getresponse()
            return response.status, dict(response.getheaders()), response.read().decode()

    def test_gateway_contract(self):
        # 인증 헤더와 인증 전 큰 본문 요청은 프록시의 별도 검증 없이 전달한다.
        token = "Bearer gateway-test-secret"
        status, headers, body = self.request(
            "/api/v1/resource-monitors/private-reference?private-query=1", "PUT", "x" * 20000,
            {"Authorization": token, "Content-Type": "application/json"})
        self.assertEqual(status, 401)
        self.assertEqual(headers["X-Test-Authorization"], token)
        self.assertEqual(headers["X-Test-Length"], "20000")
        self.assertEqual(json.loads(body), {"code": "UNAUTHORIZED"})
        self.assertEqual(self.request("/actuator/prometheus")[0], 404)

        with concurrent.futures.ThreadPoolExecutor(max_workers=24) as executor:
            responses = list(executor.map(
                lambda _: self.request("/api/v1/system/status"), range(80)))
        self.assertIn(200, [response[0] for response in responses])
        self.assertIn(429, [response[0] for response in responses])
        # 상태 경로의 폭주가 모니터 경로의 별도 예산을 소모하지 않아야 한다.
        self.assertEqual(self.request("/api/v1/resource-monitors/separate-budget")[0], 401)
        with concurrent.futures.ThreadPoolExecutor(max_workers=24) as executor:
            monitor_responses = list(executor.map(
                lambda _: self.request("/api/v1/resource-monitors/private-reference"), range(60)))
        self.assertIn(429, [response[0] for response in monitor_responses])
        responses += monitor_responses
        limited = [response for response in responses if response[0] == 429]
        for _, headers, body in limited:
            self.assertEqual(headers["Content-Type"], "application/problem+json")
            self.assertEqual(headers["Cache-Control"], "no-store")
            self.assertEqual(json.loads(body), {
                "type": "urn:baton-watch:problem:rate-limited", "title": "요청이 너무 많습니다",
                "status": 429, "code": "RATE_LIMITED"})
        logs = self.compose("logs", "--no-color", "watch-gateway")
        self.assertIn("status=429", logs)
        for private_value in ("private-reference", "private-query", "gateway-test-secret"):
            self.assertNotIn(private_value, logs)


if __name__ == "__main__":
    unittest.main()
