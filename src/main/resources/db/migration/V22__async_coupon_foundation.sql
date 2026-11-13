CREATE TABLE coupon_dispatch_gate (
 campaign_id BIGINT NOT NULL PRIMARY KEY,
 mode VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'SYNC',
 phase VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'OPEN',
 generation BIGINT NOT NULL DEFAULT 1,
 control_version BIGINT NOT NULL DEFAULT 0,
 rebuild_token CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
 lease_until DATETIME(3) NULL,
 redis_run_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 CONSTRAINT fk_coupon_gate_campaign FOREIGN KEY(campaign_id) REFERENCES coupon_campaign(id) ON DELETE CASCADE,
 CONSTRAINT ck_coupon_gate_state CHECK(mode IN ('SYNC','ASYNC') AND phase IN ('OPEN','PAUSED','REBUILDING') AND generation>=1 AND control_version>=0),
 CONSTRAINT ck_coupon_gate_rebuild CHECK(
   (phase='REBUILDING' AND mode='ASYNC' AND rebuild_token IS NOT NULL AND rebuild_token REGEXP '^[a-f0-9]{32}$' AND lease_until IS NOT NULL)
   OR (phase IN ('OPEN','PAUSED') AND rebuild_token IS NULL AND lease_until IS NULL))
) ENGINE=InnoDB;
INSERT INTO coupon_dispatch_gate(campaign_id) SELECT id FROM coupon_campaign;

ALTER TABLE coupon_claim_request
 MODIFY user_coupon_id BIGINT NULL,
 MODIFY completed_at DATETIME(3) NULL,
 ADD COLUMN status VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'SUCCEEDED',
 ADD COLUMN source_mode VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'SYNC',
 ADD COLUMN created_at DATETIME(3) NULL,
 ADD COLUMN accepted_at DATETIME(3) NULL,
 ADD COLUMN deadline DATETIME(3) NULL,
 ADD COLUMN reservation_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD COLUMN accepted_generation BIGINT NULL,
 ADD COLUMN source_campaign_version BIGINT NULL,
 ADD COLUMN failure_code VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD COLUMN redis_settled_generation BIGINT NULL;
UPDATE coupon_claim_request r JOIN user_coupon c ON c.id=r.user_coupon_id
 SET r.created_at=r.completed_at,r.source_campaign_version=c.source_campaign_version;
ALTER TABLE coupon_claim_request
 MODIFY created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 ADD COLUMN active_campaign_id BIGINT GENERATED ALWAYS AS (CASE WHEN status IN ('PENDING','SUCCEEDED') THEN campaign_id ELSE NULL END) STORED,
 ADD UNIQUE KEY uk_coupon_claim_active(student_id,active_campaign_id),
 ADD UNIQUE KEY uk_coupon_claim_reservation(reservation_id),
 ADD KEY ix_coupon_claim_due(status,deadline,id),
 ADD KEY ix_coupon_claim_campaign_status(campaign_id,status,id),
 ADD CONSTRAINT ck_coupon_claim_result CHECK(
   (status='PENDING' AND user_coupon_id IS NULL AND completed_at IS NULL AND failure_code IS NULL)
   OR (status='SUCCEEDED' AND user_coupon_id IS NOT NULL AND completed_at IS NOT NULL AND failure_code IS NULL)
   OR (status='FAILED' AND user_coupon_id IS NULL AND completed_at IS NOT NULL AND failure_code IS NOT NULL)),
 ADD CONSTRAINT ck_coupon_claim_origin CHECK(
   (source_mode='SYNC' AND status='SUCCEEDED' AND reservation_id IS NULL AND accepted_generation IS NULL AND accepted_at IS NULL AND deadline IS NULL AND redis_settled_generation IS NULL)
   OR (source_mode='ASYNC' AND reservation_id IS NOT NULL AND reservation_id REGEXP '^[a-f0-9]{32}$'
       AND accepted_generation IS NOT NULL AND accepted_generation>=1
       AND ((accepted_at IS NOT NULL AND deadline IS NOT NULL AND source_campaign_version IS NOT NULL AND source_campaign_version>=1 AND accepted_at>=created_at AND deadline>accepted_at AND deadline<=TIMESTAMPADD(MINUTE,5,accepted_at))
            OR (status='FAILED' AND accepted_at IS NULL AND deadline IS NULL)))),
 ADD CONSTRAINT ck_coupon_claim_time CHECK(
   (completed_at IS NULL OR (completed_at>=created_at AND (accepted_at IS NULL OR completed_at>=accepted_at)))
   AND (redis_settled_generation IS NULL OR (redis_settled_generation>=1 AND status IN ('SUCCEEDED','FAILED'))));

ALTER TABLE message_outbox DROP CHECK ck_outbox_event,
 ADD CONSTRAINT ck_outbox_event CHECK(schema_version=1 AND event_seq>=0 AND aggregate_id>0 AND recipient_id>0
  AND ((event_type='BOOKING_ACCEPTED' AND aggregate_type='BOOKING')
    OR (event_type='PRINT_READY_FOR_PICKUP' AND aggregate_type='PRINT')
    OR (event_type='COUPON_ISSUE_REQUESTED' AND aggregate_type='COUPON_CLAIM' AND event_seq=0)));
