-- MySQL 8.0.16+ / 8.4; executed once by Flyway, not by schema.sql.
-- Connection session must use UTC; these DATETIME(3) values represent UTC.
CREATE TABLE account (
    id BIGINT NOT NULL AUTO_INCREMENT,
    username VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    nickname VARCHAR(40) NOT NULL,
    role VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT uk_account_username UNIQUE (username),
    CONSTRAINT ck_account_username CHECK (REGEXP_LIKE(username, '^[a-z0-9_]{4,32}$', 'c')),
    CONSTRAINT ck_account_role CHECK (role IN ('STUDENT', 'MERCHANT', 'ADMIN')),
    CONSTRAINT ck_account_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_account_version CHECK (version >= 0),
    CONSTRAINT ck_account_nickname CHECK (CHAR_LENGTH(TRIM(nickname)) > 0),
    CONSTRAINT ck_account_password_hash CHECK (CHAR_LENGTH(password_hash) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE auth_session (
    id BIGINT NOT NULL AUTO_INCREMENT,
    account_id BIGINT NOT NULL,
    jti CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    revoked_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT uk_auth_session_jti UNIQUE (jti),
    KEY ix_auth_session_account_id (account_id),
    KEY ix_auth_session_expires_at (expires_at, id),
    CONSTRAINT fk_auth_session_account FOREIGN KEY (account_id)
        REFERENCES account (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_auth_session_jti CHECK (CHAR_LENGTH(TRIM(jti)) = 36),
    CONSTRAINT ck_auth_session_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_auth_session_revocation CHECK (revoked_at IS NULL OR revoked_at >= created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
