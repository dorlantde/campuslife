package com.campuslife.messaging.model;
import java.time.LocalDateTime;
public record OutboxMessage(String messageId,String eventType,int schemaVersion,String aggregateType,long aggregateId,
 long eventSeq,long recipientId,String payload,String payloadHash,LocalDateTime occurredAt,LocalDateTime createdAt,
 String status,int attempts,LocalDateTime nextAttemptAt,LocalDateTime lastAttemptAt,String leaseToken,
 LocalDateTime leaseUntil,LocalDateTime publishedAt,String lastErrorCode) {}
