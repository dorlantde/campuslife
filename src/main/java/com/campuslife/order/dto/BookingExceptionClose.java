package com.campuslife.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;

@Schema(additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
public record BookingExceptionClose(
    @Schema(type="string",minLength=1,maxLength=300,example="已向双方核实，无法继续履约，按V1规则全额模拟退款",
        requiredMode=Schema.RequiredMode.REQUIRED,description="管理员核实结论；去首尾空白后1～300个Unicode码点") JsonNode reason,
    @Schema(type="string",pattern="^(0|[1-9][0-9]{0,18})$",example="2",requiredMode=Schema.RequiredMode.REQUIRED,
        description="查看订单时取得的非负BIGINT版本字符串；最大9223372036854775807") JsonNode expectedVersion) {}
