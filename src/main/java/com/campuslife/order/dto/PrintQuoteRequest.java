package com.campuslife.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;

@Schema(description="本人PDF打印报价；六字段必填且非null，不接收客户端页数/金额或未知字段",
        additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
public record PrintQuoteRequest(
        @Schema(type="string",pattern="^[1-9][0-9]{0,18}$",example="201",requiredMode=Schema.RequiredMode.REQUIRED) JsonNode serviceId,
        @Schema(type="string",pattern="^[1-9][0-9]{0,18}$",example="301",requiredMode=Schema.RequiredMode.REQUIRED) JsonNode fileId,
        @Schema(type="string",allowableValues="A4",example="A4",requiredMode=Schema.RequiredMode.REQUIRED) JsonNode paperSize,
        @Schema(type="string",allowableValues={"BW","COLOR"},example="BW",requiredMode=Schema.RequiredMode.REQUIRED) JsonNode colorMode,
        @Schema(type="string",allowableValues={"SINGLE","DOUBLE"},example="DOUBLE",requiredMode=Schema.RequiredMode.REQUIRED) JsonNode sideMode,
        @Schema(type="integer",minimum="1",maximum="100",example="2",description="严格JSON整数，拒绝字符串/小数/指数形式",requiredMode=Schema.RequiredMode.REQUIRED) JsonNode copies, @Schema(type="string",nullable=true,description="可省略或null表示不用券") JsonNode userCouponId) {
    public PrintQuoteRequest(JsonNode serviceId,JsonNode fileId,JsonNode paperSize,JsonNode colorMode,JsonNode sideMode,JsonNode copies){this(serviceId,fileId,paperSize,colorMode,sideMode,copies,null);}
}
