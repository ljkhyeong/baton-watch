CREATE TABLE watch_monitor (
    resource_reference       VARCHAR(128) PRIMARY KEY,
    source_revision          BIGINT NOT NULL CHECK (source_revision >= 0),
    monitor_status           VARCHAR(16) NOT NULL CHECK (monitor_status IN ('ACTIVE', 'INACTIVE')),
    target_url               VARCHAR(2048),
    current_health           VARCHAR(16) NOT NULL CHECK (current_health IN ('UNKNOWN', 'HEALTHY', 'DEGRADED', 'BROKEN')),
    consecutive_failures     INTEGER NOT NULL DEFAULT 0 CHECK (consecutive_failures >= 0),
    last_outcome             VARCHAR(40) CHECK (last_outcome IN (
        'SUCCESS',
        'HTTP_CLIENT_ERROR',
        'HTTP_SERVER_ERROR',
        'DESTINATION_REJECTED',
        'DNS_FAILURE',
        'CONNECT_TIMEOUT',
        'READ_TIMEOUT',
        'TLS_FAILURE',
        'REDIRECT_REJECTED',
        'TOO_MANY_REDIRECTS',
        'RESPONSE_TOO_LARGE',
        'NETWORK_FAILURE',
        'INTERNAL_FAILURE'
    )),
    last_checked_at          TIMESTAMPTZ,
    last_conclusive_at       TIMESTAMPTZ,
    next_check_at            TIMESTAMPTZ,
    lease_token              UUID,
    lease_attempt_id         UUID,
    lease_expires_at         TIMESTAMPTZ,
    last_check_requested_at  TIMESTAMPTZ,
    CONSTRAINT watch_monitor_target_matches_status CHECK (
        (monitor_status = 'ACTIVE' AND target_url IS NOT NULL AND next_check_at IS NOT NULL)
        OR
        (monitor_status = 'INACTIVE' AND target_url IS NULL AND next_check_at IS NULL)
    ),
    CONSTRAINT watch_monitor_last_result_is_complete CHECK (
        (last_outcome IS NULL AND last_checked_at IS NULL)
        OR
        (last_outcome IS NOT NULL AND last_checked_at IS NOT NULL)
    ),
    CONSTRAINT watch_monitor_health_matches_failures CHECK (
        current_health = 'UNKNOWN'
        OR (current_health = 'HEALTHY' AND consecutive_failures = 0)
        OR (current_health = 'DEGRADED' AND consecutive_failures BETWEEN 1 AND 2)
        OR (current_health = 'BROKEN' AND consecutive_failures >= 3)
    ),
    CONSTRAINT watch_monitor_lease_is_complete CHECK (
        (lease_token IS NULL AND lease_attempt_id IS NULL AND lease_expires_at IS NULL)
        OR
        (monitor_status = 'ACTIVE' AND lease_token IS NOT NULL AND lease_attempt_id IS NOT NULL AND lease_expires_at IS NOT NULL)
    )
);

CREATE INDEX ix_watch_monitor_due
    ON watch_monitor (next_check_at, resource_reference)
    WHERE monitor_status = 'ACTIVE';

CREATE INDEX ix_watch_monitor_stale
    ON watch_monitor (last_conclusive_at, resource_reference)
    WHERE monitor_status = 'ACTIVE' AND current_health <> 'UNKNOWN';

CREATE INDEX ix_watch_monitor_lease_attempt
    ON watch_monitor (lease_attempt_id)
    WHERE lease_attempt_id IS NOT NULL;

CREATE TABLE watch_attempt (
    attempt_id               UUID PRIMARY KEY,
    resource_reference       VARCHAR(128) NOT NULL REFERENCES watch_monitor (resource_reference),
    source_revision          BIGINT NOT NULL CHECK (source_revision >= 0),
    target_url               VARCHAR(2048) NOT NULL,
    claimed_at               TIMESTAMPTZ NOT NULL,
    lease_expires_at         TIMESTAMPTZ NOT NULL,
    CONSTRAINT watch_attempt_lease_window CHECK (lease_expires_at > claimed_at)
);

CREATE INDEX ix_watch_attempt_retention
    ON watch_attempt (claimed_at, attempt_id);

CREATE INDEX ix_watch_attempt_monitor
    ON watch_attempt (resource_reference, claimed_at DESC);

