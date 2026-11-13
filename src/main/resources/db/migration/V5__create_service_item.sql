CREATE TABLE service_item (
    id BIGINT NOT NULL AUTO_INCREMENT,
    shop_id BIGINT NOT NULL,
    service_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name VARCHAR(80) NOT NULL,
    description VARCHAR(500) NOT NULL DEFAULT '',
    price_amount BIGINT NOT NULL,
    duration_minutes INT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY ix_service_shop_enabled_id (shop_id, enabled, id),
    CONSTRAINT fk_service_shop FOREIGN KEY (shop_id) REFERENCES shop (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_service_type CHECK (service_type IN ('HAIRCUT', 'PRINT')),
    CONSTRAINT ck_service_name CHECK (CHAR_LENGTH(TRIM(name)) > 0),
    CONSTRAINT ck_service_price CHECK (price_amount BETWEEN 1 AND 100000),
    CONSTRAINT ck_service_duration CHECK (
        (service_type = 'HAIRCUT' AND duration_minutes IS NOT NULL AND duration_minutes BETWEEN 15 AND 240 AND MOD(duration_minutes, 15) = 0)
        OR (service_type = 'PRINT' AND duration_minutes IS NULL)
    ),
    CONSTRAINT ck_service_enabled CHECK (enabled IN (0, 1)),
    CONSTRAINT ck_service_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
