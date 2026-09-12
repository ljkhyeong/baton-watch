"""파일 검사 실패와 전체 변경 범위의 누락 방지를 실제 임시 Git 저장소로 확인한다."""

from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "check-feedback.py"


class FeedbackTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.command("git", "init", "-q")
        self.write(".gitignore", ".gradle/\n")
        (self.root / ".gradle").mkdir()
        self.write("note.md", "메모\n")
        self.write("domain/src/main/java/Old.java", "class Old {}\n")
        self.write("gradlew", '#!/bin/sh\nprintf "%s\\n" "$@" > .gradle/tasks\n')
        (self.root / "gradlew").chmod(0o755)
        self.commit()
        self.base = self.command("git", "rev-parse", "HEAD").stdout.strip()

    def write(self, name, content):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)

    def command(self, *args):
        return subprocess.run(args, cwd=self.root, capture_output=True, text=True, check=True, timeout=10)

    def commit(self):
        self.command("git", "add", ".")
        self.command("git", "-c", "user.name=검증", "-c", "user.email=test@example.invalid",
                     "commit", "-qm", "test: 검사 표본")

    def check(self, *args):
        return subprocess.run([sys.executable, str(SCRIPT), *args], cwd=self.root,
                              capture_output=True, text=True, timeout=10)

    def test_reports_syntax_errors_without_executing_python(self):
        self.write("sample.py", "return 1\n")
        result = self.check("file", "sample.py")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("sample.py:1", result.stderr)
        self.write("sample.py", "raise RuntimeError('실행하면 안 됨')\n")
        self.assertEqual(self.check("file", "sample.py").returncode, 0)

    def test_rejects_invalid_formats_and_accepts_binary_files(self):
        for name, source in {"a.json": "{\n", "a.xml": "<a>\n", "a.toml": "a=\n",
                             "a.sh": "if\n", "a.py": "x = '\0'\n",
                             "space.txt": "공백 \n", "end.txt": "줄바꿈 없음"}.items():
            with self.subTest(name=name):
                self.write(name, source)
                self.assertNotEqual(self.check("file", name).returncode, 0)
        (self.root / "binary.bin").write_bytes(b"\0\xff")
        self.assertEqual(self.check("file", "binary.bin").returncode, 0)

    def test_checks_markdown_links_but_ignores_examples_and_external_links(self):
        self.write("note.md", '[대상](target.md)\n```md\n[예시](absent.md)\n```\n'
                   '[외부](https://example.invalid)\n[제목](#title)\n')
        self.assertNotEqual(self.check("file", "note.md").returncode, 0)
        self.write("target.md", "대상\n")
        self.assertEqual(self.check("file", "note.md").returncode, 0)

    def test_java_file_checks_compile_only_the_relevant_modules(self):
        names = ["domain/src/main/java/New.java", "application/src/test/java/NewTest.java"]
        for name in names:
            self.write(name, "class New {}\n")
        result = self.check("file", *names)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.root / ".gradle/tasks").read_text().splitlines(),
                         [":application:compileTestJava", ":domain:compileJava"])

    def test_finish_includes_committed_staged_unstaged_and_untracked_changes(self):
        for state in ("committed", "staged", "unstaged", "untracked"):
            with self.subTest(state=state):
                self.write("note.md", "메모\n")
                if (self.root / "new.json").exists():
                    (self.root / "new.json").unlink()
                self.command("git", "reset", "--quiet", self.base)
                name = "new.json" if state == "untracked" else "note.md"
                self.write(name, "잘못된 공백 \n")
                if state in {"committed", "staged"}:
                    self.command("git", "add", name)
                if state == "committed":
                    self.commit()
                result = self.check("finish", "--base", self.base)
                self.assertNotEqual(result.returncode, 0, result.stdout)
                self.assertIn(name, result.stdout + result.stderr)

    def test_renamed_or_deleted_java_still_runs_architecture_check(self):
        (self.root / "domain/src/main/java/Old.java").rename(self.root / "old.txt")
        self.commit()
        result = self.check("finish", "--base", self.base)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.root / ".gradle/tasks").read_text(), ":bootstrap:architectureTest\n")

    def test_untracked_java_runs_architecture_check(self):
        self.write("domain/src/main/java/New.java", "class New {}\n")
        result = self.check("finish", "--base", self.base)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.root / ".gradle/tasks").read_text(), ":bootstrap:architectureTest\n")

    def test_document_only_changes_do_not_start_gradle(self):
        self.write("note.md", "수정한 메모\n")
        self.commit()
        result = self.check("finish", "--base", self.base)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse((self.root / ".gradle/tasks").exists())

    def test_propagates_gradle_failure_and_rejects_missing_input(self):
        self.write("gradlew", "#!/bin/sh\nexit 7\n")
        self.assertEqual(self.check("file", "domain/src/main/java/Old.java").returncode, 7)
        self.assertNotEqual(self.check("file", "absent.py").returncode, 0)
        self.assertNotEqual(self.check("finish", "--base", "missing-ref").returncode, 0)
        self.assertNotEqual(self.check("finish").returncode, 0)


if __name__ == "__main__":
    unittest.main()
