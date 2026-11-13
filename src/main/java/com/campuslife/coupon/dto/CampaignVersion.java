package com.campuslife.coupon.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;

@Schema(description="发布/停止活动，只接受活动原版本",additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
public record CampaignVersion(
    @Schema(type="string",pattern="^(0|[1-9][0-9]{0,18})$",example="0",requiredMode=Schema.RequiredMode.REQUIRED) JsonNode expectedVersion) {}
