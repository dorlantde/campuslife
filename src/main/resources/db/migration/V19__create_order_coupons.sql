ALTER TABLE user_coupon
    ADD COLUMN state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'AVAILABLE',
    ADD COLUMN current_order_id BIGINT NULL,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD UNIQUE KEY uk_user_coupon_current_order(current_order_id),
    ADD CONSTRAINT fk_user_coupon_order FOREIGN KEY(current_order_id) REFERENCES service_order(id),
    ADD CONSTRAINT ck_user_coupon_state CHECK((state IN ('AVAILABLE','EXPIRED') AND current_order_id IS NULL) OR (state IN ('LOCKED','USED') AND current_order_id IS NOT NULL)),
    ADD CONSTRAINT ck_user_coupon_version CHECK(version>=0);

CREATE TABLE order_coupon (
    order_id BIGINT NOT NULL PRIMARY KEY,
    user_coupon_id BIGINT NOT NULL,
    campaign_id BIGINT NOT NULL,
    name_snapshot VARCHAR(80) NOT NULL,
    threshold_amount BIGINT NOT NULL,
    reduction_amount BIGINT NOT NULL,
    use_start_at DATETIME(3) NOT NULL,
    use_end_at DATETIME(3) NOT NULL,
    source_campaign_version BIGINT NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    locked_at DATETIME(3) NOT NULL,
    used_at DATETIME(3) NULL,
    released_at DATETIME(3) NULL,
    KEY ix_order_coupon_user(user_coupon_id,order_id),
    CONSTRAINT fk_order_coupon_order FOREIGN KEY(order_id) REFERENCES service_order(id),
    CONSTRAINT fk_order_coupon_user FOREIGN KEY(user_coupon_id) REFERENCES user_coupon(id),
    CONSTRAINT fk_order_coupon_campaign FOREIGN KEY(campaign_id) REFERENCES coupon_campaign(id),
    CONSTRAINT ck_order_coupon_amount CHECK(reduction_amount>=1 AND reduction_amount<threshold_amount AND threshold_amount<=5000000000),
    CONSTRAINT ck_order_coupon_time CHECK(use_start_at<=locked_at AND locked_at<use_end_at AND source_campaign_version>=1),
    CONSTRAINT ck_order_coupon_state CHECK((state='LOCKED' AND used_at IS NULL AND released_at IS NULL)
        OR (state='USED' AND used_at IS NOT NULL AND used_at>=locked_at AND released_at IS NULL)
        OR (state='RELEASED' AND released_at IS NOT NULL AND released_at>=locked_at AND used_at IS NULL))
) ENGINE=InnoDB;
