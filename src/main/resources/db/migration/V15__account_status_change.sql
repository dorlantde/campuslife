CREATE TABLE account_status_change (
    id BIGINT NOT NULL AUTO_INCREMENT,
    actor_account_id BIGINT NOT NULL,
    target_account_id BIGINT NOT NULL,
    from_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    to_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    from_version BIGINT NOT NULL,
    to_version BIGINT NOT NULL,
    changed_at DATETIME(3) NOT NULL COMMENT 'UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_account_status_change_version (target_account_id, to_version),
    CONSTRAINT fk_account_status_change_actor FOREIGN KEY (actor_account_id) REFERENCES account(id),
    CONSTRAINT fk_account_status_change_target FOREIGN KEY (target_account_id) REFERENCES account(id),
    CONSTRAINT ck_account_status_change_actor CHECK (actor_account_id <> target_account_id),
    CONSTRAINT ck_account_status_change_states CHECK (
        from_status IN ('ACTIVE','DISABLED') AND to_status IN ('ACTIVE','DISABLED') AND from_status <> to_status),
    CONSTRAINT ck_account_status_change_versions CHECK (
        from_version >= 0 AND to_version >= 0 AND to_version - from_version = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
