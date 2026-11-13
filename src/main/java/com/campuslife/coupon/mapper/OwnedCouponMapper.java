package com.campuslife.coupon.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campuslife.coupon.entity.OwnedCouponEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;
public interface OwnedCouponMapper extends BaseMapper<OwnedCouponEntity> {
    @Select("SELECT * FROM user_coupon WHERE id=#{id} FOR UPDATE") OwnedCouponEntity lock(long id);
    @Update("UPDATE user_coupon SET state='LOCKED',current_order_id=#{order},version=version+1 WHERE id=#{id} AND state='AVAILABLE' AND current_order_id IS NULL AND version<9223372036854775807")
    int reserve(@Param("id") long id,@Param("order") long order);
    @Update("UPDATE user_coupon SET state='USED',version=version+1 WHERE id=#{id} AND state='LOCKED' AND current_order_id=#{order} AND version<9223372036854775807")
    int consume(@Param("id") long id,@Param("order") long order);
    @Update("UPDATE user_coupon SET state=#{state},current_order_id=NULL,version=version+1 WHERE id=#{id} AND state='LOCKED' AND current_order_id=#{order} AND version<9223372036854775807")
    int release(@Param("id") long id,@Param("order") long order,@Param("state") String state);
    @Select("SELECT * FROM user_coupon WHERE campaign_id=#{campaign} AND student_id=#{student}")
    OwnedCouponEntity findCampaign(@Param("campaign") long campaign,@Param("student") long student);
    String FILTER=" WHERE student_id=#{student} AND (#{shop} IS NULL OR shop_id=#{shop}) AND (#{state} IS NULL OR CASE WHEN state='AVAILABLE' AND use_end_at<=#{now} THEN 'EXPIRED' ELSE state END=#{state}) ";
    @Select("SELECT COUNT(*) FROM user_coupon"+FILTER)
    long countOwn(@Param("student") long student,@Param("shop") Long shop,@Param("state") String state,@Param("now") LocalDateTime now);
    @Select("SELECT * FROM user_coupon"+FILTER+"ORDER BY claimed_at DESC,id DESC LIMIT #{size} OFFSET #{offset}")
    List<OwnedCouponEntity> pageOwn(@Param("student") long student,@Param("shop") Long shop,@Param("state") String state,@Param("now") LocalDateTime now,@Param("size") int size,@Param("offset") long offset);
}
