package com.campuslife.coupon.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description="本店活动与首次发布/停止记录；金额整数分，时间UTC，配置版本独立于店铺及累计发行issuedCount")
public record CampaignView(String id,String shopId,String name,
    @Schema(format="int64") long thresholdAmount,@Schema(format="int64") long reductionAmount,
    int totalQuantity,int issuedCount,
    @Schema(allowableValues={"DRAFT","PUBLISHED","STOPPED"}) String status,
    Instant claimStartAt,Instant claimEndAt,Instant useStartAt,Instant useEndAt,String version,
    Instant createdAt,Instant updatedAt,
    @Schema(nullable=true) Instant publishedAt,@Schema(nullable=true) String publishedBy,
    @Schema(nullable=true) String publishExpectedVersion,
    @Schema(nullable=true) Instant stoppedAt,@Schema(nullable=true) String stoppedBy,
    @Schema(nullable=true) String stopExpectedVersion) {}
