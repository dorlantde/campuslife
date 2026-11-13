package com.campuslife.order.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description="本人订单评价事实，隐藏后本人仍可读；不附加账号资料")
public record OwnReviewView(
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED,pattern="[1-9][0-9]{0,18}") String id,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED,pattern="[1-9][0-9]{0,18}") String orderId,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED,minimum="1",maximum="5") int rating,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED,minLength=1,maxLength=1000) String content,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED,allowableValues={"VISIBLE","HIDDEN"}) String visibility,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED,description="评价创建时间，UTC") Instant createdAt) {}
