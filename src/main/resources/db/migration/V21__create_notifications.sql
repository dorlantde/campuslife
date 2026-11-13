CREATE TABLE message_consumption (
 consumer_name VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 message_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 processed_at DATETIME(3) NOT NULL,
 PRIMARY KEY(consumer_name,message_id)
) ENGINE=InnoDB;
CREATE TABLE notification (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 source_message_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 recipient_id BIGINT NOT NULL,
 type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 order_id BIGINT NOT NULL,
 order_no VARCHAR(40) NOT NULL,
 shop_name VARCHAR(100) NOT NULL,
 occurred_at DATETIME(3) NOT NULL,
 created_at DATETIME(3) NOT NULL,
 read_at DATETIME(3) NULL,
 UNIQUE KEY uk_notification_source(source_message_id,recipient_id),
 KEY ix_notification_owner(recipient_id,created_at,id),
 CONSTRAINT ck_notification_type CHECK(type IN ('BOOKING_ACCEPTED','PRINT_READY_FOR_PICKUP')),
 CONSTRAINT ck_notification_time CHECK(read_at IS NULL OR read_at>=created_at)
) ENGINE=InnoDB;
ALTER TABLE message_outbox ADD COLUMN replay_count INT NOT NULL DEFAULT 0,
 ADD COLUMN replay_reason VARCHAR(300) NULL, ADD COLUMN replayed_at DATETIME(3) NULL,
 ADD CONSTRAINT ck_outbox_replay CHECK(replay_count>=0 AND ((replay_count=0 AND replay_reason IS NULL AND replayed_at IS NULL) OR (replay_count>0 AND replay_reason IS NOT NULL AND replayed_at IS NOT NULL)));
