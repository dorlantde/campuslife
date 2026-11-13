CREATE TABLE shop_business_hour (
    id BIGINT NOT NULL AUTO_INCREMENT,
    shop_id BIGINT NOT NULL,
    weekday TINYINT NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_shop_business_hour_start UNIQUE (shop_id, weekday, start_time),
    CONSTRAINT fk_shop_business_hour_shop FOREIGN KEY (shop_id) REFERENCES shop (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_shop_business_hour_weekday CHECK (weekday BETWEEN 1 AND 7),
    CONSTRAINT ck_shop_business_hour_interval CHECK (
        start_time >= '00:00:00' AND end_time <= '23:59:00' AND start_time < end_time
        AND SECOND(start_time) = 0 AND SECOND(end_time) = 0
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
