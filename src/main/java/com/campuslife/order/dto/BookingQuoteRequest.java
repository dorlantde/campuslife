package com.campuslife.order.dto;
import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;
@Schema(additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
public record BookingQuoteRequest(@Schema(type="string",requiredMode=Schema.RequiredMode.REQUIRED,pattern="^[1-9][0-9]{0,18}$") JsonNode serviceId,@Schema(type="string",nullable=true,description="省略/null不用券") JsonNode userCouponId) {}
