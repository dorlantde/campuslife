package com.campuslife.merchant.cache;

import java.math.BigInteger;
import tools.jackson.databind.ObjectMapper;

public final class RatingCacheCodec {
    private final ObjectMapper json;
    public RatingCacheCodec(ObjectMapper json) { this.json = json; }
    public String encode(RatingCacheKey key, RatingStats stats) {
        return json.writeValueAsString(json.createObjectNode().put("schema", 1)
                .put("shopId", Long.toString(key.shopId())).put("revision", Long.toString(key.revision()))
                .put("ratingSum", stats.sum().toString()).put("reviewCount", Long.toString(stats.count())));
    }
    public RatingStats decode(RatingCacheKey key, String payload) {
        if (payload == null || payload.length() > 2048) { throw new IllegalArgumentException("Invalid cache size"); }
        var node = json.readTree(payload);
        if (node == null || !node.isObject() || node.size() != 5 || !node.path("schema").isIntegralNumber()
                || !node.path("schema").toString().equals("1")) { throw new IllegalArgumentException("Invalid cache schema"); }
        for (String field : new String[]{"shopId", "revision", "ratingSum", "reviewCount"}) {
            var value = node.path(field);
            if (!value.isString() || !value.asString().matches("0|[1-9][0-9]{0,19}")) {
                throw new IllegalArgumentException("Invalid cache field");
            }
        }
        if (Long.parseLong(node.path("shopId").asString()) != key.shopId()
                || Long.parseLong(node.path("revision").asString()) != key.revision()) {
            throw new IllegalArgumentException("Cache identity mismatch");
        }
        return new RatingStats(new BigInteger(node.path("ratingSum").asString()),
                Long.parseLong(node.path("reviewCount").asString()));
    }
}
