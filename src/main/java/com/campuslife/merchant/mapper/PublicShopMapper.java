package com.campuslife.merchant.mapper;

import com.campuslife.merchant.mapper.model.PublicShopRow;
import java.util.List;
import org.apache.ibatis.annotations.*;

public interface PublicShopMapper {
    String FROM_VISIBLE = """
            FROM shop s JOIN shop_category c ON c.id = s.category_id
            JOIN shop_member m ON m.shop_id = s.id AND m.member_role = 'OWNER'
            JOIN account a ON a.id = m.account_id
            WHERE s.approval_status = 'APPROVED' AND m.status = 'ACTIVE'
              AND a.status = 'ACTIVE' AND a.role = 'MERCHANT'
            """;
    String COLUMNS = "s.id, s.name, c.id AS categoryId, c.code AS categoryCode, c.name AS categoryName, "
            + "s.location_text, s.business_enabled, s.description, s.campus_code ";
    String FILTER = " AND (#{categoryId} IS NULL OR s.category_id = #{categoryId}) "
            + "AND (#{keyword} IS NULL OR s.name LIKE #{keyword} ESCAPE '!') ";

    @Select("SELECT COUNT(*) " + FROM_VISIBLE + FILTER)
    long count(@Param("categoryId") Long categoryId, @Param("keyword") String keyword);

    @Select("SELECT " + COLUMNS + FROM_VISIBLE + FILTER + " ORDER BY s.id ASC LIMIT #{size} OFFSET #{offset}")
    List<PublicShopRow> page(@Param("categoryId") Long categoryId, @Param("keyword") String keyword,
                             @Param("offset") long offset, @Param("size") int size);

    @Select("SELECT " + COLUMNS + FROM_VISIBLE + " AND s.id = #{id}")
    PublicShopRow visible(@Param("id") long id);

    @Select("SELECT v.*,COALESCE(r.revision,0) AS ratingRevision FROM (SELECT " + COLUMNS
            + FROM_VISIBLE + " AND s.id=#{id}) v LEFT JOIN shop_rating_revision r ON r.shop_id=v.id")
    com.campuslife.merchant.mapper.model.PublicShopRatingRow visibleWithRatingRevision(@Param("id") long id);

    // The fixed category makes (approval_status,category_id,id) ordered by id.
    // Apply the complete visibility predicate before LIMIT, then load display columns for the selected IDs.
    @Select("SELECT " + COLUMNS + """
            FROM (SELECT /*+ NO_SEMIJOIN(@eligibility) */ s.id FROM shop s
              WHERE s.approval_status = 'APPROVED' AND s.category_id = #{categoryId}
                AND EXISTS (SELECT /*+ QB_NAME(eligibility) */ 1
                  FROM shop_member m JOIN account a ON a.id = m.account_id
                  WHERE m.shop_id = s.id AND m.member_role = 'OWNER' AND m.status = 'ACTIVE'
                    AND a.status = 'ACTIVE' AND a.role = 'MERCHANT')
              ORDER BY s.id ASC LIMIT #{size}) selected
            JOIN shop s ON s.id = selected.id JOIN shop_category c ON c.id = s.category_id
            ORDER BY s.id ASC
            """)
    List<PublicShopRow> categoryFirstPage(@Param("categoryId") long categoryId, @Param("size") int size);
}
