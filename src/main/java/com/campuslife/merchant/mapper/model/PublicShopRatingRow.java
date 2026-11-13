package com.campuslife.merchant.mapper.model;

public record PublicShopRatingRow(long id, String name, long categoryId, String categoryCode, String categoryName,
                                  String locationText, boolean businessEnabled, String description, String campusCode,
                                  long ratingRevision) {
    public PublicShopRow shop() {
        return new PublicShopRow(id, name, categoryId, categoryCode, categoryName, locationText, businessEnabled, description, campusCode);
    }
}
