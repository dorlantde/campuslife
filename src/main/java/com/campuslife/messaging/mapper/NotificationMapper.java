package com.campuslife.messaging.mapper;
import com.campuslife.messaging.model.NotificationRow;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;
public interface NotificationMapper {
 @Insert("INSERT INTO message_consumption(consumer_name,message_id,payload_hash,processed_at) VALUES ('notification-v1',#{id},#{hash},#{now}) ON DUPLICATE KEY UPDATE message_id=message_id")
 int receipt(@Param("id") String id,@Param("hash") String hash,@Param("now") LocalDateTime now);
 @Select("SELECT payload_hash FROM message_consumption WHERE consumer_name='notification-v1' AND message_id=#{id} FOR UPDATE") String receiptHash(String id);
 @Select("SELECT * FROM notification WHERE source_message_id=#{id}") NotificationRow source(String id);
 @Insert("INSERT INTO notification(source_message_id,recipient_id,type,order_id,order_no,shop_name,occurred_at,created_at) VALUES (#{id},#{recipient},#{type},#{order},#{number},#{shop},#{occurred},#{now})")
 int create(@Param("id") String id,@Param("recipient") long recipient,@Param("type") String type,@Param("order") long order,@Param("number") String number,@Param("shop") String shop,@Param("occurred") LocalDateTime occurred,@Param("now") LocalDateTime now);
 String FILTER=" WHERE recipient_id=#{owner} AND created_at>#{cutoff} AND (#{read} IS NULL OR (read_at IS NOT NULL)=#{read}) ";
 @Select("SELECT COUNT(*) FROM notification"+FILTER) long count(@Param("owner") long owner,@Param("cutoff") LocalDateTime cutoff,@Param("read") Boolean read);
 @Select("SELECT * FROM notification"+FILTER+" ORDER BY created_at DESC,id DESC LIMIT #{size} OFFSET #{offset}") List<NotificationRow> page(@Param("owner") long owner,@Param("cutoff") LocalDateTime cutoff,@Param("read") Boolean read,@Param("size") int size,@Param("offset") long offset);
 @Select("SELECT * FROM notification WHERE id=#{id} AND recipient_id=#{owner} FOR UPDATE") NotificationRow lockOwn(@Param("id") long id,@Param("owner") long owner);
 @Update("UPDATE notification SET read_at=#{now} WHERE id=#{id} AND read_at IS NULL") int mark(@Param("id") long id,@Param("now") LocalDateTime now);
}
