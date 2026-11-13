package com.campuslife.merchant.mapper;

import com.campuslife.merchant.mapper.model.PublicBarberRow;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface PublicBarberMapper {
    // Join all public bindings; the optional EXISTS selects people without truncating their serviceIds.
    // Independent FK constraints do not ensure that a linked service belongs to the barber's shop.
    @Select("""
            SELECT b.id AS barberId, b.display_name, s.id AS serviceId
            FROM barber b JOIN barber_service bs ON bs.barber_id = b.id
            JOIN service_item s ON s.id = bs.service_id AND s.shop_id = b.shop_id
                AND s.service_type = 'HAIRCUT' AND s.enabled = TRUE
            WHERE b.shop_id = #{shopId} AND b.enabled = TRUE
              AND (#{serviceId} IS NULL OR EXISTS (
                  SELECT 1 FROM barber_service target
                  WHERE target.barber_id = b.id AND target.service_id = #{serviceId}))
            ORDER BY b.created_at DESC, b.id DESC, s.id ASC
            """)
    List<PublicBarberRow> rows(@Param("shopId") long shopId, @Param("serviceId") Long serviceId);
}
