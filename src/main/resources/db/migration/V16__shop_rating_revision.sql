CREATE TABLE shop_rating_revision (
    shop_id BIGINT NOT NULL,
    revision BIGINT NOT NULL,
    PRIMARY KEY (shop_id),
    CONSTRAINT fk_shop_rating_revision_shop FOREIGN KEY (shop_id) REFERENCES shop(id) ON DELETE CASCADE,
    CONSTRAINT ck_shop_rating_revision_positive CHECK (revision > 0)
) ENGINE=InnoDB;

-- Existing review facts are unchanged; version 0 is reserved for shops without a row.
INSERT INTO shop_rating_revision (shop_id, revision)
SELECT DISTINCT shop_id, 1 FROM order_review;
