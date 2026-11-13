package com.campuslife.order.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description="授权范围内的订单摘要，供学生本人及商家本店列表复用；学生和商家均支持BOOKING/PRINT；名称、金额及明细均为下单快照")
public record OrderSummary(
        String id, String orderNo,
        @Schema(allowableValues={"BOOKING","PRINT"},description="学生本人及商家本店的两类订单") String orderType,
        @Schema(allowableValues={"PENDING_PAYMENT","PENDING_ACCEPTANCE","ACCEPTED","IN_SERVICE","COMPLETED","CLOSED"}) String status,
        @Schema(allowableValues={"NOT_PAID","PAID","REFUNDED"}) String paymentStatus,
        String shopId, String shopName, String serviceName,
        @Schema(description="冻结应付金额，整数分；退款后仍保留原金额") long payableAmount,
        @Schema(example="CNY") String currency, Instant createdAt,
        @Schema(nullable=true) Instant bookingStartAt, @Schema(nullable=true) Instant bookingEndAt,
        @Schema(type="string",nullable=true,description="PRINT为QUEUED/PRINTING/READY_FOR_PICKUP/PICKED_UP/ABORTED，BOOKING为null") String printingProgress) {}
