package com.campuslife.merchant.cache;

public record RatingCacheKey(long shopId, long revision) {
    public RatingCacheKey {
        if (shopId <= 0 || revision < 0) { throw new IllegalArgumentException("Invalid rating cache identity"); }
    }
    public String redisKey(String namespace) {
        return "campuslife:" + namespace + ":rating:s1:" + shopId + ":" + revision;
    }
}
