package com.campuslife.merchant.mapper;

import com.campuslife.merchant.mapper.model.BusinessHourRow;
import com.campuslife.merchant.vo.BusinessHourView;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.ibatis.annotations.*;

public interface BusinessHourMapper {
    String SELECT = "SELECT shop_id, weekday, TIME_FORMAT(start_time, '%H:%i') AS startTime, TIME_FORMAT(end_time, '%H:%i') AS endTime FROM shop_business_hour";

    @Select(SELECT + " WHERE shop_id = #{shopId} ORDER BY weekday, start_time")
    List<BusinessHourRow> rows(@Param("shopId") long shopId);

    @Select(SELECT + " WHERE shop_id = #{shopId} ORDER BY weekday, start_time FOR UPDATE")
    List<BusinessHourRow> lockRows(@Param("shopId") long shopId);

    @SelectProvider(type = Sql.class, method = "batch")
    List<BusinessHourRow> batchRows(@Param("shopIds") List<Long> shopIds);

    @Delete("DELETE FROM shop_business_hour WHERE shop_id = #{shopId}")
    int delete(@Param("shopId") long shopId);

    @Insert("INSERT INTO shop_business_hour (shop_id,weekday,start_time,end_time) VALUES (#{shopId},#{hour.weekday},#{hour.startTime},#{hour.endTime})")
    int insert(@Param("shopId") long shopId, @Param("hour") BusinessHourView hour);

    default List<BusinessHourView> views(long shopId) { return rows(shopId).stream().map(BusinessHourRow::view).toList(); }
    default List<BusinessHourView> current(long shopId) { return lockRows(shopId).stream().map(BusinessHourRow::view).toList(); }
    final class Sql {
        private Sql() {}
        public static String batch(Map<String, Object> parameters) {
            var ids = (List<?>) parameters.get("shopIds");
            if (ids.isEmpty()) { return SELECT + " WHERE 1 = 0"; }
            // Only placeholder positions are generated. Every ID remains a bound SQL parameter.
            String bindings = IntStream.range(0, ids.size()).mapToObj(i -> "#{shopIds[" + i + "]}").collect(Collectors.joining(","));
            return SELECT + " WHERE shop_id IN (" + bindings + ") ORDER BY shop_id, weekday, start_time";
        }
    }
}
