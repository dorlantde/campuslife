package com.campuslife.order.vo;
import java.time.Instant;
public record PaymentView(String paymentNo,long amount,String status,Instant paidAt) {}
