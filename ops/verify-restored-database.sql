DO $watch$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM flyway_schema_history WHERE success)
        OR EXISTS (SELECT 1 FROM flyway_schema_history WHERE NOT success) THEN
        RAISE EXCEPTION '복원된 마이그레이션 이력을 확인할 수 없습니다';
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM watch_health_change_event_backlog
        WHERE singleton
          AND pending_count = (SELECT count(*) FROM watch_health_change_event WHERE delivery_status = 'PENDING')
          AND oldest_changed_at IS NOT DISTINCT FROM (
              SELECT min(changed_at) FROM watch_health_change_event WHERE delivery_status = 'PENDING'
          )
    ) THEN
        RAISE EXCEPTION '복원된 이벤트 백로그 요약이 원본 행과 다릅니다';
    END IF;
END
$watch$;

-- 복원 내용의 집계만 출력한다. URL·리소스 참조·이벤트 ID는 출력하지 않는다.
SELECT '복원 확인: 모니터=' || (SELECT count(*) FROM watch_monitor)
    || ' 시도=' || (SELECT count(*) FROM watch_attempt)
    || ' 결과=' || (SELECT count(*) FROM watch_result)
    || ' 대기이벤트=' || (SELECT count(*) FROM watch_health_change_event WHERE delivery_status = 'PENDING')
    || ' 완료이벤트=' || (SELECT count(*) FROM watch_health_change_event WHERE delivery_status = 'DELIVERED')
    || ' 전달시도합계=' || (SELECT coalesce(sum(delivery_attempt), 0) FROM watch_health_change_event);
