CREATE TABLE message_outbox (
    message_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    event_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    schema_version INT NOT NULL,
    aggregate_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    aggregate_id BIGINT NOT NULL,
    event_seq BIGINT NOT NULL,
    recipient_id BIGINT NOT NULL,
    payload LONGTEXT NOT NULL,
    payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    occurred_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(3) NULL,
    last_attempt_at DATETIME(3) NULL,
    lease_token CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until DATETIME(3) NULL,
    published_at DATETIME(3) NULL,
    last_error_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    UNIQUE KEY uk_outbox_business(event_type,aggregate_id,event_seq,recipient_id),
    KEY ix_outbox_ready(status,next_attempt_at,created_at,message_id),
    KEY ix_outbox_lease(status,lease_until,created_at,message_id),
    CONSTRAINT ck_outbox_event CHECK(schema_version=1 AND event_seq>=0 AND aggregate_id>0 AND recipient_id>0
       AND ((event_type='BOOKING_ACCEPTED' AND aggregate_type='BOOKING') OR (event_type='PRINT_READY_FOR_PICKUP' AND aggregate_type='PRINT'))),
    CONSTRAINT ck_outbox_payload CHECK(JSON_VALID(payload) AND OCTET_LENGTH(payload)<=16384 AND payload_hash REGEXP '^[a-f0-9]{64}$'),
    CONSTRAINT ck_outbox_identity CHECK(message_id REGEXP '^[a-f0-9]{32}$'),
    CONSTRAINT ck_outbox_attempts CHECK(attempts BETWEEN 0 AND 20 AND occurred_at<=created_at
        AND ((attempts=0 AND last_attempt_at IS NULL) OR (attempts>=1 AND last_attempt_at>=created_at AND last_attempt_at IS NOT NULL))),
    CONSTRAINT ck_outbox_state CHECK(
        (status='READY' AND next_attempt_at IS NOT NULL AND next_attempt_at>=created_at AND lease_token IS NULL AND lease_until IS NULL AND published_at IS NULL AND attempts<20)
        OR (status='PUBLISHING' AND attempts>=1 AND last_attempt_at IS NOT NULL AND next_attempt_at IS NULL AND lease_token IS NOT NULL AND lease_until IS NOT NULL AND lease_until>last_attempt_at AND published_at IS NULL)
        OR (status='SENT' AND attempts>=1 AND last_attempt_at IS NOT NULL AND next_attempt_at IS NULL AND lease_token IS NULL AND lease_until IS NULL AND published_at IS NOT NULL AND published_at>=last_attempt_at)
        OR (status='DEAD' AND attempts=20 AND last_attempt_at IS NOT NULL AND next_attempt_at IS NULL AND lease_token IS NULL AND lease_until IS NULL AND published_at IS NULL))
) ENGINE=InnoDB;
