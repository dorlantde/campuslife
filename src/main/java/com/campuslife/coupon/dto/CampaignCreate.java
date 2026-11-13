package com.campuslife.coupon.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;

@Schema(description="本店全部服务满减活动完整草稿；八字段必填且非null，未知字段拒绝", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
public record CampaignCreate(
    @Schema(type="string",minLength=1,maxLength=80,example="校园满30减5",requiredMode=Schema.RequiredMode.REQUIRED) JsonNode name,
    @Schema(type="integer",format="int64",minimum="2",maximum="5000000000",example="3000",requiredMode=Schema.RequiredMode.REQUIRED) JsonNode thresholdAmount,
    @Schema(type="integer",format="int64",minimum="1",maximum="4999999999",example="500",description="严格整数分，小于门槛",requiredMode=Schema.RequiredMode.REQUIRED) JsonNode reductionAmount,
    @Schema(type="integer",minimum="1",maximum="100000",example="100",requiredMode=Schema.RequiredMode.REQUIRED) JsonNode totalQuantity,
    @Schema(type="string",format="date-time",example="2026-11-01T00:00:00Z",requiredMode=Schema.RequiredMode.REQUIRED) JsonNode claimStartAt,
    @Schema(type="string",format="date-time",example="2026-11-07T00:00:00Z",requiredMode=Schema.RequiredMode.REQUIRED) JsonNode claimEndAt,
    @Schema(type="string",format="date-time",example="2026-11-01T00:00:00Z",requiredMode=Schema.RequiredMode.REQUIRED) JsonNode useStartAt,
    @Schema(type="string",format="date-time",example="2026-11-10T00:00:00Z",requiredMode=Schema.RequiredMode.REQUIRED) JsonNode useEndAt) {}
