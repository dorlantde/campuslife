ALTER TABLE order_review
    ADD COLUMN moderated_by BIGINT NULL,
    ADD COLUMN moderation_reason VARCHAR(300) NULL,
    ADD COLUMN moderated_at DATETIME(3) NULL,
    ADD CONSTRAINT fk_order_review_moderator FOREIGN KEY (moderated_by) REFERENCES account(id),
    ADD CONSTRAINT ck_order_review_moderation CHECK (
        (moderated_by IS NULL AND moderation_reason IS NULL AND moderated_at IS NULL)
        OR (moderated_by IS NOT NULL AND moderation_reason IS NOT NULL AND moderated_at IS NOT NULL
            AND visibility = 'HIDDEN' AND CHAR_LENGTH(moderation_reason) BETWEEN 1 AND 300
            AND moderated_at >= created_at)
    );
