package com.campuslife.merchant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campuslife.merchant.entity.ServiceItemEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface ServiceItemMapper extends BaseMapper<ServiceItemEntity> {
    String COLUMNS = "id, shop_id, service_type, name, description, price_amount, duration_minutes, enabled, version";

    @Select("SELECT " + COLUMNS + " FROM service_item WHERE shop_id = #{shopId} AND (#{type} IS NULL OR service_type = #{type}) "
            + "AND (#{enabled} IS NULL OR enabled = #{enabled}) ORDER BY created_at DESC, id DESC")
    List<ServiceItemEntity> listOwn(@Param("shopId") long shopId, @Param("type") String type, @Param("enabled") Boolean enabled);

    @Select("SELECT " + COLUMNS + " FROM service_item WHERE id = #{id} AND shop_id = #{shopId}")
    ServiceItemEntity findOwn(@Param("id") long id, @Param("shopId") long shopId);

    @Select("SELECT " + COLUMNS + " FROM service_item WHERE id = #{id} AND shop_id = #{shopId} FOR UPDATE")
    ServiceItemEntity lockOwn(@Param("id") long id, @Param("shopId") long shopId);

    @Update("""
            UPDATE service_item SET name = #{name}, description = #{description}, price_amount = #{amount},
                duration_minutes = #{duration}, enabled = #{enabled}, version = version + 1
            WHERE id = #{id} AND shop_id = #{shopId} AND version = #{version}
            """)
    int updateOwn(@Param("id") long id, @Param("shopId") long shopId, @Param("version") long version,
                  @Param("name") String name, @Param("description") String description, @Param("amount") long amount,
                  @Param("duration") Integer duration, @Param("enabled") boolean enabled);
}