CREATE TABLE watch_result (
    attempt_id               UUID PRIMARY KEY REFERENCES watch_attempt (attempt_id) ON DELETE CASCADE,
    outcome                  VARCHAR(40) NOT NULL CHECK (outcome IN (
        'SUCCESS',
        'HTTP_CLIENT_ERROR',
        'HTTP_SERVER_ERROR',
        'DESTINATION_REJECTED',
        'DNS_FAILURE',
        'CONNECT_TIMEOUT',
        'READ_TIMEOUT',
        'TLS_FAILURE',
        'REDIRECT_REJECTED',
        'TOO_MANY_REDIRECTS',
        'RESPONSE_TOO_LARGE',
        'NETWORK_FAILURE',
        'INTERNAL_FAILURE'
    )),
    http_status_code         INTEGER CHECK (http_status_code BETWEEN 100 AND 599),
    completed_at             TIMESTAMPTZ NOT NULL,
    duration_seconds         BIGINT NOT NULL CHECK (duration_seconds >= 0),
    duration_nanos           INTEGER NOT NULL CHECK (duration_nanos BETWEEN 0 AND 999999999),
    redirect_count           SMALLINT NOT NULL CHECK (redirect_count BETWEEN 0 AND 3),
    CONSTRAINT watch_result_http_status_matches_outcome CHECK (
        CASE outcome
            WHEN 'SUCCESS' THEN http_status_code BETWEEN 200 AND 399
            WHEN 'HTTP_CLIENT_ERROR' THEN http_status_code BETWEEN 400 AND 499
            WHEN 'HTTP_SERVER_ERROR' THEN http_status_code BETWEEN 500 AND 599
            ELSE http_status_code IS NULL
        END IS TRUE
    )
);

CREATE INDEX ix_watch_result_retention
    ON watch_result (completed_at, attempt_id);

CREATE TABLE watch_health_change_event (
    event_id                 UUID PRIMARY KEY,
    resource_reference       VARCHAR(128) NOT NULL REFERENCES watch_monitor (resource_reference),
    source_revision          BIGINT NOT NULL CHECK (source_revision >= 0),
    attempt_id               UUID,
    previous_health          VARCHAR(16) NOT NULL CHECK (previous_health IN ('UNKNOWN', 'HEALTHY', 'DEGRADED', 'BROKEN')),
    current_health           VARCHAR(16) NOT NULL CHECK (current_health IN ('UNKNOWN', 'HEALTHY', 'DEGRADED', 'BROKEN')),
    changed_at               TIMESTAMPTZ NOT NULL,
    delivery_status          VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    delivery_attempt         INTEGER NOT NULL DEFAULT 0,
    next_attempt_at          TIMESTAMPTZ,
    delivery_lease_token     UUID,
    delivery_lease_expires_at TIMESTAMPTZ,
    delivered_at             TIMESTAMPTZ,
    last_delivery_outcome    VARCHAR(40),
    last_http_status_code    INTEGER,
    CONSTRAINT watch_health_change_is_a_change CHECK (previous_health <> current_health),
    CONSTRAINT watch_health_event_delivery_status CHECK (
        delivery_status IN ('PENDING', 'DELIVERED')
    ),
    CONSTRAINT watch_health_event_delivery_attempt CHECK (
        delivery_attempt >= 0
    ),
    CONSTRAINT watch_health_event_delivery_state CHECK (
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
    CONSTRAINT watch_health_event_delivery_lease CHECK (
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
    CONSTRAINT watch_health_event_delivery_outcome CHECK (
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
    CONSTRAINT watch_health_event_delivery_http_status CHECK (
        CASE last_delivery_outcome
            WHEN 'DELIVERED' THEN last_http_status_code BETWEEN 200 AND 299
            WHEN 'HTTP_CLIENT_ERROR' THEN last_http_status_code BETWEEN 300 AND 499
            WHEN 'HTTP_SERVER_ERROR' THEN last_http_status_code BETWEEN 500 AND 599
            ELSE last_http_status_code IS NULL
        END IS TRUE
    )
);

CREATE UNIQUE INDEX ux_watch_health_change_event_attempt
    ON watch_health_change_event (attempt_id)
    WHERE attempt_id IS NOT NULL;

CREATE INDEX ix_watch_health_change_event_monitor
    ON watch_health_change_event (resource_reference, changed_at, event_id);

CREATE INDEX ix_watch_health_event_delivery_due
    ON watch_health_change_event (next_attempt_at, changed_at, event_id)
    INCLUDE (delivery_lease_expires_at)
    WHERE delivery_status = 'PENDING';

CREATE INDEX ix_watch_health_event_delivery_retention
    ON watch_health_change_event (delivered_at, event_id)
    WHERE delivery_status = 'DELIVERED';
