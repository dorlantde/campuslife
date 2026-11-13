package com.campuslife.merchant.mapper;

import com.campuslife.merchant.mapper.model.BarberDay;
import com.campuslife.merchant.mapper.model.BarberScheduleRow;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

public interface BarberScheduleMapper {
    @Select("SELECT barber_id, service_date, start_at, end_at FROM barber_schedule WHERE barber_id = #{barberId} AND service_date = #{date} ORDER BY start_at")
    List<BarberScheduleRow> rows(@Param("barberId") long barberId, @Param("date") LocalDate date);

    @Select("SELECT id FROM barber_day_guard WHERE barber_id = #{barberId} AND service_date = #{date} FOR UPDATE")
    Long lockGuard(@Param("barberId") long barberId, @Param("date") LocalDate date);

    @Insert("INSERT INTO barber_day_guard (barber_id, service_date) VALUES (#{barberId}, #{date})")
    int insertGuard(@Param("barberId") long barberId, @Param("date") LocalDate date);

    @Delete("DELETE FROM barber_schedule WHERE barber_id = #{barberId} AND service_date = #{date}")
    int delete(@Param("barberId") long barberId, @Param("date") LocalDate date);

    @Insert("INSERT INTO barber_schedule (barber_id, service_date, start_at, end_at) VALUES (#{barberId}, #{date}, #{start}, #{end})")
    int insert(@Param("barberId") long barberId, @Param("date") LocalDate date,
               @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    // Shop X is already held: no compliant writer can add a new date while enumerating these guards.
    @Select("""
            SELECT g.barber_id, g.service_date FROM barber_day_guard g JOIN barber b ON b.id = g.barber_id
            WHERE b.shop_id = #{shopId} AND g.service_date >= #{today} ORDER BY g.barber_id, g.service_date
            """)
    List<BarberDay> futureDays(@Param("shopId") long shopId, @Param("today") LocalDate today);
}
