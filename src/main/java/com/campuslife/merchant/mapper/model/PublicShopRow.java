package com.campuslife.merchant.mapper.model;

public record PublicShopRow(long id, String name, long categoryId, String categoryCode, String categoryName,
                            String locationText, boolean businessEnabled, String description, String campusCode) {}
