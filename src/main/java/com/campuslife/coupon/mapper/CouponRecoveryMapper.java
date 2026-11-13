package com.campuslife.coupon.mapper;
import com.campuslife.coupon.entity.ClaimRequestEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;
public interface CouponRecoveryMapper {
 @Select("SELECT * FROM coupon_claim_request WHERE student_id=#{student} AND request_key=#{key} FOR UPDATE")
 ClaimRequestEntity lockKey(@Param("student") long student,@Param("key") String key);
 @Select("SELECT * FROM coupon_claim_request WHERE campaign_id=#{campaign} AND status='PENDING' AND (accepted_at>#{after} OR (accepted_at=#{after} AND id>#{id})) ORDER BY accepted_at,id LIMIT 200")
 List<ClaimRequestEntity> pendingOrdered(@Param("campaign") long campaign,@Param("after") LocalDateTime after,@Param("id") long id);
 @Select("SELECT * FROM coupon_claim_request WHERE campaign_id=#{campaign} AND status='PENDING' AND id>#{after} ORDER BY id LIMIT 200")
 List<ClaimRequestEntity> pendingPage(@Param("campaign") long campaign,@Param("after") long after);
 record Issued(long studentId,long couponId,String requestKey,String reservationId,Long acceptedGeneration,LocalDateTime createdAt) {}
 @Select("SELECT c.student_id,c.id AS coupon_id,r.request_key,r.reservation_id,r.accepted_generation,r.created_at FROM user_coupon c LEFT JOIN coupon_claim_request r ON r.user_coupon_id=c.id WHERE c.campaign_id=#{campaign} AND c.id>#{after} ORDER BY c.id LIMIT 200")
 List<Issued> issued(@Param("campaign") long campaign,@Param("after") long after);
 @Select("SELECT r.id FROM coupon_claim_request r JOIN coupon_dispatch_gate g ON g.campaign_id=r.campaign_id WHERE r.source_mode='ASYNC' AND r.status IN ('SUCCEEDED','FAILED') AND g.mode='ASYNC' AND g.phase IN ('OPEN','PAUSED') AND (r.redis_settled_generation IS NULL OR r.redis_settled_generation<>g.generation) AND r.id>#{after} ORDER BY r.id LIMIT 100")
 List<Long> unsettled(long after);
 @Update("UPDATE coupon_claim_request SET redis_settled_generation=#{generation} WHERE id=#{id} AND status=#{status} AND reservation_id=#{reservation} AND (redis_settled_generation IS NULL OR redis_settled_generation<>#{generation})")
 int checkpoint(@Param("id") long id,@Param("generation") long generation,@Param("status") String status,@Param("reservation") String reservation);
}
