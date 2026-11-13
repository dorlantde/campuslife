package com.campuslife.order.mapper.model;

import com.campuslife.order.vo.OrderSummary;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

public record OrderSummaryRow(long id,String orderNo,String orderType,String status,String paymentStatus,
        long shopId,String shopName,String serviceName,long payableAmount,String currency,LocalDateTime createdAt,
        LocalDateTime bookingStartAt,LocalDateTime bookingEndAt,String printingProgress) {
    public OrderSummary view() {
        return new OrderSummary(Long.toString(id),orderNo,orderType,status,paymentStatus,Long.toString(shopId),
                shopName,serviceName,payableAmount,currency,createdAt.toInstant(ZoneOffset.UTC),
                bookingStartAt==null?null:bookingStartAt.toInstant(ZoneOffset.UTC),bookingEndAt==null?null:bookingEndAt.toInstant(ZoneOffset.UTC),printingProgress);
    }
}
