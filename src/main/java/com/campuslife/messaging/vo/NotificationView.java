package com.campuslife.messaging.vo;
import java.time.Instant;
import io.swagger.v3.oas.annotations.media.Schema;
public record NotificationView(String id,String type,String orderId,String orderNo,String shopName,Instant occurredAt,Instant createdAt,@Schema(nullable=true) Instant readAt,String title,String message) {}
