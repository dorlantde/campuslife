package com.campuslife.order.vo;
import java.time.Instant;
import io.swagger.v3.oas.annotations.media.Schema;
@Schema(description="下单时的预约快照；待支付HELD、已付及服务中ACTIVE、未开始关闭RELEASED、完成CONSUMED保留原区间")
public record BookingOrderView(String serviceId, String serviceName, String barberId, String barberName,
 Instant startAt, Instant endAt, int durationMinutes, String occupancyStatus) {}
