package com.campuslife.order.vo;
import java.time.Instant;
public record RefundView(String refundNo,long amount,String status,Instant refundedAt) {}
