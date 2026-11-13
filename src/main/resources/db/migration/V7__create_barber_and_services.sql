CREATE TABLE barber (
    id BIGINT NOT NULL AUTO_INCREMENT,
    shop_id BIGINT NOT NULL,
    display_name VARCHAR(40) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY ix_barber_shop_enabled_id (shop_id, enabled, id),
    CONSTRAINT fk_barber_shop FOREIGN KEY (shop_id) REFERENCES shop (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_barber_name CHECK (CHAR_LENGTH(TRIM(display_name)) > 0),
    CONSTRAINT ck_barber_enabled CHECK (enabled IN (0, 1)),
    CONSTRAINT ck_barber_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE barber_service (
    id BIGINT NOT NULL AUTO_INCREMENT,
    barber_id BIGINT NOT NULL,
    service_id BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_barber_service (barber_id, service_id),
    KEY ix_barber_service_service (service_id),
    CONSTRAINT fk_barber_service_barber FOREIGN KEY (barber_id) REFERENCES barber (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_barber_service_service FOREIGN KEY (service_id) REFERENCES service_item (id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
