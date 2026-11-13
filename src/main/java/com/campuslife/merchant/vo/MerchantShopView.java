package com.campuslife.merchant.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** Private owner-facing view. No password, session, or other merchant account fields. */
public record MerchantShopView(@Schema(type = "string") String id, String name, ShopCategoryView category,
                               String locationText, String description, String campusCode,
                               @Schema(allowableValues = {"PENDING", "APPROVED", "REJECTED", "SUSPENDED"}) String approvalStatus,
                               @Schema(nullable = true) String approvalReason, boolean businessEnabled,
                               @Schema(allowableValues = {"PENDING", "ACTIVE", "DISABLED"}) String memberStatus,
                               @Schema(type = "string", example = "0") String version,
                               @Schema(description = "每周营业时间，Asia/Shanghai；按星期和开始升序，未配置为空数组") List<BusinessHourView> businessHours) {
    public MerchantShopView { businessHours = List.copyOf(businessHours); }
}
