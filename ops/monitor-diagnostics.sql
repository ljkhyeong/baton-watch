BEGIN READ ONLY;
SET LOCAL statement_timeout = '5s';
SET LOCAL lock_timeout = '1s';
SET LOCAL transaction_timeout = '10s';
SET LOCAL idle_in_transaction_session_timeout = '5s';
SET LOCAL TIME ZONE 'UTC';
SET LOCAL search_path = pg_catalog, public;

-- 한 SELECT의 스냅샷에서 현재 상태와 최근 이력을 읽는다. URL과 리스 토큰은 선택하지 않는다.
SELECT json_build_object(
    'observedAt', statement_timestamp(),
    'readOnly', current_setting('transaction_read_only')::boolean,
    'monitor', (
        SELECT row_to_json(monitor)
        FROM (
            SELECT source_revision AS "sourceRevision", monitor_status AS "monitoringState",
                   current_health AS health, consecutive_failures AS "consecutiveFailures",
                   last_outcome AS "lastOutcome", last_checked_at AS "lastCheckedAt",
                   last_conclusive_at AS "lastConclusiveAt", next_check_at AS "nextCheckAt"
            FROM public.watch_monitor
            WHERE resource_reference = :'resource_reference'
        ) monitor
    ),
    'checks', (
        SELECT coalesce(json_agg(checks ORDER BY checks."claimedAt" DESC, checks."attemptId" DESC), '[]'::json)
        FROM (
            SELECT attempt.attempt_id AS "attemptId", attempt.source_revision AS "sourceRevision",
                   attempt.claimed_at AS "claimedAt", result.outcome,
                   result.http_status_code AS "httpStatusCode", result.completed_at AS "completedAt",
                   result.duration_seconds + result.duration_nanos / 1000000000.0 AS "durationSeconds",
                   result.response_bytes AS "responseBytes", result.redirect_count AS "redirectCount"
            FROM public.watch_attempt attempt
            LEFT JOIN public.watch_result result USING (attempt_id)
            WHERE attempt.resource_reference = :'resource_reference'
            ORDER BY attempt.claimed_at DESC, attempt.attempt_id DESC
            LIMIT :'row_limit'::integer
        ) checks
    ),
    'deliveries', (
        SELECT coalesce(json_agg(delivery ORDER BY delivery."changedAt" DESC, delivery."eventId" DESC), '[]'::json)
        FROM (
            SELECT event_id AS "eventId", source_revision AS "sourceRevision",
                   changed_at AS "changedAt", current_health AS "currentHealth",
                   delivery_status AS "deliveryStatus", delivery_attempt AS "deliveryAttempt",
                   last_delivery_outcome AS "lastDeliveryOutcome",
                   last_http_status_code AS "lastHttpStatusCode",
                   next_attempt_at AS "nextAttemptAt", delivered_at AS "deliveredAt"
            FROM public.watch_health_change_event
            WHERE resource_reference = :'resource_reference'
            ORDER BY changed_at DESC, event_id DESC
            LIMIT :'row_limit'::integer
        ) delivery
    )
);
COMMIT;
