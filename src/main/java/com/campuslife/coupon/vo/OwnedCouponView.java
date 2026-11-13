package com.campuslife.coupon.vo;
import java.time.Instant;
import io.swagger.v3.oas.annotations.media.Schema;
@Schema(description="本人持有券冻结规则；AVAILABLE/LOCKED/USED/EXPIRED；空闲券到期由读取时刻派生，GET不写数据库")
public record OwnedCouponView(String id,String campaignId,String shopId,String name,long thresholdAmount,long reductionAmount,
    Instant useStartAt,Instant useEndAt,String sourceCampaignVersion,Instant claimedAt,String state) {}
