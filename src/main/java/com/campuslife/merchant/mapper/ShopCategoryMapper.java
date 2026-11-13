package com.campuslife.merchant.mapper;

import com.campuslife.merchant.vo.ShopCategoryView;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface ShopCategoryMapper {
    @Select("SELECT id, code, name FROM shop_category WHERE enabled = TRUE ORDER BY sort_order, id")
    List<ShopCategoryView> enabled();

    // Hold the dictionary row through application commit, including concurrent category disabling.
    @Select("SELECT id FROM shop_category WHERE id = #{id} AND enabled = TRUE FOR SHARE")
    Long lockEnabled(@Param("id") long id);
}
