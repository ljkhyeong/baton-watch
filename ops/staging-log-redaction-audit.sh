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

def load_values(paths, label):
    values = []
    for path in paths:
        file_values = [value for value in path.read_bytes().splitlines() if value]
        if not file_values:
            raise SystemExit(f"[staging-log-redaction-audit] {label} 파일에 검사할 값이 없습니다")
        values.extend(file_values)
    return tuple(values)

secret_values = load_values(secret_paths, "비밀")
forbidden_values = load_values((forbidden_values_path,), "금지 값")

findings = {
    "정확한 비밀값": False,
    "Authorization 헤더": False,
    "Bearer 자격 증명": False,
    "지정한 금지 값": False,
}
authorization_pattern = re.compile(rb"\bauthorization\s*[:=]", re.IGNORECASE)
bearer_pattern = re.compile(rb"\bbearer\s+[A-Za-z0-9._~+/=-]{8,}", re.IGNORECASE)

has_log_content = False
with log_path.open("rb") as log:
    for line in log:
        has_log_content |= bool(line.strip())
        findings["정확한 비밀값"] |= any(value in line for value in secret_values)
        findings["지정한 금지 값"] |= any(value in line for value in forbidden_values)
        findings["Authorization 헤더"] |= authorization_pattern.search(line) is not None
        findings["Bearer 자격 증명"] |= bearer_pattern.search(line) is not None

if not has_log_content:
    raise SystemExit("[staging-log-redaction-audit] 검사할 로그가 없습니다. 수집 대상과 기간을 확인하세요")

failed = False
for category, matched in findings.items():
    result = "실패" if matched else "통과"
    print(f"[staging-log-redaction-audit] {category}: {result}")
    failed |= matched

raise SystemExit(1 if failed else 0)
PY
