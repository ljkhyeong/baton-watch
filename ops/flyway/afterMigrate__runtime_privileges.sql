-- 테이블 권한 회수는 같은 권한의 열 권한까지 함께 회수한다. 아래에서 필요한 권한만 다시 부여한다.
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
