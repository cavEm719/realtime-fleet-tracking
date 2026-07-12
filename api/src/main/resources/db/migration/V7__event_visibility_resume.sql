-- Source report time is not the order in which stored events become visible.
ALTER TABLE geofence_event
    MODIFY created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    DROP INDEX events_by_resume,
    ADD INDEX events_by_resume (tenant_id, created_at, id);
