package com.campuslife.admin.mapper;

import com.campuslife.admin.mapper.model.ReviewShopRow;
import com.campuslife.merchant.mapper.model.OwnShopRow;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface AdminShopMapper {
    String VIEW = """
            SELECT s.id, s.name, c.id AS categoryId, c.code AS categoryCode, c.name AS categoryName,
                   s.location_text, s.description, s.campus_code, s.approval_status, s.approval_reason,
                   s.business_enabled, m.status AS memberStatus, s.version
            FROM shop s JOIN shop_category c ON c.id = s.category_id
            JOIN shop_member m ON m.shop_id = s.id AND m.member_role = 'OWNER'
            """;

    @Select(VIEW + " WHERE s.id = #{id}")
    OwnShopRow detail(@Param("id") long id);

    @Select(VIEW + " WHERE (#{status} IS NULL OR s.approval_status = #{status}) ORDER BY s.created_at DESC, s.id DESC LIMIT #{size} OFFSET #{offset}")
    List<OwnShopRow> page(@Param("status") String status, @Param("offset") long offset, @Param("size") int size);

    @Select("SELECT COUNT(*) FROM shop WHERE (#{status} IS NULL OR approval_status = #{status})")
    long count(@Param("status") String status);

    @Select("SELECT id, approval_status, version FROM shop WHERE id = #{id} FOR UPDATE")
    ReviewShopRow lock(@Param("id") long id);

    @Update("""
            UPDATE shop SET approval_status = #{decision}, approval_reason = #{reason}, reviewed_by = #{actor},
                reviewed_at = #{time}, business_enabled = FALSE, version = version + 1
            WHERE id = #{id} AND approval_status = 'PENDING' AND version = #{version}
            """)
    int decide(@Param("id") long id, @Param("version") long version, @Param("decision") String decision,
               @Param("reason") String reason, @Param("actor") long actor, @Param("time") LocalDateTime time);
}
