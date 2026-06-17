CREATE TABLE tenant (
    id BIGINT PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    ingest_rate_per_sec INT NOT NULL CHECK (ingest_rate_per_sec > 0),
    llm_rate_per_min INT NOT NULL CHECK (llm_rate_per_min > 0)
);

CREATE TABLE asset (
    id BIGINT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    mmsi BIGINT NOT NULL UNIQUE,
    name VARCHAR(128) NOT NULL,
    UNIQUE KEY asset_tenant (id, tenant_id),
    INDEX asset_by_tenant (tenant_id),
    FOREIGN KEY (tenant_id) REFERENCES tenant (id)
);

CREATE TABLE fence (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    center_lat DOUBLE NOT NULL,
    center_lon DOUBLE NOT NULL,
    enter_radius_m DOUBLE NOT NULL,
    exit_radius_m DOUBLE NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY fence_tenant (id, tenant_id),
    INDEX fence_by_tenant (tenant_id, deleted, version),
    CHECK (center_lat BETWEEN -85.05112878 AND 85.05112878),
    CHECK (center_lon BETWEEN -180 AND 180),
    CHECK (enter_radius_m > 0 AND exit_radius_m > enter_radius_m),
    CHECK (version > 0),
    FOREIGN KEY (tenant_id) REFERENCES tenant (id)
);

CREATE TABLE asset_fence_state (
    asset_id BIGINT NOT NULL,
    fence_id BIGINT NOT NULL,
    tenant_id BIGINT NOT NULL,
    inside BOOLEAN NOT NULL,
    last_transition_position_ts BIGINT NOT NULL,
    fence_version BIGINT NOT NULL,
    PRIMARY KEY (asset_id, fence_id),
    INDEX state_by_tenant (tenant_id, asset_id),
    FOREIGN KEY (asset_id, tenant_id) REFERENCES asset (id, tenant_id),
    FOREIGN KEY (fence_id, tenant_id) REFERENCES fence (id, tenant_id)
);

CREATE TABLE geofence_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    tenant_id BIGINT NOT NULL,
    asset_id BIGINT NOT NULL,
    fence_id BIGINT NOT NULL,
    type VARCHAR(5) NOT NULL CHECK (type IN ('ENTER', 'EXIT')),
    position_ts BIGINT NOT NULL,
    fence_version BIGINT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX events_by_asset (tenant_id, asset_id, position_ts),
    INDEX events_by_fence (tenant_id, fence_id, position_ts),
    FOREIGN KEY (asset_id, tenant_id) REFERENCES asset (id, tenant_id),
    FOREIGN KEY (fence_id, tenant_id) REFERENCES fence (id, tenant_id)
);

CREATE TABLE outbox (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    topic VARCHAR(64) NOT NULL,
    msg_key VARCHAR(128) NOT NULL,
    payload JSON NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    published_at TIMESTAMP(3) NULL,
    INDEX unpublished_rows (published_at, id),
    FOREIGN KEY (tenant_id) REFERENCES tenant (id)
);

CREATE TABLE alert_rule (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    fence_id BIGINT NOT NULL,
    asset_filter JSON NULL,
    status VARCHAR(6) NOT NULL CHECK (status IN ('DRAFT', 'ACTIVE')),
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX rules_by_tenant (tenant_id, status),
    FOREIGN KEY (fence_id, tenant_id) REFERENCES fence (id, tenant_id)
);
