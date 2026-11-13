package com.campuslife.order.vo;
import java.time.Instant;
import io.swagger.v3.oas.annotations.media.Schema;
@Schema(description="模拟支付成功事实；重放不延长接单期限，金额单位为分")
public record PaymentResult(String orderId,String orderStatus,String paymentStatus,String paymentNo,long amount,Instant paidAt,Instant acceptanceDeadline) {}
