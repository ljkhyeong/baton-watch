#!/usr/bin/env bash

set -euo pipefail
TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly TEST_DIR
# 버전과 멀티 아키텍처 다이제스트를 고정한다. 수집기나 알림 서버는 실행하지 않는다.
readonly PROMETHEUS_IMAGE='prom/prometheus:v3.13.1-distroless@sha256:214f8427c8fba80c327bb94a75feb802ae12f2d6ca30812aa6e7d22f09bbea80'
dashboard_test_dir="$(mktemp -d)"
trap 'rm -rf "$dashboard_test_dir"' EXIT
python3 "$TEST_DIR/grafana-dashboard-test.py" >"$dashboard_test_dir/queries.json"
chmod 0444 "$dashboard_test_dir/queries.json"

promtool() {
    docker run --rm --network none --read-only --cap-drop ALL \
        --security-opt no-new-privileges:true --memory 256m --cpus 1 \
        --tmpfs /tmp:rw,noexec,nosuid,nodev,size=64m \
        --entrypoint /bin/promtool \
        --volume "$TEST_DIR/../prometheus:/rules:ro" --workdir /rules \
        --volume "$dashboard_test_dir/queries.json:/dashboard-tests/queries.json:ro" \
        "$PROMETHEUS_IMAGE" "$@"
}

promtool check rules watch-alerts.yml
promtool check config watch-ingress-scrape.yml
promtool test rules watch-ingress-test.yml
promtool test rules watch-alerts-test.yml
promtool test rules watch-workers-test.yml
promtool test rules /dashboard-tests/queries.json
