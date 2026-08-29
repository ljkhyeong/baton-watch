REVOKE ALL PRIVILEGES
    ON ALL TABLES IN SCHEMA public
    FROM ${runtimeRole};

REVOKE ALL PRIVILEGES
    ON ALL SEQUENCES IN SCHEMA public
    FROM ${runtimeRole};

REVOKE ALL PRIVILEGES
    ON ALL FUNCTIONS IN SCHEMA public
    FROM ${runtimeRole};

REVOKE ALL PRIVILEGES
    ON ALL TABLES IN SCHEMA public
    FROM PUBLIC;

REVOKE ALL PRIVILEGES
    ON ALL SEQUENCES IN SCHEMA public
    FROM PUBLIC;

REVOKE EXECUTE
    ON ALL FUNCTIONS IN SCHEMA public
    FROM PUBLIC;

REVOKE UPDATE (
    attempt_id,
    resource_reference,
    source_revision,
    target_url,
    lease_token,
    claimed_at,
    lease_expires_at
)
    ON TABLE watch_attempt
    FROM ${runtimeRole};

REVOKE UPDATE (
    attempt_id,
    outcome,
    http_status_code,
    completed_at,
    duration_seconds,
    duration_nanos,
    response_bytes,
    redirect_count
)
    ON TABLE watch_result
    FROM ${runtimeRole};

REVOKE UPDATE (
    event_id,
    resource_reference,
    source_revision,
    attempt_id,
    previous_health,
    current_health,
    changed_at
)
    ON TABLE watch_health_change_event
    FROM ${runtimeRole};

GRANT SELECT, INSERT, UPDATE
    ON TABLE watch_monitor
    TO ${runtimeRole};

GRANT SELECT, INSERT, DELETE
    ON TABLE watch_attempt
    TO ${runtimeRole};

GRANT SELECT, INSERT
    ON TABLE watch_result
    TO ${runtimeRole};

GRANT SELECT, INSERT, DELETE
    ON TABLE watch_health_change_event
    TO ${runtimeRole};

GRANT UPDATE (
    delivery_status,
    delivery_attempt,
    next_attempt_at,
    delivery_lease_token,
    delivery_lease_expires_at,
    delivered_at,
    last_delivery_outcome,
    last_http_status_code
)
    ON TABLE watch_health_change_event
    TO ${runtimeRole};

REVOKE ALL PRIVILEGES
    ON TABLE flyway_schema_history
    FROM ${runtimeRole};

REVOKE INSERT, UPDATE, DELETE
    ON TABLE watch_health_change_event_backlog
    FROM ${runtimeRole};

GRANT SELECT
    ON TABLE watch_health_change_event_backlog
    TO ${runtimeRole};
