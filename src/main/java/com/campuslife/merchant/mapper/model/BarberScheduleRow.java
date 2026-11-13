package com.campuslife.merchant.mapper.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record BarberScheduleRow(long barberId, LocalDate serviceDate, LocalDateTime startAt, LocalDateTime endAt) {}
