package com.campuslife.coupon.vo;
import java.time.Instant;
import io.swagger.v3.oas.annotations.media.Schema;
public record OrderCouponView(String userCouponId,String campaignId,String name,long thresholdAmount,long reductionAmount,
    Instant useStartAt,Instant useEndAt,String sourceCampaignVersion,String state,Instant lockedAt,
    @Schema(nullable=true) Instant usedAt,@Schema(nullable=true) Instant releasedAt) {}
