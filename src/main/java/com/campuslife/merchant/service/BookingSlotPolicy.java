package com.campuslife.merchant.service;

import com.campuslife.merchant.mapper.model.BarberScheduleRow;
import com.campuslife.merchant.vo.BookingSlot;
import com.campuslife.merchant.vo.BusinessHourView;
import java.time.*;
import java.util.ArrayList;
import java.util.List;

public final class BookingSlotPolicy {
    private BookingSlotPolicy() {}

    static LocalDate date(String text, Instant now) {
        var date = SchedulePolicy.date(text);
        var today = now.atZone(SchedulePolicy.CAMPUS_ZONE).toLocalDate();
        if (date.isBefore(today) || date.isAfter(today.plusDays(6))) { throw MerchantShopPolicy.invalid("date 仅允许校园时区今天至第 6 天"); }
        return date;
    }

    public static List<BookingSlot> candidates(LocalDate date, Instant now, int duration,
                                         List<BusinessHourView> hours, List<BarberScheduleRow> schedules) {
        if (duration < 15 || duration > 240 || duration % 15 != 0) { throw new IllegalArgumentException("Invalid stored haircut duration"); }
        var earliest = now.plus(Duration.ofHours(2));
        var result = new ArrayList<BookingSlot>();
        // Write policies normalize adjacent hours/shifts. A complete service must fit one row of each.
        // A single local-day grid inherently sorts and deduplicates even if source rows overlap.
        for (int minute = 0; minute < 24 * 60; minute += 15) {
            var start = date.atStartOfDay().plusMinutes(minute);
            var end = start.plusMinutes(duration);
            if (!end.toLocalDate().equals(date)) { continue; }
            var startAt = start.atZone(SchedulePolicy.CAMPUS_ZONE).toInstant();
            var endAt = end.atZone(SchedulePolicy.CAMPUS_ZONE).toInstant();
            if (startAt.isBefore(earliest)) { continue; }
            boolean open = hours.stream().anyMatch(hour -> hour.weekday() == date.getDayOfWeek().getValue()
                    && !start.toLocalTime().isBefore(LocalTime.parse(hour.startTime()))
                    && !end.toLocalTime().isAfter(LocalTime.parse(hour.endTime())));
            if (!open) { continue; }
            boolean scheduled = schedules.stream().anyMatch(row -> row.serviceDate().equals(date)
                    && !startAt.isBefore(row.startAt().toInstant(ZoneOffset.UTC))
                    && !endAt.isAfter(row.endAt().toInstant(ZoneOffset.UTC)));
            if (scheduled) { result.add(new BookingSlot(startAt, endAt)); }
        }
        return List.copyOf(result);
    }
}
