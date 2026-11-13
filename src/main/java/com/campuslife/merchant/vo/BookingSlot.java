package com.campuslife.merchant.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public record BookingSlot(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", format = "date-time", example = "2026-10-22T01:00:00Z", description = "UTC 开始时刻；候选不预留资源") Instant startAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", format = "date-time", example = "2026-10-22T01:30:00Z", description = "UTC 结束时刻，等于开始加真实服务时长") Instant endAt) {}
