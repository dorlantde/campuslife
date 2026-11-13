package com.campuslife.coupon.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campuslife.coupon.entity.ClaimRequestEntity;
import org.apache.ibatis.annotations.*;
public interface ClaimRequestMapper extends BaseMapper<ClaimRequestEntity> {
    @Select("SELECT * FROM coupon_claim_request WHERE student_id=#{student} AND request_key=#{key}")
    ClaimRequestEntity findKey(@Param("student") long student,@Param("key") String key);
    @Select("SELECT * FROM coupon_claim_request WHERE student_id=#{student} AND id=#{id}")
    ClaimRequestEntity findOwn(@Param("student") long student,@Param("id") long id);
    @Select("SELECT * FROM coupon_claim_request WHERE id=#{id} FOR UPDATE")
    ClaimRequestEntity lock(long id);
    @Select("SELECT * FROM coupon_claim_request WHERE student_id=#{student} AND active_campaign_id=#{campaign}")
    ClaimRequestEntity active(@Param("student") long student,@Param("campaign") long campaign);
    @Select("SELECT r.id FROM coupon_claim_request r JOIN coupon_dispatch_gate g ON g.campaign_id=r.campaign_id WHERE r.status='PENDING' AND r.deadline<=#{now} AND g.mode='ASYNC' AND g.phase IN ('OPEN','PAUSED') ORDER BY r.deadline,r.id LIMIT 100")
    java.util.List<Long> due(java.time.LocalDateTime now);
    @Update("UPDATE coupon_claim_request SET status=#{status},user_coupon_id=#{coupon},failure_code=#{failure},completed_at=#{now} WHERE id=#{id} AND status='PENDING'")
    int finish(@Param("id") long id,@Param("status") String status,@Param("coupon") Long coupon,@Param("failure") String failure,@Param("now") java.time.LocalDateTime now);
}
