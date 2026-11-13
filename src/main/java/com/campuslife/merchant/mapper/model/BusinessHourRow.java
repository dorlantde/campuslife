package com.campuslife.merchant.mapper.model;

import com.campuslife.merchant.vo.BusinessHourView;

public record BusinessHourRow(long shopId, int weekday, String startTime, String endTime) {
    public BusinessHourView view() { return new BusinessHourView(weekday, startTime, endTime); }
}
