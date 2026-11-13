package com.campuslife.merchant.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "营业/排班候选扣除 HELD/ACTIVE/CONSUMED 真实占用；不预留资源或冻结价格")
public record BookingSlotsView(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "同一查询快照中的服务版本") String serviceVersion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "100000", description = "当前服务价格，整数分，不冻结") long priceAmount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "15", maximum = "240", description = "理发服务分钟数，15 的倍数") int durationMinutes,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "UTC 候选按开始升序、去重；无候选返回 []，已排除真实占用") List<BookingSlot> slots) {}
