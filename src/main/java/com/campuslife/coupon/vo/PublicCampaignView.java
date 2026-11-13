package com.campuslife.coupon.vo;
import java.time.Instant;
public record PublicCampaignView(String id,String shopId,String name,long thresholdAmount,long reductionAmount,int totalQuantity,int issuedCount,int remainingQuantity,
    Instant claimStartAt,Instant claimEndAt,Instant useStartAt,Instant useEndAt) {}
