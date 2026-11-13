package com.campuslife.admin.mapper;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

public interface ShopReviewMapper {
    @Insert("""
            INSERT INTO shop_review (shop_id, reviewed_version, decision, reason, reviewed_by, reviewed_at)
            VALUES (#{shopId}, #{version}, #{decision}, #{reason}, #{actor}, #{time})
            """)
    int append(@Param("shopId") long shopId, @Param("version") long version, @Param("decision") String decision,
               @Param("reason") String reason, @Param("actor") long actor, @Param("time") LocalDateTime time);
}
