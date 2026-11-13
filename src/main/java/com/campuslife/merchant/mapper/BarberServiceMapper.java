package com.campuslife.merchant.mapper;

import com.campuslife.merchant.mapper.model.BarberServiceRow;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface BarberServiceMapper {
    @Select("SELECT EXISTS(SELECT 1 FROM barber_service WHERE barber_id = #{barberId} AND service_id = #{serviceId})")
    boolean bound(@Param("barberId") long barberId, @Param("serviceId") long serviceId);

    @Select("""
            SELECT bs.barber_id, bs.service_id FROM barber_service bs JOIN barber b ON b.id = bs.barber_id
            WHERE b.shop_id = #{shopId} ORDER BY bs.barber_id, bs.service_id
            """)
    List<BarberServiceRow> listOwnShop(@Param("shopId") long shopId);

    @Select("SELECT service_id FROM barber_service WHERE barber_id = #{barberId} ORDER BY service_id FOR UPDATE")
    List<Long> current(@Param("barberId") long barberId);

    @Insert("INSERT INTO barber_service (barber_id, service_id) VALUES (#{barberId}, #{serviceId})")
    int insert(@Param("barberId") long barberId, @Param("serviceId") long serviceId);

    @Delete("DELETE FROM barber_service WHERE barber_id = #{barberId} AND service_id = #{serviceId}")
    int delete(@Param("barberId") long barberId, @Param("serviceId") long serviceId);
}
