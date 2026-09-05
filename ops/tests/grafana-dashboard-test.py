#!/usr/bin/env python3
"""대시보드의 실제 PromQL을 읽어 promtool 시험 입력을 만든다."""

import json
from pathlib import Path
import sys


dashboard = json.loads((Path(__file__).parents[1] / "grafana/watch-overview.json").read_text())
metrics = {
    "up": "1+0x5",
    "baton_watch_check_schedule_delay_seconds": "120+0x5",
    "baton_watch_event_delivery_backlog": "42+0x5",
    "baton_watch_event_delivery_oldest_age_seconds": "900+0x5",
    "baton_watch_database_clock_offset_seconds": "-0.5+0x5",
    'hikaricp_connections_active{pool="watch"}': "2+0x5",
    'hikaricp_connections_pending{pool="watch"}': "1+0x5",
}
for worker in ("check", "event_delivery"):
    prefix = "baton_watch_" + worker
    outcome = "success" if worker == "check" else "delivered"
    metrics[prefix + '_attempts_total{outcome="' + outcome + '"}'] = "0+60x5"
    metrics[prefix + "_duration_seconds_sum"] = "0+120x5"
    metrics[prefix + "_duration_seconds_count"] = "0+60x5"
    metrics[prefix + '_finalizations_total{status="failure"}'] = "0+1x5"
    metrics[prefix + "_lease_recoveries_total"] = "0+1x5"
    metrics[prefix + "_inflight"] = "1+0x5"
for scheduler, functions in {
    "MonitoringScheduler": ["checkDueMonitors"],
    "EventDeliveryScheduler": ["deliverPendingEvents"],
    "MonitoringMaintenanceScheduler": ["markStaleProjections", "purgeAttemptHistory",
                                       "updateDatabaseClockOffset", "refreshCheckScheduleDelay"],
    "EventDeliveryMaintenanceScheduler": ["purgeDeliveredEventHistory", "refreshEventDeliveryBacklog"],
}.items():
    for function in functions:
        metrics['tasks_scheduled_execution_seconds_count{code_namespace="com.personal.baton.watch.bootstrap.'
                + scheduler + '",code_function="' + function + '",outcome="SUCCESS"}'] = "0+1x5"

series = []
for instance in ("test-1", "test-2"):
    labels = 'job="baton-watch",instance="' + instance + '"'
    for metric, values in metrics.items():
        series.append({
            "series": metric.replace("{", "{" + labels + ",", 1)
            if "{" in metric else metric + "{" + labels + "}",
            "values": values,
        })

queries = []
missing_queries = []
for panel in dashboard["panels"]:
    for target in panel["targets"]:
        expression = target["expr"].replace("$__rate_interval", "5m").replace("$instance", "test-.*")
        # 인스턴스 둘의 데이터를 합치거나 존재하지 않는 메트릭을 조회하면 실패한다.
        expected_count = 16 if panel["id"] == 9 else 2
        queries.append({"expr": "count(" + expression + ")", "eval_time": "5m",
                        "exp_samples": [{"labels": "{}", "value": expected_count}]})
        missing_queries.append({"expr": expression, "eval_time": "5m", "exp_samples": []})

# 같은 DB의 적체 42건을 두 인스턴스가 각각 보고해도 84건으로 합산하지 않는다.
queries.append({"expr": dashboard["panels"][2]["targets"][0]["expr"].replace("$instance", "test-.*"),
                "eval_time": "5m", "exp_samples": [
                    {"labels": '{instance="test-1"}', "value": 42},
                    {"labels": '{instance="test-2"}', "value": 42}]})
json.dump({"rule_files": [], "evaluation_interval": "1m", "tests": [
    {"name": "인스턴스별 지표와 DB 적체 중복 합산 방지", "interval": "1m",
     "input_series": series, "promql_expr_test": queries},
    {"name": "수집 누락을 정상 값으로 대체하지 않음", "interval": "1m",
     "input_series": [], "promql_expr_test": missing_queries},
]}, sys.stdout, ensure_ascii=False)
