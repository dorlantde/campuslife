package com.campuslife.order.vo;
import java.time.Instant;
import io.swagger.v3.oas.annotations.media.Schema;
@Schema(description="授权可见订单；BOOKING支持模拟支付、商家履约及允许窗口/管理员核实退款；PRINT支持创建/支付、待接单退款、商家查询/确认和接单后未开始时取消及冻结明细；打印受控内容访问、开始与待取件已接入，学生确认取件及管理员PRINT异常核实退款已接入，学生本人评价已接入，公开评价与真实统计已接入，管理员隐藏后续开发")
public record OrderDetail(String id, String orderNo, @Schema(example="BOOKING") String orderType,
 @Schema(example="PENDING_PAYMENT",description="待支付、待接单、已接单、服务中、完成或关闭") String status,
 @Schema(example="NOT_PAID") String paymentStatus,
 String shopId, String shopName, String serviceName, long originalAmount, long discountAmount, long payableAmount,
 @Schema(example="CNY") String currency, Instant createdAt, Instant paymentDeadline,
 @Schema(nullable=true) Instant acceptanceDeadline, @Schema(nullable=true) Instant paidAt,
 @Schema(nullable=true) Instant acceptedAt, @Schema(nullable=true) Instant startedAt,
 @Schema(nullable=true) Instant completedAt, @Schema(nullable=true) Instant closedAt,
 @Schema(nullable=true) String closeReason, @Schema(nullable=true) String closeNote,
 @Schema(nullable=true,maxLength=300) String note, String version, @Schema(description="是否存在任何可见性评价；由评价表派生，不递增订单版本") boolean reviewed, @Schema(nullable=true) BookingOrderView booking,
 @Schema(nullable=true,description="PRINT冻结打印明细；BOOKING为null") PrintingOrderView printing,
 @Schema(nullable=true,description="成功支付事实；退款后仍保留") PaymentView payment,
 @Schema(nullable=true,description="全额成功退款事实；尚未退款为 null") RefundView refund, @Schema(nullable=true,description="冻结订单优惠；历史无券订单为null") com.campuslife.coupon.vo.OrderCouponView coupon) {}
