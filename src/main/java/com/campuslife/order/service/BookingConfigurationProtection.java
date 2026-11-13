package com.campuslife.order.service;

import com.campuslife.common.exception.BusinessException;
import com.campuslife.merchant.mapper.BarberScheduleMapper;
import com.campuslife.merchant.vo.*;
import com.campuslife.order.mapper.BookingMapper;
import java.time.*;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
@Profile("mysql")
public class BookingConfigurationProtection {
    private final BookingMapper bookings;private final BarberScheduleMapper schedules;private final Clock clock;
    public BookingConfigurationProtection(BookingMapper bookings,BarberScheduleMapper schedules,Clock clock) { this.bookings=bookings;this.schedules=schedules;this.clock=clock; }
    // Caller holds shop X. Enumerate and lock dates BEFORE service/barber/relationship write locks.
    public void lockBarberDays(long shop,long barber) {
        var today=clock.instant().atZone(BookingOrderPolicy.ZONE).toLocalDate();
        for (var day:schedules.futureDays(shop,today)) { if (day.barberId()==barber) { schedules.lockGuard(barber,day.serviceDate()); } }
    }
    public void barber(long shop,long barber,boolean enabled,List<Long> targetServices) {
        var now=clock.instant();var today=now.atZone(BookingOrderPolicy.ZONE).toLocalDate();
        for (var day:schedules.futureDays(shop,today)) {
            if (day.barberId()!=barber) { continue; }
            for (var row:bookings.occupied(barber,day.serviceDate())) {
                if (!BookingOrderPolicy.instant(row.getEndAt()).isAfter(now) || "CONSUMED".equals(row.getOccupancyStatus())) { continue; }
                if (!enabled || (targetServices!=null && !targetServices.contains(row.getServiceId()))) {
                    throw new BusinessException(HttpStatus.CONFLICT,"BOOKING_CONFLICT","人员或服务绑定仍有未结束预约，请先处理预约");
                }
            }
        }
    }
    public void schedule(long barber,LocalDate date,List<ScheduleInterval> target) {
        var now=clock.instant();
        for (var row:bookings.occupied(barber,date)) {
            if (!BookingOrderPolicy.instant(row.getEndAt()).isAfter(now)) { continue; }
            var start=BookingOrderPolicy.instant(row.getStartAt()).atZone(BookingOrderPolicy.ZONE).toLocalTime();
            var end=BookingOrderPolicy.instant(row.getEndAt()).atZone(BookingOrderPolicy.ZONE).toLocalTime();
            if (target.stream().noneMatch(x -> !start.isBefore(LocalTime.parse(x.startTime())) && !end.isAfter(LocalTime.parse(x.endTime())))) { throw conflict(); }
        }
    }
    public void hours(long barber,LocalDate date,List<BusinessHourView> target) {
        var now=clock.instant();
        for (var row:bookings.occupied(barber,date)) {
            if (!BookingOrderPolicy.instant(row.getEndAt()).isAfter(now)) { continue; }
            var start=BookingOrderPolicy.instant(row.getStartAt()).atZone(BookingOrderPolicy.ZONE).toLocalTime();
            var end=BookingOrderPolicy.instant(row.getEndAt()).atZone(BookingOrderPolicy.ZONE).toLocalTime();
            if (target.stream().noneMatch(x -> x.weekday()==date.getDayOfWeek().getValue() && !start.isBefore(LocalTime.parse(x.startTime())) && !end.isAfter(LocalTime.parse(x.endTime())))) { throw conflict(); }
        }
    }
    private static BusinessException conflict() { return new BusinessException(HttpStatus.CONFLICT,"SCHEDULE_CONFLICT","新时段无法覆盖尚未结束的预约原区间"); }
}
