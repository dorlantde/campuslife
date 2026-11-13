package com.campuslife.coupon.dto;
import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;
@Schema(description="领取本店满减券；默认SYNC，受控ASYNC持久受理202；只接受campaignId",additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
public record CouponClaim(@Schema(type="string",example="1",pattern="^[1-9][0-9]{0,18}$",requiredMode=Schema.RequiredMode.REQUIRED) JsonNode campaignId) {}
