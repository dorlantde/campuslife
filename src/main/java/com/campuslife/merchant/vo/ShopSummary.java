package com.campuslife.merchant.vo;

import io.swagger.v3.oas.annotations.media.Schema;

public record ShopSummary(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ShopCategoryView category,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String locationText,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "商家手动营业意愿") boolean businessEnabled,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "开关开启且校园当前时刻处于营业时段；不表示可预约或有空位") boolean openNow,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, description = "全部VISIBLE评价平均分，十进制HALF_UP保留两位，无公开评价为null") Double rating,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "全部VISIBLE评价真实数量，含停用作者，隐藏不计入") long reviewCount) {}
