-- Base dictionaries are application data, not demo accounts.
CREATE TABLE shop_category (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name VARCHAR(40) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (id),
    CONSTRAINT uk_shop_category_code UNIQUE (code),
    CONSTRAINT ck_shop_category_name CHECK (CHAR_LENGTH(TRIM(name)) > 0),
    CONSTRAINT ck_shop_category_enabled CHECK (enabled IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO shop_category (code, name, sort_order) VALUES ('HAIRCUT', '理发', 10), ('PRINT', '打印', 20);

CREATE TABLE shop (
    id BIGINT NOT NULL AUTO_INCREMENT,
    category_id BIGINT NOT NULL,
    name VARCHAR(80) NOT NULL,
    campus_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    location_text VARCHAR(200) NOT NULL,
    description VARCHAR(1000) NOT NULL DEFAULT '',
    approval_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
    approval_reason VARCHAR(300) NULL,
    reviewed_by BIGINT NULL,
    reviewed_at DATETIME(3) NULL,
    business_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY ix_shop_approval_category_id (approval_status, category_id, id),
    CONSTRAINT fk_shop_category FOREIGN KEY (category_id) REFERENCES shop_category (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_shop_reviewer FOREIGN KEY (reviewed_by) REFERENCES account (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_shop_name CHECK (CHAR_LENGTH(TRIM(name)) > 0),
    CONSTRAINT ck_shop_location CHECK (CHAR_LENGTH(TRIM(location_text)) > 0),
    CONSTRAINT ck_shop_campus CHECK (campus_code = 'XINGHAI_MAIN'),
    CONSTRAINT ck_shop_approval CHECK (approval_status IN ('PENDING', 'APPROVED', 'REJECTED', 'SUSPENDED')),
    CONSTRAINT ck_shop_business CHECK (business_enabled IN (0, 1)),
    CONSTRAINT ck_shop_version CHECK (version >= 0),
    CONSTRAINT ck_shop_review_pair CHECK ((reviewed_by IS NULL) = (reviewed_at IS NULL)),
    CONSTRAINT ck_shop_pending CHECK (approval_status <> 'PENDING' OR (reviewed_by IS NULL AND approval_reason IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE shop_member (
    id BIGINT NOT NULL AUTO_INCREMENT,
    shop_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    member_role VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'OWNER',
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT uk_shop_member_account UNIQUE (account_id),
    CONSTRAINT uk_shop_member_owner UNIQUE (shop_id),
    CONSTRAINT fk_shop_member_shop FOREIGN KEY (shop_id) REFERENCES shop (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_shop_member_account FOREIGN KEY (account_id) REFERENCES account (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_shop_member_role CHECK (member_role = 'OWNER'),
    CONSTRAINT ck_shop_member_status CHECK (status IN ('PENDING', 'ACTIVE', 'DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
