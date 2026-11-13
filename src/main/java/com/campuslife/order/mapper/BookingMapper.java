package com.campuslife.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campuslife.order.entity.BookingDetailEntity;
import java.time.*;
import java.util.List;
import org.apache.ibatis.annotations.*;

public interface BookingMapper extends BaseMapper<BookingDetailEntity> {
    @Select("SELECT * FROM booking_detail WHERE order_id=#{id} FOR UPDATE")
    BookingDetailEntity lockByOrder(long id);
    String OCCUPIED = "SELECT * FROM booking_detail WHERE barber_id=#{barber} AND service_date=#{date} AND occupancy_status IN ('HELD','ACTIVE','CONSUMED') ORDER BY order_id";
    @Select(OCCUPIED)
    List<BookingDetailEntity> occupied(@Param("barber") long barber,@Param("date") LocalDate date);
    @Select(OCCUPIED + " FOR UPDATE")
    List<BookingDetailEntity> lockOccupied(@Param("barber") long barber,@Param("date") LocalDate date);
    @Update("UPDATE booking_detail SET occupancy_status='RELEASED' WHERE order_id=#{id} AND occupancy_status='HELD'")
    int release(long id);
    @Update("UPDATE booking_detail SET occupancy_status='ACTIVE' WHERE order_id=#{id} AND occupancy_status='HELD'")
    int activate(long id);
    @Update("UPDATE booking_detail SET occupancy_status='RELEASED' WHERE order_id=#{id} AND occupancy_status='ACTIVE'")
    int releaseActive(long id);
    @Update("UPDATE booking_detail SET occupancy_status='CONSUMED' WHERE order_id=#{id} AND occupancy_status='ACTIVE'")
    int consumeActive(long id);
}
