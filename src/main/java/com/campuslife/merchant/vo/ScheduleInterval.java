package com.campuslife.merchant.vo;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record ScheduleInterval(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "^([01][0-9]|2[0-3]):(00|15|30|45)$", example = "09:00", description = "Asia/Shanghai，15 分钟对齐") String startTime,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "^([01][0-9]|2[0-3]):(00|15|30|45)$", example = "12:00", description = "严格晚于开始，不跨午夜，不含结束时刻") String endTime) {}
