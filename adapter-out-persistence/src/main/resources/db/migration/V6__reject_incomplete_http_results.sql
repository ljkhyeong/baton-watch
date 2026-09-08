ALTER TABLE public.watch_result
    DROP CONSTRAINT watch_result_http_status_matches_outcome,
    ADD CONSTRAINT watch_result_http_status_matches_outcome CHECK (
        CASE outcome
            WHEN 'SUCCESS' THEN http_status_code BETWEEN 200 AND 399
            WHEN 'HTTP_CLIENT_ERROR' THEN http_status_code BETWEEN 400 AND 499
            WHEN 'HTTP_SERVER_ERROR' THEN http_status_code BETWEEN 500 AND 599
            ELSE http_status_code IS NULL
        END IS TRUE
    );

ALTER TABLE public.watch_health_change_event
    DROP CONSTRAINT watch_health_event_delivery_state,
    DROP CONSTRAINT watch_health_event_delivery_http_status,
    ADD CONSTRAINT watch_health_event_delivery_state CHECK (
        CASE delivery_status
            WHEN 'PENDING' THEN
                delivered_at IS NULL
                AND next_attempt_at IS NOT NULL
                AND next_attempt_at >= changed_at
                AND (last_delivery_outcome IS NULL OR last_delivery_outcome <> 'DELIVERED')
            WHEN 'DELIVERED' THEN
                delivered_at IS NOT NULL
                AND delivered_at >= changed_at
                AND next_attempt_at IS NULL
                AND delivery_lease_token IS NULL
                AND delivery_lease_expires_at IS NULL
                AND delivery_attempt > 0
                AND last_delivery_outcome = 'DELIVERED'
            ELSE FALSE
        END IS TRUE
    ),
    ADD CONSTRAINT watch_health_event_delivery_http_status CHECK (
        CASE last_delivery_outcome
            WHEN 'DELIVERED' THEN last_http_status_code BETWEEN 200 AND 299
            WHEN 'HTTP_CLIENT_ERROR' THEN last_http_status_code BETWEEN 300 AND 499
            WHEN 'HTTP_SERVER_ERROR' THEN last_http_status_code BETWEEN 500 AND 599
            ELSE last_http_status_code IS NULL
        END IS TRUE
    );
