DROP INDEX public.ix_watch_health_event_delivery_due;

CREATE INDEX ix_watch_health_event_delivery_due
    ON public.watch_health_change_event (next_attempt_at, changed_at, event_id)
    INCLUDE (delivery_lease_expires_at)
    WHERE delivery_status = 'PENDING';

CREATE INDEX ix_watch_monitor_lease_attempt
    ON public.watch_monitor (lease_attempt_id)
    WHERE lease_attempt_id IS NOT NULL;
