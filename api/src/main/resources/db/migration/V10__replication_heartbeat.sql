-- The API writes one row on the primary every second; reading it on the replica shows how
-- current the replicated data is.
CREATE TABLE replication_heartbeat (
    id TINYINT PRIMARY KEY,
    beat_at TIMESTAMP(6) NOT NULL
);
INSERT INTO replication_heartbeat (id, beat_at) VALUES (1, CURRENT_TIMESTAMP(6));
