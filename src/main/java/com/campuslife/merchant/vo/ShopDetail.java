package com.campuslife.merchant.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record ShopDetail(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ShopCategoryView category,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String locationText,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean businessEnabled,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "开关及校园当前营业时段，不等于接单或空位") boolean openNow,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, description = "全部VISIBLE评价平均分，HALF_UP保留两位，无公开评价为null") Double rating,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "全部VISIBLE评价真实数量，含停用作者，隐藏不计入") long reviewCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String description,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<BusinessHourView> businessHours,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String campusCode) {}
