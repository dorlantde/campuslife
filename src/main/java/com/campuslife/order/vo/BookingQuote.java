package com.campuslife.order.vo;
import io.swagger.v3.oas.annotations.media.Schema;
@Schema(description="理发只读报价，不预留时段或锁价")
public record BookingQuote(String serviceId,String shopId,String serviceVersion,long originalAmount,long discountAmount,long payableAmount,String currency,@Schema(nullable=true) String userCouponId) {}
