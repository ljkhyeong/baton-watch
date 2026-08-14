CREATE INDEX ix_watch_health_event_delivery_pending_changed
    ON public.watch_health_change_event (changed_at, event_id)
    WHERE delivery_status = 'PENDING';

CREATE TABLE public.watch_health_change_event_backlog (
    singleton                BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    pending_count            BIGINT NOT NULL CHECK (pending_count >= 0),
    oldest_changed_at        TIMESTAMPTZ,
    CONSTRAINT watch_health_event_backlog_shape CHECK (
        (pending_count = 0 AND oldest_changed_at IS NULL)
        OR (pending_count > 0 AND oldest_changed_at IS NOT NULL)
    )
);

INSERT INTO public.watch_health_change_event_backlog (
    singleton,
    pending_count,
    oldest_changed_at
)
SELECT
    TRUE,
    COUNT(*),
    MIN(changed_at)
FROM public.watch_health_change_event
WHERE delivery_status = 'PENDING';

CREATE FUNCTION public.maintain_watch_health_change_event_backlog()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF TG_OP = 'INSERT' AND NEW.delivery_status <> 'PENDING' THEN
        RETURN NULL;
    ELSIF TG_OP = 'DELETE' AND OLD.delivery_status <> 'PENDING' THEN
        RETURN NULL;
    ELSIF TG_OP = 'UPDATE' AND NOT (
            (OLD.delivery_status = 'PENDING' AND NEW.delivery_status <> 'PENDING')
            OR (OLD.delivery_status <> 'PENDING' AND NEW.delivery_status = 'PENDING')
            OR (
                OLD.delivery_status = 'PENDING'
                AND NEW.delivery_status = 'PENDING'
                AND OLD.changed_at IS DISTINCT FROM NEW.changed_at
            )
        ) THEN
        RETURN NULL;
    END IF;

    -- 잠금 대기 뒤 새 READ COMMITTED 스냅샷으로 실제 백로그를 다시 집계한다.
    PERFORM 1
    FROM public.watch_health_change_event_backlog
    WHERE singleton
    FOR UPDATE;

    IF TG_OP = 'INSERT' AND NEW.delivery_status = 'PENDING' THEN
        UPDATE public.watch_health_change_event_backlog
        SET pending_count = pending_count + 1,
            oldest_changed_at = CASE
                WHEN pending_count = 0 THEN NEW.changed_at
                ELSE LEAST(oldest_changed_at, NEW.changed_at)
            END
        WHERE singleton;
    ELSIF TG_OP = 'DELETE' AND OLD.delivery_status = 'PENDING' THEN
        UPDATE public.watch_health_change_event_backlog
        SET pending_count = pending_count - 1,
            oldest_changed_at = CASE
                WHEN pending_count = 1 THEN NULL
                WHEN oldest_changed_at = OLD.changed_at THEN (
                    SELECT MIN(changed_at)
                    FROM public.watch_health_change_event
                    WHERE delivery_status = 'PENDING'
                )
                ELSE oldest_changed_at
            END
        WHERE singleton;
    ELSIF TG_OP = 'UPDATE'
            AND OLD.delivery_status = 'PENDING'
            AND NEW.delivery_status <> 'PENDING' THEN
        UPDATE public.watch_health_change_event_backlog
        SET pending_count = pending_count - 1,
            oldest_changed_at = CASE
                WHEN pending_count = 1 THEN NULL
                WHEN oldest_changed_at = OLD.changed_at THEN (
                    SELECT MIN(changed_at)
                    FROM public.watch_health_change_event
                    WHERE delivery_status = 'PENDING'
                )
                ELSE oldest_changed_at
            END
        WHERE singleton;
    ELSIF TG_OP = 'UPDATE'
            AND OLD.delivery_status <> 'PENDING'
            AND NEW.delivery_status = 'PENDING' THEN
        UPDATE public.watch_health_change_event_backlog
        SET pending_count = pending_count + 1,
            oldest_changed_at = CASE
                WHEN pending_count = 0 THEN NEW.changed_at
                ELSE LEAST(oldest_changed_at, NEW.changed_at)
            END
        WHERE singleton;
    ELSIF TG_OP = 'UPDATE'
            AND OLD.delivery_status = 'PENDING'
            AND NEW.delivery_status = 'PENDING'
            AND OLD.changed_at IS DISTINCT FROM NEW.changed_at THEN
        UPDATE public.watch_health_change_event_backlog
        SET oldest_changed_at = (
            SELECT MIN(changed_at)
            FROM public.watch_health_change_event
            WHERE delivery_status = 'PENDING'
        )
        WHERE singleton;
    END IF;

    RETURN NULL;
END;
$$;

REVOKE EXECUTE
    ON FUNCTION public.maintain_watch_health_change_event_backlog()
    FROM PUBLIC;

CREATE TRIGGER trg_watch_health_change_event_backlog
AFTER INSERT OR UPDATE OR DELETE ON public.watch_health_change_event
FOR EACH ROW
EXECUTE FUNCTION public.maintain_watch_health_change_event_backlog();
