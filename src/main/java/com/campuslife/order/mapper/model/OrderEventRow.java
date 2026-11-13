package com.campuslife.order.mapper.model;

import com.campuslife.order.vo.OrderEventView;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

public record OrderEventRow(long id,long eventSeq,String action,String fromStatus,String toStatus,
                            String actorType,Long actorId,String reason,LocalDateTime occurredAt) {
    public OrderEventView view() {
        return new OrderEventView(Long.toString(id),Long.toString(eventSeq),action,fromStatus,toStatus,
                actorType,actorId==null?null:Long.toString(actorId),reason,occurredAt.toInstant(ZoneOffset.UTC));
    }
}
