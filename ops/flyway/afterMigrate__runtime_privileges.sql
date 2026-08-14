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

GRANT SELECT, INSERT, UPDATE, DELETE
    ON ALL TABLES IN SCHEMA public
    TO ${runtimeRole};

GRANT USAGE, SELECT, UPDATE
    ON ALL SEQUENCES IN SCHEMA public
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
