package com.campuslife.order.dto;
import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;
@Schema(additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
public record BookingRejection(@Schema(type="string",minLength=1,maxLength=300,example="人员临时无法提供服务",
    requiredMode=Schema.RequiredMode.REQUIRED,description="去首尾空白后1～300个Unicode码点") JsonNode reason) {}
