package com.campuslife.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;

@Schema(additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
public record OrderReviewWrite(
        @Schema(type="integer",minimum="1",maximum="5",example="5",requiredMode=Schema.RequiredMode.REQUIRED,
                description="严格JSON整数1～5，不接受字符串、小数或布尔") JsonNode rating,
        @Schema(type="string",minLength=1,maxLength=1000,example="服务顺利完成，沟通清楚。",requiredMode=Schema.RequiredMode.REQUIRED,
                description="strip去首尾空白后1～1000个Unicode码点，内部文字/换行保留") JsonNode content) {}
