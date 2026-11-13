CREATE TABLE coupon_campaign (
    id BIGINT NOT NULL AUTO_INCREMENT,
    shop_id BIGINT NOT NULL,
    name VARCHAR(80) NOT NULL,
    threshold_amount BIGINT NOT NULL,
    reduction_amount BIGINT NOT NULL,
    total_quantity INT NOT NULL,
    issued_count INT NOT NULL DEFAULT 0,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'DRAFT',
    claim_start_at DATETIME(3) NOT NULL,
    claim_end_at DATETIME(3) NOT NULL,
    use_start_at DATETIME(3) NOT NULL,
    use_end_at DATETIME(3) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    published_at DATETIME(3) NULL,
    published_by BIGINT NULL,
    publish_expected_version BIGINT NULL,
    stopped_at DATETIME(3) NULL,
    stopped_by BIGINT NULL,
    stop_expected_version BIGINT NULL,
    PRIMARY KEY (id),
    KEY ix_coupon_campaign_shop (shop_id, created_at, id),
    CONSTRAINT fk_coupon_campaign_shop FOREIGN KEY (shop_id) REFERENCES shop(id),
    CONSTRAINT fk_coupon_campaign_publisher FOREIGN KEY (published_by) REFERENCES account(id),
    CONSTRAINT fk_coupon_campaign_stopper FOREIGN KEY (stopped_by) REFERENCES account(id),
    CONSTRAINT ck_coupon_campaign_name CHECK (CHAR_LENGTH(name) BETWEEN 1 AND 80),
    CONSTRAINT ck_coupon_campaign_amount CHECK (reduction_amount >= 1 AND reduction_amount < threshold_amount AND threshold_amount <= 5000000000),
    CONSTRAINT ck_coupon_campaign_quantity CHECK (total_quantity BETWEEN 1 AND 100000 AND issued_count BETWEEN 0 AND total_quantity),
    CONSTRAINT ck_coupon_campaign_window CHECK (use_start_at <= claim_start_at AND claim_start_at < claim_end_at AND claim_end_at <= use_end_at),
    CONSTRAINT ck_coupon_campaign_version CHECK (version >= 0 AND updated_at >= created_at),
    CONSTRAINT ck_coupon_campaign_state CHECK (
        (status = 'DRAFT' AND issued_count = 0 AND published_at IS NULL AND published_by IS NULL AND publish_expected_version IS NULL
            AND stopped_at IS NULL AND stopped_by IS NULL AND stop_expected_version IS NULL)
        OR (status = 'PUBLISHED' AND published_at IS NOT NULL AND published_by IS NOT NULL AND publish_expected_version IS NOT NULL
            AND publish_expected_version >= 0 AND publish_expected_version < version AND published_at >= created_at AND published_at <= updated_at
            AND published_at < claim_end_at AND stopped_at IS NULL AND stopped_by IS NULL AND stop_expected_version IS NULL)
        OR (status = 'STOPPED' AND published_at IS NOT NULL AND published_by IS NOT NULL AND publish_expected_version IS NOT NULL
            AND publish_expected_version >= 0 AND publish_expected_version < stop_expected_version
            AND stopped_at IS NOT NULL AND stopped_by IS NOT NULL AND stop_expected_version IS NOT NULL
            AND stop_expected_version < version AND published_at >= created_at AND published_at < claim_end_at
            AND stopped_at >= published_at AND stopped_at <= updated_at)
    )
) ENGINE=InnoDB;
