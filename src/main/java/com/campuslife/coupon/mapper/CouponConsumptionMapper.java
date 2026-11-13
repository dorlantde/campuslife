package com.campuslife.coupon.mapper;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.*;
public interface CouponConsumptionMapper {
 @Insert("INSERT INTO message_consumption(consumer_name,message_id,payload_hash,processed_at) VALUES ('coupon-issue-v1',#{id},#{hash},#{now}) ON DUPLICATE KEY UPDATE message_id=message_id")
 int receipt(@Param("id") String id,@Param("hash") String hash,@Param("now") LocalDateTime now);
 @Select("SELECT payload_hash FROM message_consumption WHERE consumer_name='coupon-issue-v1' AND message_id=#{id} FOR UPDATE") String hash(String id);
}
