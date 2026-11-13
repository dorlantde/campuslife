package com.campuslife.merchant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campuslife.merchant.entity.BarberEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface BarberMapper extends BaseMapper<BarberEntity> {
    String COLUMNS = "id, shop_id, display_name, enabled, version";

    @Select("SELECT " + COLUMNS + " FROM barber WHERE shop_id = #{shopId} ORDER BY created_at DESC, id DESC")
    List<BarberEntity> listOwn(@Param("shopId") long shopId);

    @Select("SELECT " + COLUMNS + " FROM barber WHERE id = #{id} AND shop_id = #{shopId}")
    BarberEntity findOwn(@Param("id") long id, @Param("shopId") long shopId);

    @Select("SELECT " + COLUMNS + " FROM barber WHERE id = #{id} AND shop_id = #{shopId} FOR UPDATE")
    BarberEntity lockOwn(@Param("id") long id, @Param("shopId") long shopId);

    @Update("""
            UPDATE barber SET display_name = #{name}, enabled = #{enabled}, version = version + 1
            WHERE id = #{id} AND shop_id = #{shopId} AND version = #{version}
            """)
    int updateOwn(@Param("id") long id, @Param("shopId") long shopId, @Param("version") long version,
                  @Param("name") String name, @Param("enabled") boolean enabled);
}
