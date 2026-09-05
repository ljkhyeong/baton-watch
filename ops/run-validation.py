#!/usr/bin/env python3
"""검증 명령의 종료 코드와 로그, 실행 당시 작업 파일 지문을 보관한다."""

import argparse
from collections import deque
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import subprocess
import sys
import time


def git(root, *args):
    return subprocess.check_output(["git", "-C", str(root), *args], stderr=subprocess.PIPE)


def workspace(root):
    digest = hashlib.sha256()
    files = git(root, "ls-files", "-z", "--cached", "--others", "--exclude-standard")
    for name in sorted(set(files.split(b"\0")) - {b""}):
        path = root / os.fsdecode(name)
        digest.update(name + b"\0")
        if path.is_symlink():
            digest.update(b"symlink\0" + os.fsencode(os.readlink(path)))
        elif path.is_file():
            digest.update(str(path.stat().st_mode & 0o777).encode() + b"\0")
            with path.open("rb") as stream:
                digest.update(hashlib.file_digest(stream, "sha256").digest())
        else:
            digest.update(b"deleted")
    return {"commit": git(root, "rev-parse", "HEAD").decode().strip(), "files_sha256": digest.hexdigest()}


def status(root, evidence):
    current = workspace(root)
    for path in sorted(evidence.glob("*/result.json"), reverse=True)[:10]:
        record = json.loads(path.read_text())
        unchanged = record["before"]["files_sha256"] == record["after"]["files_sha256"] == current["files_sha256"]
        print(f"{record['started_at']} {record['label']}: {record['outcome']} "
              f"(종료 {record['exit_code']}, 파일 {'동일' if unchanged else '변경됨'})\n  {path}")


def run(root, evidence, label, command):
    started = datetime.now(timezone.utc)
    directory = evidence / f"{started:%Y%m%dT%H%M%S%fZ}-{label}"
    directory.mkdir(parents=True, mode=0o700)
    log = directory / "output.log"
    record = {"label": label, "command": command, "started_at": started.isoformat(),
              "before": workspace(root), "platform": platform.platform(),
              "python": sys.version.split()[0]}
    print(f"검증 시작: {label}\n로그: {log}", flush=True)
    begin = time.monotonic()
    with log.open("x", encoding="utf-8") as output:
        log.chmod(0o600)
        try:
            code = subprocess.run(command, cwd=root, stdout=output, stderr=subprocess.STDOUT).returncode
            code = code if code >= 0 else 128 - code
        except FileNotFoundError as error:
            output.write(f"실행 파일을 찾을 수 없습니다: {error.filename}\n")
            code = 127
        except PermissionError as error:
            output.write(f"실행 권한이 없습니다: {error.filename}\n")
            code = 126
        except KeyboardInterrupt:
            output.write("검증 실행이 중단됐습니다.\n")
            code = 130
    record.update(after=workspace(root), exit_code=code, outcome="passed" if code == 0 else "failed",
                  elapsed_seconds=round(time.monotonic() - begin, 3),
                  finished_at=datetime.now(timezone.utc).isoformat())
    result = directory / "result.json"
    with result.open("x", encoding="utf-8") as output:
        result.chmod(0o600)
        json.dump(record, output, ensure_ascii=False, indent=2)
        output.write("\n")
    print(f"검증 {'통과' if code == 0 else '실패'}: {label}, 종료 코드 {code}, {record['elapsed_seconds']}초\n기록: {result}")
    if record["before"]["files_sha256"] != record["after"]["files_sha256"]:
        print("실행 중 작업 파일이 바뀌었습니다. 결과를 현재 파일의 검증으로 재사용하지 마세요.")
    if code:
        with log.open(errors="replace") as output:
            print("".join(deque(output, maxlen=20))[-4000:], end="")
    return code


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_subparsers(dest="mode", required=True)
    modes.add_parser("status", help="최근 검증 10건과 현재 파일의 일치 여부")
    execute = modes.add_parser("run", help="검증 명령 하나 실행")
    execute.add_argument("--label", required=True, help="검증 이름: 영문 소문자·숫자·하이픈")
    execute.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    if args.mode == "run":
        if not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,63}", args.label):
            parser.error("검증 이름은 영문 소문자·숫자·하이픈 1~64자로 지정하세요.")
        args.command = args.command[1:] if args.command[:1] == ["--"] else args.command
        if not args.command:
            parser.error("-- 뒤에 검증 명령을 지정하세요.")
    try:
        root = Path(git(Path.cwd(), "rev-parse", "--show-toplevel").decode().strip())
        evidence = root / ".gradle" / "agent-validation"
        if args.mode == "status":
            status(root, evidence)
            return 0
        return run(root, evidence, args.label, args.command)
    except (OSError, subprocess.CalledProcessError) as error:
        print(f"검증 기록을 만들 수 없습니다: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
