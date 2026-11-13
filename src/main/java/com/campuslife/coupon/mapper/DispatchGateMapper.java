package com.campuslife.coupon.mapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;
public interface DispatchGateMapper {
 String COLUMNS="campaign_id,mode,phase,generation,control_version,rebuild_token,lease_until,redis_run_id,resume_phase";
 record Gate(long campaignId,String mode,String phase,long generation,long controlVersion,String rebuildToken,
             LocalDateTime leaseUntil,String redisRunId,String resumePhase) {}
 @Insert("INSERT INTO coupon_dispatch_gate(campaign_id) VALUES (#{campaign})") int create(long campaign);
 @Select("SELECT "+COLUMNS+" FROM coupon_dispatch_gate WHERE campaign_id=#{campaign} FOR SHARE") Gate share(long campaign);
 @Select("SELECT "+COLUMNS+" FROM coupon_dispatch_gate WHERE campaign_id=#{campaign} FOR UPDATE") Gate lock(long campaign);
 @Select("SELECT "+COLUMNS+" FROM coupon_dispatch_gate WHERE campaign_id=#{campaign}") Gate find(long campaign);
 @Select("SELECT "+COLUMNS+" FROM coupon_dispatch_gate WHERE mode='ASYNC' AND campaign_id>#{after} ORDER BY campaign_id LIMIT 100") List<Gate> page(long after);
 @Update("UPDATE coupon_dispatch_gate SET phase='REBUILDING',generation=#{generation},control_version=control_version+1,rebuild_token=#{token},lease_until=#{until},resume_phase=#{resume} WHERE campaign_id=#{id} AND mode='ASYNC' AND control_version<9223372036854775807")
 int begin(@Param("id") long id,@Param("generation") long generation,@Param("token") String token,@Param("until") LocalDateTime until,@Param("resume") String resume);
 @Update("UPDATE coupon_dispatch_gate SET lease_until=#{until} WHERE campaign_id=#{id} AND phase='REBUILDING' AND generation=#{generation} AND rebuild_token=#{token} AND lease_until>#{now}")
 int renew(@Param("id") long id,@Param("generation") long generation,@Param("token") String token,@Param("now") LocalDateTime now,@Param("until") LocalDateTime until);
 @Update("UPDATE coupon_dispatch_gate SET phase=resume_phase,resume_phase=NULL,rebuild_token=NULL,lease_until=NULL,redis_run_id=#{run},control_version=control_version+1 WHERE campaign_id=#{id} AND phase='REBUILDING' AND generation=#{generation} AND rebuild_token=#{token} AND lease_until>#{now} AND control_version<9223372036854775807")
 int finish(@Param("id") long id,@Param("generation") long generation,@Param("token") String token,@Param("run") String run,@Param("now") LocalDateTime now);
}
