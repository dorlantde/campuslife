package com.campuslife.merchant.mapper.model;

public record OwnShopRow(long id, String name, long categoryId, String categoryCode, String categoryName,
                         String locationText, String description, String campusCode, String approvalStatus,
                         String approvalReason, boolean businessEnabled, String memberStatus, long version) {
}
