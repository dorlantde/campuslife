package com.campuslife.coupon.mapper;
import com.campuslife.coupon.vo.OrderCouponView;
import com.campuslife.coupon.entity.OwnedCouponEntity;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.*;
public interface OrderCouponMapper {
    record Row(long orderId,long userCouponId,long campaignId,String nameSnapshot,long thresholdAmount,long reductionAmount,LocalDateTime useStartAt,LocalDateTime useEndAt,long sourceCampaignVersion,String state,LocalDateTime lockedAt,LocalDateTime usedAt,LocalDateTime releasedAt) {}
    @Select("SELECT * FROM order_coupon WHERE order_id=#{id}") Row find(long id);
    @Insert("""
        INSERT INTO order_coupon(order_id,user_coupon_id,campaign_id,name_snapshot,threshold_amount,reduction_amount,use_start_at,use_end_at,source_campaign_version,state,locked_at)
        VALUES(#{order},#{c.id},#{c.campaignId},#{c.nameSnapshot},#{c.thresholdAmount},#{c.reductionAmount},#{c.useStartAt},#{c.useEndAt},#{c.sourceCampaignVersion},'LOCKED',#{now})
        """) int create(@Param("order") long order,@Param("c") OwnedCouponEntity c,@Param("now") LocalDateTime now);
    @Update("UPDATE order_coupon SET state='USED',used_at=#{now} WHERE order_id=#{id} AND state='LOCKED'") int use(@Param("id") long id,@Param("now") LocalDateTime now);
    @Update("UPDATE order_coupon SET state='RELEASED',released_at=#{now} WHERE order_id=#{id} AND state='LOCKED'") int release(@Param("id") long id,@Param("now") LocalDateTime now);
}
