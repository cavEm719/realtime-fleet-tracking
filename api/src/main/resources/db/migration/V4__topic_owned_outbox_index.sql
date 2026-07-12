ALTER TABLE outbox
    DROP INDEX unpublished_rows,
    ADD INDEX unpublished_rows (topic, published_at, id);
