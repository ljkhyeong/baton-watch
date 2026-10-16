#!/usr/bin/env python3
"""Gradle JUnit XML 결과에서 모듈·작업별 테스트 개수와 실행 시각을 집계한다."""

import argparse
from pathlib import Path
import subprocess
import sys
from xml.etree import ElementTree

COUNTS = ("tests", "skipped", "failures", "errors")


def summarize(directory):
    total = dict.fromkeys(COUNTS, 0)
    suites = 0
    stamps = []
    for report in sorted(directory.glob("TEST-*.xml")):
        suite = ElementTree.parse(report).getroot()
        suites += 1
        for key in COUNTS:
            total[key] += int(suite.get(key, "0"))
        if suite.get("timestamp"):
            stamps.append(suite.get("timestamp"))
    return suites, total, stamps


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("targets", nargs="*", help="모듈 또는 모듈:작업. 생략하면 모든 모듈의 test")
    args = parser.parse_args()
    root = Path(subprocess.check_output(["git", "rev-parse", "--show-toplevel"], text=True).strip())

    if args.targets:
        targets = [target if ":" in target else f"{target}:test" for target in args.targets]
    else:
        targets = sorted(f"{path.parents[2].name}:test" for path in root.glob("*/build/test-results/test"))

    grand = dict.fromkeys(COUNTS, 0)
    ok = bool(targets)
    for target in targets:
        module, task = target.split(":", 1)
        suites, total, stamps = summarize(root / module / "build" / "test-results" / task)
        if not suites:
            print(f"{target}: 결과 없음")
            ok = False
            continue
        for key in COUNTS:
            grand[key] += total[key]
        print(f"{target}: 스위트 {suites} · 테스트 {total['tests']} · 건너뜀 {total['skipped']}"
              f" · 실패 {total['failures']} · 오류 {total['errors']} · 실행 {min(stamps)} ~ {max(stamps)}")
        ok = ok and not total["failures"] and not total["errors"]

    if len(targets) > 1:
        print(f"합계: 테스트 {grand['tests']} · 건너뜀 {grand['skipped']}"
              f" · 실패 {grand['failures']} · 오류 {grand['errors']}")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
