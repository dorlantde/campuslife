package com.campuslife.merchant.cache;

import com.campuslife.merchant.mapper.PublicReviewMapper.RatingRow;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

public record RatingStats(BigInteger sum, long count) {
    public RatingStats {
        if (sum == null || count < 0 || sum.compareTo(BigInteger.valueOf(count)) < 0
                || sum.compareTo(BigInteger.valueOf(count).multiply(BigInteger.valueOf(5))) > 0) {
            throw new IllegalArgumentException("Invalid rating aggregate");
        }
    }
    public static RatingStats from(List<RatingRow> rows) {
        if (rows.isEmpty()) { return new RatingStats(BigInteger.ZERO, 0); }
        if (rows.size() != 1) { throw new IllegalStateException("Expected one shop aggregate"); }
        var row = rows.getFirst();
        return new RatingStats(row.ratingSum().toBigIntegerExact(), row.reviewCount());
    }
    public Double average(long shopId) {
        return count == 0 ? null : new RatingRow(shopId, new BigDecimal(sum), count).average();
    }
}
