package com.campuslife.merchant.vo;

import io.swagger.v3.oas.annotations.media.Schema;

public record ServiceView(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"HAIRCUT", "PRINT"}) String serviceType,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String description,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "100000", description = "整数分；PRINT 仅为参考起价，不是规格报价") long priceAmount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, description = "HAIRCUT 分钟数，PRINT 为 null") Integer durationMinutes,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "服务版本，不代表预留价格") String version) {}
