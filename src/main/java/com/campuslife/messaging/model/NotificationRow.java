package com.campuslife.messaging.model;
import java.time.LocalDateTime;
public record NotificationRow(long id,String sourceMessageId,long recipientId,String type,long orderId,String orderNo,String shopName,LocalDateTime occurredAt,LocalDateTime createdAt,LocalDateTime readAt) {}
