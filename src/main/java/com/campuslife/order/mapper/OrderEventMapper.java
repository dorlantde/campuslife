package com.campuslife.order.mapper;
import com.campuslife.order.mapper.model.OrderEventRow;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;
public interface OrderEventMapper {
    @Select("SELECT id,event_seq,action,from_status,to_status,actor_type,actor_id,reason,occurred_at FROM order_event WHERE order_id=#{id} ORDER BY event_seq ASC")
    List<OrderEventRow> listByOrder(long id);
    record LastEvent(long eventSeq,String action,String fromStatus,String toStatus,String actorType,Long actorId,String reason,LocalDateTime occurredAt) {}
    @Select("SELECT event_seq,action,from_status,to_status,actor_type,actor_id,reason,occurred_at FROM order_event WHERE order_id=#{id} ORDER BY event_seq DESC LIMIT 1")
    LastEvent latest(long id);
    @Insert("INSERT INTO order_event(order_id,event_seq,action,from_status,to_status,actor_type,actor_id,reason,occurred_at) VALUES (#{id},#{seq},#{action},#{from},#{to},#{actor},#{actorId},#{reason},#{now})")
    int append(@Param("id") long id,@Param("seq") long seq,@Param("action") String action,@Param("from") String from,
               @Param("to") String to,@Param("actor") String actor,@Param("actorId") Long actorId,@Param("reason") String reason,@Param("now") LocalDateTime now);
}
