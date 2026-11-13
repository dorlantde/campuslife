package com.campuslife.messaging.mapper;
import com.campuslife.messaging.model.OutboxMessage;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.*;
public interface OutboxMapper {
 @Insert("""
 INSERT INTO message_outbox(message_id,event_type,schema_version,aggregate_type,aggregate_id,event_seq,recipient_id,payload,payload_hash,occurred_at,created_at,status,next_attempt_at)
 VALUES(#{id},#{type},1,#{aggregate},#{order},#{seq},#{recipient},#{payload},#{hash},#{now},#{now},'READY',#{now})
 """) int append(@Param("id") String id,@Param("type") String type,@Param("aggregate") String aggregate,
   @Param("order") long order,@Param("seq") long seq,@Param("recipient") long recipient,@Param("payload") String payload,@Param("hash") String hash,@Param("now") LocalDateTime now);
 @Select("SELECT * FROM message_outbox WHERE message_id=#{id}") OutboxMessage find(String id);
 @Select("""
 SELECT * FROM message_outbox WHERE created_at<=#{now} AND
 ((status='READY' AND next_attempt_at<=#{now}) OR (status='PUBLISHING' AND lease_until<=#{now}))
 ORDER BY created_at,message_id LIMIT 1 FOR UPDATE SKIP LOCKED
 """) OutboxMessage lockDue(LocalDateTime now);
 @Update("""
 UPDATE message_outbox SET status='PUBLISHING',attempts=attempts+1,last_attempt_at=#{now},next_attempt_at=NULL,
 lease_token=#{token},lease_until=#{until},last_error_code=NULL WHERE message_id=#{id} AND attempts<20
 """) int claim(@Param("id") String id,@Param("token") String token,@Param("now") LocalDateTime now,@Param("until") LocalDateTime until);
 @Update("""
 UPDATE message_outbox SET status='DEAD',next_attempt_at=NULL,lease_token=NULL,lease_until=NULL,last_error_code='LEASE_EXHAUSTED'
 WHERE message_id=#{id} AND status='PUBLISHING' AND attempts=20
 """) int exhausted(String id);
 @Update("""
 UPDATE message_outbox SET status=#{status},next_attempt_at=#{next},published_at=#{published},last_error_code=#{error},lease_token=NULL,lease_until=NULL
 WHERE message_id=#{id} AND status='PUBLISHING' AND lease_token=#{token}
 """) int finish(@Param("id") String id,@Param("token") String token,@Param("status") String status,@Param("next") LocalDateTime next,@Param("published") LocalDateTime published,@Param("error") String error);
}
