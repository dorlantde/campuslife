package com.campuslife.merchant.service;

import com.campuslife.common.exception.BusinessException;
import com.campuslife.merchant.mapper.BarberScheduleMapper;
import com.campuslife.merchant.vo.BusinessHourView;
import com.campuslife.merchant.vo.ScheduleInterval;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
@Profile("mysql")
public class ScheduleHoursProtection {
    private final BarberScheduleMapper schedules;
    private final Clock clock;
    private final com.campuslife.order.service.BookingConfigurationProtection bookingProtection;
    public ScheduleHoursProtection(BarberScheduleMapper schedules, Clock clock, com.campuslife.order.service.BookingConfigurationProtection bookingProtection) { this.schedules = schedules; this.clock = clock; this.bookingProtection = bookingProtection; }

    // Caller holds shop X in the same RC write transaction, including while enumerating dates.
    public void check(long shopId, List<BusinessHourView> target) {
        var today = LocalDate.ofInstant(clock.instant(), SchedulePolicy.CAMPUS_ZONE);
        for (var day : schedules.futureDays(shopId, today)) {
            schedules.lockGuard(day.barberId(), day.serviceDate());
            bookingProtection.hours(day.barberId(), day.serviceDate(), target);
            for (var row : schedules.rows(day.barberId(), day.serviceDate())) {
                var interval = new ScheduleInterval(SchedulePolicy.localTime(row.startAt()), SchedulePolicy.localTime(row.endAt()));
                if (!SchedulePolicy.covered(day.serviceDate(), interval, target)) {
                    throw new BusinessException(HttpStatus.CONFLICT, "SCHEDULE_CONFLICT", "新营业时间无法覆盖今天及之后的已有排班，请先调整或清空对应排班");
                }
            }
        }
    }
}
