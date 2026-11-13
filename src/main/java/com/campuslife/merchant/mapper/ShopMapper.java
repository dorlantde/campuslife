package com.campuslife.merchant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campuslife.merchant.entity.ShopEntity;
import com.campuslife.merchant.mapper.model.OwnShopRow;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface ShopMapper extends BaseMapper<ShopEntity> {
    @Select("""
            SELECT s.id, s.name, c.id AS categoryId, c.code AS categoryCode, c.name AS categoryName,
                   s.location_text, s.description, s.campus_code, s.approval_status, s.approval_reason,
                   s.business_enabled, m.status AS memberStatus, s.version
            FROM shop_member m JOIN shop s ON s.id = m.shop_id JOIN shop_category c ON c.id = s.category_id
            WHERE m.account_id = #{accountId} AND m.member_role = 'OWNER'
            """)
    OwnShopRow findOwn(@Param("accountId") long accountId);

    @Select("SELECT id, name, location_text, description, business_enabled, approval_status, approval_reason, version FROM shop WHERE id = #{shopId} FOR UPDATE")
    ShopEntity lock(@Param("shopId") long shopId);

    @Select("SELECT id, approval_status FROM shop WHERE id = #{shopId} FOR SHARE")
    ShopEntity share(@Param("shopId") long shopId);

    @Update("UPDATE shop SET version = version + 1 WHERE id = #{shopId} AND version = #{version} AND approval_status = 'APPROVED'")
    int incrementVersion(@Param("shopId") long shopId, @Param("version") long version);

    @Update("""
            UPDATE shop SET name = #{name}, location_text = #{location}, description = #{description},
                business_enabled = #{business}, version = version + 1
            WHERE id = #{shopId} AND version = #{version} AND approval_status = 'APPROVED'
            """)
    int updateOperation(@Param("shopId") long shopId, @Param("version") long version, @Param("name") String name,
                        @Param("location") String location, @Param("description") String description, @Param("business") boolean business);

    @Update("""
            UPDATE shop SET category_id = #{categoryId}, name = #{name}, location_text = #{location},
                description = #{description}, approval_status = 'PENDING', approval_reason = NULL,
                reviewed_by = NULL, reviewed_at = NULL, business_enabled = FALSE, version = version + 1
            WHERE id = #{shopId} AND version = #{version} AND approval_status = 'REJECTED'
            """)
    int resubmit(@Param("shopId") long shopId, @Param("version") long version, @Param("categoryId") long categoryId,
                 @Param("name") String name, @Param("location") String location, @Param("description") String description);
}
