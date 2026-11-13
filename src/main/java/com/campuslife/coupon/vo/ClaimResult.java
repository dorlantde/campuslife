package com.campuslife.coupon.vo;
import java.time.Instant;
import io.swagger.v3.oas.annotations.media.Schema;
@Schema(description="已提交领取结果；PENDING不承诺获券，FAILED不再发行。默认SYNC首次201；受控ASYNC首次PENDING202，按Location查询终态")
public record ClaimResult(String id,String campaignId,@Schema(allowableValues={"PENDING","SUCCEEDED","FAILED"}) String status,
 @Schema(nullable=true) Instant completedAt,@Schema(nullable=true) OwnedCouponView coupon,
 @Schema(nullable=true) Instant acceptedAt,@Schema(nullable=true) Instant deadline,@Schema(nullable=true) String failureCode) {
 public ClaimResult(String id,String campaignId,String status,Instant completedAt,OwnedCouponView coupon){this(id,campaignId,status,completedAt,coupon,null,null,null);}
}
