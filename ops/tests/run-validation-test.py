"""검증 실패 전파와 결과 재사용에 필요한 파일 변경 감지를 확인한다."""

import json
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "run-validation.py"


class ValidationRunnerTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.command("git", "init", "-q")
        (self.root / ".gitignore").write_text(".gradle/\n")
        (self.root / "source.txt").write_text("before\n")
        self.command("git", "add", ".")
        self.command("git", "-c", "user.name=검증", "-c", "user.email=test@example.invalid",
                     "commit", "-qm", "test: 검증 표본")

    def command(self, *args):
        return subprocess.run(args, cwd=self.root, capture_output=True, text=True, timeout=10, check=True)

    def run_check(self, *args):
        return subprocess.run([sys.executable, str(SCRIPT), "run", "--label", "sample", "--", *args],
                              cwd=self.root, capture_output=True, text=True, timeout=10)

    def record(self):
        path = next((self.root / ".gradle/agent-validation").glob("*/result.json"))
        return path, json.loads(path.read_text())

    def test_success_retains_log_and_detects_tracked_and_untracked_changes(self):
        """성공 결과는 파일 변경과 구분하며 새 파일 수정도 감지한다."""
        (self.root / "new.txt").write_text("first\n")
        result = self.run_check(sys.executable, "-c", "print('검증 로그')")
        self.assertEqual(result.returncode, 0, result.stderr)
        path, record = self.record()
        self.assertEqual(record["outcome"], "passed")
        self.assertEqual((path.parent / "output.log").read_text(), "검증 로그\n")
        self.assertEqual(path.stat().st_mode & 0o777, 0o600)
        self.assertIn("파일 동일", self.command(sys.executable, str(SCRIPT), "status").stdout)
        for filename in ("source.txt", "new.txt"):
            source = self.root / filename
            original = source.read_text()
            source.write_text("changed\n")
            self.assertIn("파일 변경됨", self.command(sys.executable, str(SCRIPT), "status").stdout)
            source.write_text(original)

    def test_failure_preserves_exit_code_and_limits_console_output(self):
        """실패를 뒤의 기록 작업으로 덮지 않고 원문은 파일에 남긴다."""
        result = self.run_check(sys.executable, "-c", "import sys; print('x' * 8000); sys.exit(7)")
        self.assertEqual(result.returncode, 7)
        path, record = self.record()
        self.assertEqual(record["exit_code"], 7)
        self.assertEqual(record["outcome"], "failed")
        self.assertGreater((path.parent / "output.log").stat().st_size, 8000)
        self.assertLess(len(result.stdout), 5000)

    def test_missing_tool_is_a_recorded_failure(self):
        result = self.run_check("/nonexistent/watch-validation-command")
        self.assertEqual(result.returncode, 127)
        self.assertEqual(self.record()[1]["exit_code"], 127)

    def test_changed_files_during_execution_are_not_presented_as_unchanged(self):
        result = self.run_check(sys.executable, "-c", "from pathlib import Path; Path('source.txt').write_text('after')")
        self.assertEqual(result.returncode, 0, result.stderr)
        record = self.record()[1]
        self.assertNotEqual(record["before"]["files_sha256"], record["after"]["files_sha256"])
        self.assertIn("파일 변경됨", self.command(sys.executable, str(SCRIPT), "status").stdout)

    def test_signal_termination_stays_failed(self):
        result = self.run_check(sys.executable, "-c", "import os, signal; os.kill(os.getpid(), signal.SIGTERM)")
        self.assertEqual(result.returncode, 128 + signal.SIGTERM)
        self.assertEqual(self.record()[1]["outcome"], "failed")


if __name__ == "__main__":
    unittest.main()
