package com.campuslife.merchant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campuslife.merchant.entity.PrintPriceRuleEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface PrintPriceRuleMapper extends BaseMapper<PrintPriceRuleEntity> {
    String QUERY = "SELECT id, service_id, paper_size, color_mode, side_mode, price_per_impression, enabled, version "
            + "FROM print_price_rule WHERE service_id = #{serviceId} "
            + "ORDER BY color_mode, CASE side_mode WHEN 'SINGLE' THEN 0 ELSE 1 END";

    @Select(QUERY)
    List<PrintPriceRuleEntity> list(@Param("serviceId") long serviceId);

    @Select(QUERY + " FOR UPDATE")
    List<PrintPriceRuleEntity> current(@Param("serviceId") long serviceId);

    @Update("""
            UPDATE print_price_rule SET price_per_impression = #{amount}, enabled = #{enabled}, version = version + 1
            WHERE id = #{id} AND service_id = #{serviceId} AND version = #{version}
            """)
    int updateOwn(@Param("id") long id, @Param("serviceId") long serviceId, @Param("version") long version,
                  @Param("amount") long amount, @Param("enabled") boolean enabled);
}
