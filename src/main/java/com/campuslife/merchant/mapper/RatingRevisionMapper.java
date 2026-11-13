package com.campuslife.merchant.mapper;

import org.apache.ibatis.annotations.Insert;

public interface RatingRevisionMapper {
    // Evaluated under the row lock. Overflow fails and rolls back the review transaction.
    @Insert("INSERT INTO shop_rating_revision (shop_id,revision) VALUES (#{shop},1) "
            + "ON DUPLICATE KEY UPDATE revision=revision+1")
    int advance(long shop);
}
