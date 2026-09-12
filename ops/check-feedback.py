#!/usr/bin/env python3
"""파일 작성 직후의 빠른 검사와 작업 종료 전의 전체 변경 검사를 실행한다."""

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tomllib
from urllib.parse import unquote, urlsplit
from xml.etree import ElementTree


def git(root, *args):
    return subprocess.check_output(["git", "-C", str(root), *args])


def changed_paths(root, base):
    tracked = git(root, "diff", "--name-only", "--no-renames", "-z", base, "--")
    untracked = git(root, "ls-files", "--others", "--exclude-standard", "-z")
    return sorted({os.fsdecode(name) for name in (tracked + untracked).split(b"\0") if name})


def check_file(root, name):
    path = root / name
    if not path.exists() or path.is_symlink():
        return
    data = path.read_bytes()
    text_types = {".java", ".py", ".json", ".md", ".sh", ".xml", ".toml"}
    if b"\0" in data and path.suffix not in text_types:
        return
    try:
        source = data.decode("utf-8")
    except UnicodeDecodeError:
        if path.suffix in text_types:
            raise ValueError(f"{name}: UTF-8 인코딩이 아닙니다.")
        return
    if source and not source.endswith("\n"):
        raise ValueError(f"{name}: 파일 끝에 줄바꿈이 필요합니다.")
    for line, text in enumerate(source.splitlines(keepends=True), 1):
        if text.endswith("\r\n") or text.rstrip("\r\n").endswith((" ", "\t")):
            raise ValueError(f"{name}:{line}: 줄 끝 공백 또는 CRLF를 제거하세요.")
    try:
        if path.suffix == ".py":
            compile(source, name, "exec", dont_inherit=True)
        elif path.suffix == ".json":
            json.loads(source)
        elif path.suffix == ".toml":
            tomllib.loads(source)
        elif path.suffix == ".xml":
            ElementTree.fromstring(source)
        elif path.suffix == ".sh" or path.name == "gradlew":
            subprocess.run(["bash", "-n", str(path)], check=True)
        elif path.suffix == ".md":
            source = re.sub(r"(?ms)^(```|~~~).*?^\1[^\n]*$", "", source)
            for target in re.findall(r"\]\((<[^>]+>|[^\s)]+)(?:\s+\"[^\"]*\")?\)", source):
                target = target.strip("<>")
                link = urlsplit(target)
                if link.scheme or link.netloc or not link.path:
                    continue
                if not (path.parent / unquote(link.path)).exists():
                    raise ValueError(f"{name}: 로컬 링크 대상을 찾을 수 없습니다: {target}")
    except (SyntaxError, ValueError, ElementTree.ParseError) as error:
        line = getattr(error, "lineno", None)
        detail = getattr(error, "msg", str(error))
        raise ValueError(f"{name}{':' + str(line) if line else ''}: {detail}") from error


def gradle_tasks(paths, finish):
    tasks = set()
    structural = False
    for name in paths:
        path = Path(name)
        if (path.suffix in {".java", ".gradle"} or name in {"gradle.properties", "gradlew", "gradlew.bat"}
                or name.startswith("gradle/") or name == "ops/check-feedback.py"):
            structural = True
        if finish:
            continue
        parts = path.parts
        if path.suffix == ".java" and len(parts) > 4 and parts[1:3] == ("src", "main"):
            tasks.add(f":{parts[0]}:compileJava")
        elif path.suffix == ".java" and len(parts) > 4 and parts[1:3] == ("src", "test"):
            tasks.add(f":{parts[0]}:compileTestJava")
        elif (path.suffix == ".gradle" or name in {"gradle.properties", "gradlew", "gradlew.bat"}
              or name.startswith("gradle/")):
            tasks.add("help")
    if finish and structural:
        return [":bootstrap:architectureTest"]
    return sorted(tasks)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_subparsers(dest="mode", required=True)
    local = modes.add_parser("file", help="지정한 파일의 형식·문법·Java 컴파일 검사")
    local.add_argument("paths", nargs="+")
    final = modes.add_parser("finish", help="기준 커밋 이후 변경과 전체 계층 의존성 검사")
    final.add_argument("--base", required=True, help="작업 시작 때 기록한 커밋 또는 PR 기준 커밋")
    args = parser.parse_args()
    try:
        root = Path(git(Path.cwd(), "rev-parse", "--show-toplevel").decode().strip())
        if args.mode == "finish":
            base = git(root, "rev-parse", "--verify", args.base + "^{commit}").decode().strip()
            paths = changed_paths(root, base)
            subprocess.run(["git", "diff", "--check", base, "--"], cwd=root, check=True)
            subprocess.run(["git", "diff", "--stat", base, "--"], cwd=root, check=True)
        else:
            paths = [str(Path(os.path.abspath(name)).relative_to(root)) for name in args.paths]
            for name in paths:
                if not (root / name).is_file():
                    raise ValueError(f"검사할 파일이 없습니다: {name}")
        for name in paths:
            check_file(root, name)
        print(f"파일 검사 통과: {len(paths)}개 (새 파일·삭제 포함)", flush=True)
        tasks = gradle_tasks(paths, args.mode == "finish")
        if tasks:
            print("Java 검사: " + " ".join(tasks), flush=True)
            subprocess.run([str(root / "gradlew"), *tasks], cwd=root, check=True)
        if args.mode == "finish":
            print(f"완료 전 diff 검토 기준: {base}\n"
                  "추적 파일의 전체 diff와 새 파일 본문도 검토하세요. 동작 테스트는 변경 범위별로 별도 실행합니다.")
        return 0
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f"검사 실패: {error}", file=sys.stderr)
        return error.returncode if isinstance(error, subprocess.CalledProcessError) else 1


if __name__ == "__main__":
    sys.exit(main())
