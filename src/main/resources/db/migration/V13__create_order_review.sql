CREATE TABLE order_review (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    student_id BIGINT NOT NULL,
    shop_id BIGINT NOT NULL,
    rating TINYINT NOT NULL,
    content VARCHAR(1000) NOT NULL,
    visibility VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_review_order (order_id),
    KEY ix_order_review_shop (shop_id, visibility, created_at, id),
    CONSTRAINT fk_order_review_order FOREIGN KEY (order_id) REFERENCES service_order(id),
    CONSTRAINT fk_order_review_student FOREIGN KEY (student_id) REFERENCES account(id),
    CONSTRAINT fk_order_review_shop FOREIGN KEY (shop_id) REFERENCES shop(id),
    CONSTRAINT ck_order_review_rating CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT ck_order_review_content CHECK (CHAR_LENGTH(content) BETWEEN 1 AND 1000),
    CONSTRAINT ck_order_review_visibility CHECK (visibility IN ('VISIBLE','HIDDEN'))
) ENGINE=InnoDB;
