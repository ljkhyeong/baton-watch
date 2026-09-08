"""CI와 같은 명령으로 승인된 라이선스와 예외 범위를 확인한다."""

import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "check-runtime-licenses.py"


def component(purl, *licenses):
    return {"purl": purl, "licenses": [{"license": {"id": value}} for value in licenses]}


class RuntimeLicensePolicyTest(unittest.TestCase):
    def setUp(self):
        self.components = [
            component("pkg:maven/org.flywaydb/flyway-database-postgresql@13.4.0"),
            component("pkg:maven/ch.qos.logback/logback-classic@1.5.38", "EPL-2.0", "LGPL-2.1-only"),
            component("pkg:maven/ch.qos.logback/logback-core@1.5.38", "EPL-2.0", "LGPL-2.1-only"),
            component("pkg:maven/jakarta.annotation/jakarta.annotation-api@3.0.0",
                      "Apache-2.0", "GPL-2.0-only WITH classpath-exception"),
        ]

    def check(self, extra=()):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "baton-watch.cdx.json"
            report.write_text(json.dumps({
                "bomFormat": "CycloneDX",
                "components": self.components + list(extra),
            }))
            return subprocess.run(
                [sys.executable, str(SCRIPT), directory],
                capture_output=True, text=True, timeout=5,
            )

    def test_accepts_allowed_licenses_and_approved_exceptions(self):
        result = self.check([component("pkg:maven/example/allowed@1.0", "Apache-2.0")])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stderr, "")
        self.assertTrue({"Apache-2.0", "LGPL-2.1-only", "GPL-2.0-only WITH classpath-exception"}
                        <= set(result.stdout.strip().split(",")))

    def test_rejects_unapproved_only_licenses_even_when_globally_excluded(self):
        for license_id in ("LGPL-2.1-only", "GPL-2.0-only WITH classpath-exception", "AGPL-3.0-only"):
            with self.subTest(license=license_id):
                result = self.check([component("pkg:maven/example/unapproved@1.0", license_id)])
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(result.stdout, "")
                self.assertIn("허용 라이선스가 없는 런타임 의존성", result.stderr)
                self.assertIn("pkg:maven/example/unapproved@1.0", result.stderr)

    def test_rejects_approved_package_without_an_allowed_license(self):
        self.components[1] = component("pkg:maven/ch.qos.logback/logback-classic@1.5.38", "LGPL-2.1-only")
        result = self.check()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("허용 라이선스가 없는 런타임 의존성", result.stderr)

    def test_rejects_alternative_license_for_an_unapproved_package(self):
        result = self.check([component("pkg:maven/example/unapproved@1.0", "MIT", "LGPL-2.1-only")])
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("대체 라이선스가 있는 런타임 의존성이 승인 목록과 다릅니다", result.stderr)

    def test_rejects_changed_exception_version(self):
        self.components[1]["purl"] = "pkg:maven/ch.qos.logback/logback-classic@1.5.39"
        result = self.check()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("대체 라이선스가 있는 런타임 의존성이 승인 목록과 다릅니다", result.stderr)

    def test_rejects_unknown_missing_license(self):
        result = self.check([component("pkg:maven/example/unknown@1.0")])
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("라이선스를 판별하지 못한 런타임 의존성이 승인 목록과 다릅니다", result.stderr)


if __name__ == "__main__":
    unittest.main()
