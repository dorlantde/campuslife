package com.campuslife.coupon.mapper;
import org.apache.ibatis.annotations.*;
public interface CouponMaintenanceMapper {
 @Select("SELECT COUNT(*) FROM coupon_claim_request WHERE campaign_id=#{id} AND status='PENDING'") long pending(long id);
 @Select("SELECT COUNT(*) FROM user_coupon WHERE campaign_id=#{id}") long issued(long id);
 @Update("UPDATE coupon_dispatch_gate SET phase=#{phase},mode=#{mode},control_version=control_version+1 WHERE campaign_id=#{id} AND control_version=#{version} AND phase IN ('OPEN','PAUSED') AND control_version<9223372036854775807")
 int change(@Param("id") long id,@Param("version") long version,@Param("mode") String mode,@Param("phase") String phase);
 @Update("UPDATE coupon_dispatch_gate SET resume_phase='OPEN' WHERE campaign_id=#{id} AND phase='REBUILDING' AND rebuild_token=#{token}")
 int resumeOpen(@Param("id") long id,@Param("token") String token);
}
