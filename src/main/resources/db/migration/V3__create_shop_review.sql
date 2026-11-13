CREATE TABLE shop_review (
    id BIGINT NOT NULL AUTO_INCREMENT,
    shop_id BIGINT NOT NULL,
    reviewed_version BIGINT NOT NULL,
    decision VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(300) NULL,
    reviewed_by BIGINT NOT NULL,
    reviewed_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_shop_review_version UNIQUE (shop_id, reviewed_version),
    KEY ix_shop_review_actor (reviewed_by, id),
    CONSTRAINT fk_shop_review_shop FOREIGN KEY (shop_id) REFERENCES shop (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_shop_review_actor FOREIGN KEY (reviewed_by) REFERENCES account (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_shop_review_version CHECK (reviewed_version >= 0),
    CONSTRAINT ck_shop_review_decision CHECK (decision IN ('APPROVED', 'REJECTED')),
    CONSTRAINT ck_shop_review_reason CHECK ((decision = 'APPROVED' AND reason IS NULL)
        OR (decision = 'REJECTED' AND reason IS NOT NULL AND CHAR_LENGTH(TRIM(reason)) > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
