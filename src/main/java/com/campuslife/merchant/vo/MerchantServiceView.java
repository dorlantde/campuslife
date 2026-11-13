package com.campuslife.merchant.vo;

import io.swagger.v3.oas.annotations.media.Schema;

public record MerchantServiceView(
        @Schema(type = "string") String id,
        @Schema(allowableValues = {"HAIRCUT", "PRINT"}) String serviceType,
        String name, String description,
        @Schema(minimum = "1", maximum = "100000", description = "整数分；PRINT 只作参考起价，不是规格报价") long priceAmount,
        @Schema(nullable = true, description = "HAIRCUT 为分钟数，PRINT 为 null") Integer durationMinutes,
        boolean enabled, @Schema(type = "string") String version) {}
