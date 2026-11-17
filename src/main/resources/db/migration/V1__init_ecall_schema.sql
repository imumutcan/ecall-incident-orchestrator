-- ============================================================================
-- Flyway Migration V1: Production PostgreSQL 15+ Schema & Partial Indexes
-- ============================================================================

CREATE TABLE ecall_incidents (
    id BIGSERIAL PRIMARY KEY,
    event_id VARCHAR(64) NOT NULL,
    vin VARCHAR(17) NOT NULL,
    crash_timestamp TIMESTAMP WITH TIME ZONE NOT NULL,
    impact_delta_v DOUBLE PRECISION NOT NULL,
    airbag_deployed BOOLEAN NOT NULL,
    rollover_detected BOOLEAN NOT NULL,
    latitude DOUBLE PRECISION NOT NULL,
    longitude DOUBLE PRECISION NOT NULL,
    heading_degrees INTEGER NOT NULL,
    passengers_count INTEGER NOT NULL,
    severity VARCHAR(20) NOT NULL,
    status VARCHAR(32) NOT NULL,
    policy_number VARCHAR(40),
    claim_reference VARCHAR(40),
    dispatch_reference VARCHAR(40),
    failure_reason VARCHAR(500),
    retry_count INTEGER NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_ecall_incidents_event_id UNIQUE (event_id)
);

CREATE INDEX idx_incidents_vin_crash_time
    ON ecall_incidents (vin, crash_timestamp DESC);

CREATE INDEX idx_incidents_status_severity
    ON ecall_incidents (status, severity);

CREATE TABLE outbox_events (
    id BIGSERIAL PRIMARY KEY,
    aggregate_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    retry_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMP WITH TIME ZONE
);

-- Production PostgreSQL Partial Index:
-- Indexes ONLY rows where status = 'PENDING', keeping index footprint < 1MB
-- even when outbox_events contains millions of historical PROCESSED rows.
CREATE INDEX idx_outbox_pending_partial
    ON outbox_events (next_attempt_at, created_at)
    WHERE status = 'PENDING';

CREATE INDEX idx_outbox_aggregate_id
    ON outbox_events (aggregate_id);
