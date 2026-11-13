CREATE TABLE payment_record (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    payment_no VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    amount BIGINT NOT NULL,
    channel VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    paid_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_payment_order (order_id),
    UNIQUE KEY uk_payment_no (payment_no),
    CONSTRAINT fk_payment_order FOREIGN KEY (order_id) REFERENCES service_order(id),
    CONSTRAINT ck_payment_amount CHECK (amount > 0),
    CONSTRAINT ck_payment_channel CHECK (channel = 'MOCK'),
    CONSTRAINT ck_payment_status CHECK (status = 'SUCCEEDED')
) ENGINE=InnoDB;

CREATE TABLE refund_record (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    payment_id BIGINT NOT NULL,
    refund_no VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    amount BIGINT NOT NULL,
    reason VARCHAR(32) NOT NULL,
    reason_note VARCHAR(300) NULL,
    status VARCHAR(16) NOT NULL,
    refunded_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_refund_order (order_id),
    UNIQUE KEY uk_refund_payment (payment_id),
    UNIQUE KEY uk_refund_no (refund_no),
    CONSTRAINT fk_refund_order FOREIGN KEY (order_id) REFERENCES service_order(id),
    CONSTRAINT fk_refund_payment FOREIGN KEY (payment_id) REFERENCES payment_record(id),
    CONSTRAINT ck_refund_amount CHECK (amount > 0),
    CONSTRAINT ck_refund_status CHECK (status = 'SUCCEEDED')
) ENGINE=InnoDB;
