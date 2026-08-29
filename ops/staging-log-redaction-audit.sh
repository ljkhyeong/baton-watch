#!/usr/bin/env bash

set -euo pipefail
set +x
umask 077

readonly PREFIX="[staging-log-redaction-audit]"

fail() {
    printf '%s %s\n' "$PREFIX" "$1" >&2
    exit 1
}

if (( $# < 3 )); then
    fail "사용법: $0 <로그 파일> <금지 값 파일> <비밀 파일>..."
fi
if ! command -v python3 >/dev/null 2>&1; then
    fail "python3가 필요합니다"
fi

readonly log_file="$1"
readonly forbidden_values_file="$2"
shift 2
readonly -a secret_files=("$@")

for input_file in "$log_file" "$forbidden_values_file" "${secret_files[@]}"; do
    if [[ ! -f "$input_file" || ! -r "$input_file" ]]; then
        fail "감사 입력은 읽을 수 있는 일반 파일이어야 합니다"
    fi
done
unset input_file

python3 - "$log_file" "$forbidden_values_file" "${secret_files[@]}" <<'PY'
from pathlib import Path
import re
import sys

log_path = Path(sys.argv[1])
forbidden_values_path = Path(sys.argv[2])
secret_paths = [Path(value) for value in sys.argv[3:]]

def load_values(paths):
    values = []
    for path in paths:
        values.extend(value for value in path.read_bytes().splitlines() if value)
    return tuple(values)

secret_values = load_values(secret_paths)
forbidden_values = load_values((forbidden_values_path,))
if not secret_values:
    raise SystemExit("[staging-log-redaction-audit] 비밀 파일에 검사할 값이 없습니다")
if not forbidden_values:
    raise SystemExit("[staging-log-redaction-audit] 금지 값 파일에 검사할 값이 없습니다")

findings = {
    "정확한 비밀값": False,
    "Authorization 헤더": False,
    "Bearer 자격 증명": False,
    "지정한 금지 값": False,
}
authorization_pattern = re.compile(rb"\bauthorization\s*[:=]", re.IGNORECASE)
bearer_pattern = re.compile(rb"\bbearer\s+[A-Za-z0-9._~+/=-]{8,}", re.IGNORECASE)

with log_path.open("rb") as log:
    for line in log:
        findings["정확한 비밀값"] |= any(value in line for value in secret_values)
        findings["지정한 금지 값"] |= any(value in line for value in forbidden_values)
        findings["Authorization 헤더"] |= authorization_pattern.search(line) is not None
        findings["Bearer 자격 증명"] |= bearer_pattern.search(line) is not None

failed = False
for category, matched in findings.items():
    result = "실패" if matched else "통과"
    print(f"[staging-log-redaction-audit] {category}: {result}")
    failed |= matched

raise SystemExit(1 if failed else 0)
PY
