package com.campuslife.merchant.vo;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record BusinessHourView(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "7", example = "1", description = "周一至周日，严格 JSON 整数") int weekday,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "^([01][0-9]|2[0-3]):[0-5][0-9]$", example = "09:00", description = "Asia/Shanghai 本地时间，精确到分钟") String startTime,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "^([01][0-9]|2[0-3]):[0-5][0-9]$", example = "12:00", description = "严格晚于开始，不跨午夜，不含结束时刻") String endTime) {}
