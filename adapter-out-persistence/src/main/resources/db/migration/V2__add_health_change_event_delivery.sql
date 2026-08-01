ALTER TABLE watch_health_change_event
    ADD COLUMN delivery_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN delivery_attempt INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at TIMESTAMPTZ,
    ADD COLUMN delivery_lease_token UUID,
    ADD COLUMN delivery_lease_expires_at TIMESTAMPTZ,
    ADD COLUMN delivered_at TIMESTAMPTZ,
    ADD COLUMN last_delivery_outcome VARCHAR(40),
    ADD COLUMN last_http_status_code INTEGER;

UPDATE watch_health_change_event
SET next_attempt_at = changed_at;

ALTER TABLE watch_health_change_event
    ADD CONSTRAINT watch_health_event_delivery_status CHECK (
        delivery_status IN ('PENDING', 'DELIVERED')
    ),
    ADD CONSTRAINT watch_health_event_delivery_attempt CHECK (
        delivery_attempt >= 0
    ),
    ADD CONSTRAINT watch_health_event_delivery_state CHECK (
        (
            delivery_status = 'PENDING'
            AND delivered_at IS NULL
            AND next_attempt_at IS NOT NULL
            AND next_attempt_at >= changed_at
            AND (last_delivery_outcome IS NULL OR last_delivery_outcome <> 'DELIVERED')
        )
        OR
        (
            delivery_status = 'DELIVERED'
            AND delivered_at IS NOT NULL
            AND delivered_at >= changed_at
            AND next_attempt_at IS NULL
            AND delivery_lease_token IS NULL
            AND delivery_lease_expires_at IS NULL
            AND delivery_attempt > 0
            AND last_delivery_outcome = 'DELIVERED'
        )
    ),
    ADD CONSTRAINT watch_health_event_delivery_lease CHECK (
        (delivery_lease_token IS NULL AND delivery_lease_expires_at IS NULL)
        OR
        (
            delivery_status = 'PENDING'
            AND delivery_lease_token IS NOT NULL
            AND delivery_lease_expires_at IS NOT NULL
            AND delivery_lease_expires_at > next_attempt_at
            AND delivery_attempt > 0
        )
    ),
    ADD CONSTRAINT watch_health_event_delivery_outcome CHECK (
        last_delivery_outcome IS NULL
        OR last_delivery_outcome IN (
            'DELIVERED',
            'HTTP_CLIENT_ERROR',
            'HTTP_SERVER_ERROR',
            'DESTINATION_REJECTED',
            'DNS_FAILURE',
            'CONNECT_TIMEOUT',
            'READ_TIMEOUT',
            'TLS_FAILURE',
            'RESPONSE_TOO_LARGE',
            'NETWORK_FAILURE',
            'INTERNAL_FAILURE'
        )
    ),
    ADD CONSTRAINT watch_health_event_delivery_http_status CHECK (
        (last_delivery_outcome IS NULL AND last_http_status_code IS NULL)
        OR (last_delivery_outcome = 'DELIVERED' AND last_http_status_code BETWEEN 200 AND 299)
        OR (last_delivery_outcome = 'HTTP_CLIENT_ERROR' AND last_http_status_code BETWEEN 300 AND 499)
        OR (last_delivery_outcome = 'HTTP_SERVER_ERROR' AND last_http_status_code BETWEEN 500 AND 599)
        OR (
            last_delivery_outcome NOT IN ('DELIVERED', 'HTTP_CLIENT_ERROR', 'HTTP_SERVER_ERROR')
            AND last_http_status_code IS NULL
        )
    );

CREATE UNIQUE INDEX ux_watch_health_event_delivery_lease
    ON watch_health_change_event (delivery_lease_token)
    WHERE delivery_lease_token IS NOT NULL;

CREATE INDEX ix_watch_health_event_delivery_due
    ON watch_health_change_event (next_attempt_at, delivery_lease_expires_at, changed_at, event_id)
    WHERE delivery_status = 'PENDING';

CREATE INDEX ix_watch_health_event_delivery_retention
    ON watch_health_change_event (delivered_at, event_id)
    WHERE delivery_status = 'DELIVERED';
