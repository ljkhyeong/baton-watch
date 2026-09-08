-- BATON MySQL에서 자료별 마지막 불변 아웃박스만 JSONL로 내보낸다.
-- mysql --batch --raw --skip-column-names로 실행하고 결과 파일은 0600으로 보관한다.
START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY;
SELECT JSON_OBJECT(
    'resourceReference', snapshot.resource_reference,
    'sourceRevision', snapshot.id,
    'monitoringState', snapshot.monitoring_state,
    'targetUrl', snapshot.target_url
)
FROM watch_monitor_outbox snapshot
WHERE NOT EXISTS (
    SELECT 1 FROM watch_monitor_outbox newer
    WHERE newer.resource_id = snapshot.resource_id AND newer.id > snapshot.id
)
ORDER BY snapshot.resource_reference;
COMMIT;
