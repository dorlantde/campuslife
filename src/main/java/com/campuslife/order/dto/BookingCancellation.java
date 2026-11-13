package com.campuslife.order.dto;
import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;
@Schema(additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
public record BookingCancellation(@Schema(type="string",minLength=1,maxLength=300,example="设备或人员临时故障，无法履行本次订单",
    requiredMode=Schema.RequiredMode.REQUIRED,description="去首尾空白后1～300个Unicode码点") JsonNode reason) {}
