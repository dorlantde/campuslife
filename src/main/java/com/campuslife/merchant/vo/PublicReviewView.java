package com.campuslife.merchant.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description="公开VISIBLE评价，仅显示评价ID、评分、原文字、作者当前昵称和UTC时间；无学生/订单ID或账号资料")
public record PublicReviewView(
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED,pattern="[1-9][0-9]{0,18}") String reviewId,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED,minimum="1",maximum="5") int rating,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED,minLength=1,maxLength=1000) String content,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED,description="作者当前昵称；修改昵称后历史展示同步变化，停用作者不自动隐藏评价") String reviewerNickname,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED,description="原评价创建时间，UTC") Instant createdAt) {}
