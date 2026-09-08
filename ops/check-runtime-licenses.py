#!/usr/bin/env python3
"""부트 JAR의 라이선스를 검사한 뒤 Trivy에 전달할 제외 목록을 출력한다."""

import argparse
import json
from pathlib import Path


def checked_exclusions(document, allowed):
    # Trivy 0.74가 라이선스를 싣지 않지만 배포 POM은 Apache-2.0을 선언한다.
    approved_missing = {
        "pkg:maven/org.flywaydb/flyway-database-postgresql@13.4.0",
    }
    approved_alternatives = {
        "pkg:maven/ch.qos.logback/logback-classic@1.5.38": {"LGPL-2.1-only"},
        "pkg:maven/ch.qos.logback/logback-core@1.5.38": {"LGPL-2.1-only"},
        "pkg:maven/jakarta.annotation/jakarta.annotation-api@3.0.0": {
            "GPL-2.0-only WITH classpath-exception"
        },
    }

    missing = set()
    alternatives = {}
    for component in document["components"]:
        identifiers = {
            entry.get("license", {}).get("id")
            or entry.get("license", {}).get("name")
            or entry.get("expression")
            for entry in component.get("licenses", [])
        } - {None}
        purl = component.get("purl", component.get("bom-ref", "알 수 없는 구성요소"))
        if not identifiers:
            missing.add(purl)
            continue
        if identifiers.isdisjoint(allowed):
            raise SystemExit(
                f"허용 라이선스가 없는 런타임 의존성: {purl}, 라이선스={sorted(identifiers)}"
            )
        additional = identifiers - allowed
        if additional:
            alternatives[purl] = additional
    if missing != approved_missing:
        raise SystemExit(
            "라이선스를 판별하지 못한 런타임 의존성이 승인 목록과 다릅니다: "
            f"실제={sorted(missing)}, 승인={sorted(approved_missing)}"
        )
    if alternatives != approved_alternatives:
        raise SystemExit(
            "대체 라이선스가 있는 런타임 의존성이 승인 목록과 다릅니다: "
            f"실제={alternatives}, 승인={approved_alternatives}"
        )

    # 모든 의존성의 허용 라이선스와 패키지·버전별 예외를 확인한 뒤에만 제외한다.
    return allowed | {
        identifier
        for identifiers in approved_alternatives.values()
        for identifier in identifiers
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("reports", type=Path, help="CycloneDX SBOM 보고서 디렉터리")
    reports = parser.parse_args().reports
    for report in sorted(reports.glob("*.cdx.json")):
        document = json.loads(report.read_text())
        if document.get("bomFormat") != "CycloneDX":
            raise SystemExit(f"CycloneDX 보고서가 올바르지 않습니다: {report}")
        if not document.get("components"):
            raise SystemExit(f"SBOM에 구성요소가 없습니다: {report}")

    policy = Path(__file__).resolve().parent.parent / ".github/dependency-review-config.yml"
    allowed = set()
    reading_allowlist = False
    for line in policy.read_text().splitlines():
        if line == "allow-licenses:":
            reading_allowlist = True
            continue
        if reading_allowlist and line.startswith("  - "):
            allowed.add(line.removeprefix("  - "))
            continue
        if reading_allowlist and line.strip():
            break
    if not allowed:
        raise SystemExit("런타임 라이선스 허용 목록이 비어 있습니다.")

    document = json.loads((reports / "baton-watch.cdx.json").read_text())
    print(",".join(sorted(checked_exclusions(document, allowed))))


if __name__ == "__main__":
    main()
