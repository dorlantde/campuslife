package com.campuslife.merchant.service;

import com.campuslife.merchant.vo.BusinessHourView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import tools.jackson.databind.JsonNode;

final class BusinessHoursPolicy {
    private static final String TIME = "([01][0-9]|2[0-3]):[0-5][0-9]";
    private BusinessHoursPolicy() {}

    static List<BusinessHourView> normalize(JsonNode intervals) {
        if (intervals == null || !intervals.isArray() || intervals.size() > 28) { throw MerchantShopPolicy.invalid("intervals 须为最多 28 项的数组"); }
        var sorted = new ArrayList<BusinessHourView>();
        int[] counts = new int[8];
        for (JsonNode item : intervals) {
            if (!item.isObject() || item.size() != 3 || !item.has("weekday") || !item.has("startTime") || !item.has("endTime")) {
                throw MerchantShopPolicy.invalid("每个时段须且仅包含 weekday、startTime、endTime");
            }
            var day = item.get("weekday");
            if (!day.isIntegralNumber() || !day.toString().matches("[1-7]")) { throw MerchantShopPolicy.invalid("weekday 须为 1～7 的 JSON 整数"); }
            int weekday = day.asInt();
            if (++counts[weekday] > 4) { throw MerchantShopPolicy.invalid("每天最多 4 个原始时段"); }
            String start = time(item.get("startTime")), end = time(item.get("endTime"));
            if (start.compareTo(end) >= 0) { throw MerchantShopPolicy.invalid("开始须早于结束，不支持跨午夜"); }
            sorted.add(new BusinessHourView(weekday, start, end));
        }
        sorted.sort(Comparator.comparingInt(BusinessHourView::weekday).thenComparing(BusinessHourView::startTime).thenComparing(BusinessHourView::endTime));
        var merged = new ArrayList<BusinessHourView>();
        for (var next : sorted) {
            if (!merged.isEmpty()) {
                var previous = merged.getLast();
                if (previous.weekday() == next.weekday()) {
                    int gap = next.startTime().compareTo(previous.endTime());
                    if (gap < 0) { throw MerchantShopPolicy.invalid("同一天营业时段不能重叠或重复"); }
                    if (gap == 0) { merged.set(merged.size() - 1, new BusinessHourView(previous.weekday(), previous.startTime(), next.endTime())); continue; }
                }
            }
            merged.add(next);
        }
        return List.copyOf(merged);
    }

    private static String time(JsonNode value) {
        if (!value.isString() || !value.asString().matches(TIME)) { throw MerchantShopPolicy.invalid("时间须为严格 HH:mm，范围 00:00～23:59"); }
        return value.asString();
    }
}
