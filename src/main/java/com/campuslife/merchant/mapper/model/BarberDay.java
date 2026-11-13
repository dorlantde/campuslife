package com.campuslife.merchant.mapper.model;

import java.time.LocalDate;

public record BarberDay(long barberId, LocalDate serviceDate) {}
