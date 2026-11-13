CREATE TABLE user_coupon (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    student_id BIGINT NOT NULL,
    campaign_id BIGINT NOT NULL,
    shop_id BIGINT NOT NULL,
    name_snapshot VARCHAR(80) NOT NULL,
    threshold_amount BIGINT NOT NULL,
    reduction_amount BIGINT NOT NULL,
    use_start_at DATETIME(3) NOT NULL,
    use_end_at DATETIME(3) NOT NULL,
    source_campaign_version BIGINT NOT NULL,
    claimed_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_user_coupon_campaign_student (campaign_id,student_id),
    KEY ix_user_coupon_student (student_id,claimed_at,id),
    CONSTRAINT fk_user_coupon_student FOREIGN KEY(student_id) REFERENCES account(id),
    CONSTRAINT fk_user_coupon_campaign FOREIGN KEY(campaign_id) REFERENCES coupon_campaign(id),
    CONSTRAINT fk_user_coupon_shop FOREIGN KEY(shop_id) REFERENCES shop(id),
    CONSTRAINT ck_user_coupon_amount CHECK(reduction_amount>=1 AND reduction_amount<threshold_amount AND threshold_amount<=5000000000),
    CONSTRAINT ck_user_coupon_time CHECK(use_start_at<=claimed_at AND claimed_at<use_end_at AND source_campaign_version>=1)
) ENGINE=InnoDB;

CREATE TABLE coupon_claim_request (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    student_id BIGINT NOT NULL,
    campaign_id BIGINT NOT NULL,
    request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_coupon_id BIGINT NOT NULL,
    completed_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_coupon_claim_student_key(student_id,request_key),
    UNIQUE KEY uk_coupon_claim_coupon(user_coupon_id),
    CONSTRAINT fk_coupon_claim_student FOREIGN KEY(student_id) REFERENCES account(id),
    CONSTRAINT fk_coupon_claim_campaign FOREIGN KEY(campaign_id) REFERENCES coupon_campaign(id),
    CONSTRAINT fk_coupon_claim_coupon FOREIGN KEY(user_coupon_id) REFERENCES user_coupon(id),
    CONSTRAINT ck_coupon_claim_key CHECK(request_key REGEXP '^[A-Za-z0-9_-]{1,64}$')
) ENGINE=InnoDB;
