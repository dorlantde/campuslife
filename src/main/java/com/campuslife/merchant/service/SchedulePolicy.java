package com.campuslife.merchant.service;

import com.campuslife.merchant.vo.BusinessHourView;
import com.campuslife.merchant.vo.ScheduleInterval;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

final class SchedulePolicy {
    static final ZoneId CAMPUS_ZONE = ZoneId.of("Asia/Shanghai");
    private SchedulePolicy() {}

    static LocalDate date(String value) {
        if (value == null || !value.matches("[1-9][0-9]{3}-[0-9]{2}-[0-9]{2}")) { throw MerchantShopPolicy.invalid("date 必须为 1000～9999 年严格 YYYY-MM-DD 日期"); }
        try { return LocalDate.parse(value); }
        catch (DateTimeParseException ex) { throw MerchantShopPolicy.invalid("date 必须是真实日期"); }
    }
    static LocalDate date(JsonNode value) {
        if (value == null || !value.isString()) { throw MerchantShopPolicy.invalid("date 必须是日期字符串"); }
        return date(value.asString());
    }
    static void writable(LocalDate date, Clock clock) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), CAMPUS_ZONE);
        if (date.isBefore(today) || date.isAfter(today.plusDays(6))) { throw MerchantShopPolicy.invalid("仅可修改校园时区今天至第 6 天的排班"); }
    }
    static List<ScheduleInterval> normalize(JsonNode value) {
        if (value == null || !value.isArray() || value.size() > 4) { throw MerchantShopPolicy.invalid("intervals 必须是 0～4 段的数组"); }
        var sorted = new ArrayList<ScheduleInterval>();
        for (var item : value) {
            if (!item.isObject() || item.size() != 2 || !item.propertyNames().equals(Set.of("startTime", "endTime"))) { throw MerchantShopPolicy.invalid("每段只允许且必须包含 startTime、endTime"); }
            String start = time(item.get("startTime")), end = time(item.get("endTime"));
            if (start.compareTo(end) >= 0) { throw MerchantShopPolicy.invalid("时段开始必须早于结束，不支持跨午夜"); }
            sorted.add(new ScheduleInterval(start, end));
        }
        sorted.sort(Comparator.comparing(ScheduleInterval::startTime));
        var merged = new ArrayList<ScheduleInterval>();
        for (var interval : sorted) {
            if (merged.isEmpty()) { merged.add(interval); continue; }
            var last = merged.getLast();
            int comparison = last.endTime().compareTo(interval.startTime());
            if (comparison > 0) { throw MerchantShopPolicy.invalid("排班时段不能重叠或重复"); }
            if (comparison == 0) { merged.set(merged.size() - 1, new ScheduleInterval(last.startTime(), interval.endTime())); }
            else { merged.add(interval); }
        }
        return List.copyOf(merged);
    }
    static boolean covered(LocalDate date, ScheduleInterval interval, List<BusinessHourView> hours) {
        return hours.stream().anyMatch(h -> h.weekday() == date.getDayOfWeek().getValue()
                && h.startTime().compareTo(interval.startTime()) <= 0 && h.endTime().compareTo(interval.endTime()) >= 0);
    }
    static LocalDateTime utc(LocalDate date, String time) {
        return LocalDateTime.ofInstant(date.atTime(LocalTime.parse(time)).atZone(CAMPUS_ZONE).toInstant(), ZoneOffset.UTC);
    }
    static String localTime(LocalDateTime utc) {
        return utc.toInstant(ZoneOffset.UTC).atZone(CAMPUS_ZONE).toLocalTime().toString();
    }
    private static String time(JsonNode value) {
        if (value == null || !value.isString() || !value.asString().matches("([01][0-9]|2[0-3]):(00|15|30|45)")) { throw MerchantShopPolicy.invalid("时间必须为 HH:mm 且按 15 分钟对齐"); }
        return value.asString();
    }
}
