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
    created_at               TIMESTAMPTZ NOT NULL,
    updated_at               TIMESTAMPTZ NOT NULL,
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

CREATE UNIQUE INDEX ux_watch_monitor_lease_token
    ON watch_monitor (lease_token)
    WHERE lease_token IS NOT NULL;

CREATE INDEX ix_watch_monitor_due
    ON watch_monitor (next_check_at, resource_reference)
    WHERE monitor_status = 'ACTIVE';

CREATE INDEX ix_watch_monitor_stale
    ON watch_monitor (last_conclusive_at, resource_reference)
    WHERE monitor_status = 'ACTIVE' AND current_health <> 'UNKNOWN';

CREATE TABLE watch_attempt (
    attempt_id               UUID PRIMARY KEY,
    resource_reference       VARCHAR(128) NOT NULL REFERENCES watch_monitor (resource_reference),
    source_revision          BIGINT NOT NULL CHECK (source_revision >= 0),
    target_url               VARCHAR(2048) NOT NULL,
    lease_token              UUID NOT NULL UNIQUE,
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
    response_bytes           BIGINT NOT NULL CHECK (response_bytes >= 0),
    redirect_count           SMALLINT NOT NULL CHECK (redirect_count BETWEEN 0 AND 3),
    CONSTRAINT watch_result_http_status_matches_outcome CHECK (
        (outcome = 'SUCCESS' AND http_status_code BETWEEN 200 AND 399)
        OR (outcome = 'HTTP_CLIENT_ERROR' AND http_status_code BETWEEN 400 AND 499)
        OR (outcome = 'HTTP_SERVER_ERROR' AND http_status_code BETWEEN 500 AND 599)
        OR (outcome NOT IN ('SUCCESS', 'HTTP_CLIENT_ERROR', 'HTTP_SERVER_ERROR') AND http_status_code IS NULL)
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
    CONSTRAINT watch_health_change_is_a_change CHECK (previous_health <> current_health)
);

CREATE UNIQUE INDEX ux_watch_health_change_event_attempt
    ON watch_health_change_event (attempt_id)
    WHERE attempt_id IS NOT NULL;

CREATE INDEX ix_watch_health_change_event_monitor
    ON watch_health_change_event (resource_reference, changed_at, event_id);
