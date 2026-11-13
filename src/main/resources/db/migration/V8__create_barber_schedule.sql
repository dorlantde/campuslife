CREATE TABLE barber_day_guard (
    id BIGINT NOT NULL AUTO_INCREMENT,
    barber_id BIGINT NOT NULL,
    service_date DATE NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_barber_day (barber_id, service_date),
    CONSTRAINT fk_guard_barber FOREIGN KEY (barber_id) REFERENCES barber(id)
) ENGINE=InnoDB;

CREATE TABLE barber_schedule (
    id BIGINT NOT NULL AUTO_INCREMENT,
    barber_id BIGINT NOT NULL,
    service_date DATE NOT NULL,
    start_at DATETIME(3) NOT NULL COMMENT 'UTC; service_date uses Asia/Shanghai',
    end_at DATETIME(3) NOT NULL COMMENT 'UTC; exclusive',
    PRIMARY KEY (id),
    UNIQUE KEY uk_barber_schedule_start (barber_id, service_date, start_at),
    CONSTRAINT fk_schedule_guard FOREIGN KEY (barber_id, service_date)
        REFERENCES barber_day_guard(barber_id, service_date),
    CONSTRAINT ck_schedule_time CHECK (start_at < end_at)
) ENGINE=InnoDB;
