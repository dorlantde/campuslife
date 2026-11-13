package com.campuslife.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;

@Schema(description="从打印报价复制规格、价目版本和金额；不接收页数、单价或未知字段",additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
public record PrintCreate(
    @Schema(type="string",pattern="^[1-9][0-9]{0,18}$",requiredMode=Schema.RequiredMode.REQUIRED) JsonNode serviceId,
    @Schema(type="string",pattern="^[1-9][0-9]{0,18}$",requiredMode=Schema.RequiredMode.REQUIRED) JsonNode fileId,
    @Schema(type="string",allowableValues="A4",requiredMode=Schema.RequiredMode.REQUIRED) JsonNode paperSize,
    @Schema(type="string",allowableValues={"BW","COLOR"},requiredMode=Schema.RequiredMode.REQUIRED) JsonNode colorMode,
    @Schema(type="string",allowableValues={"SINGLE","DOUBLE"},requiredMode=Schema.RequiredMode.REQUIRED) JsonNode sideMode,
    @Schema(type="integer",minimum="1",maximum="100",requiredMode=Schema.RequiredMode.REQUIRED) JsonNode copies,
    @Schema(type="string",pattern="^(0|[1-9][0-9]{0,18})$",requiredMode=Schema.RequiredMode.REQUIRED) JsonNode expectedPriceVersion,
    @Schema(type="integer",format="int64",minimum="1",maximum="5000000000",description="整数分，拒绝小数/指数/字符串",requiredMode=Schema.RequiredMode.REQUIRED) JsonNode expectedPayableAmount,
    @Schema(type="string",nullable=true,maxLength=300) JsonNode note, @Schema(type="string",nullable=true,description="可省略或null表示不用券") JsonNode userCouponId) {
    public PrintCreate(JsonNode serviceId,JsonNode fileId,JsonNode paperSize,JsonNode colorMode,JsonNode sideMode,JsonNode copies,JsonNode expectedPriceVersion,JsonNode expectedPayableAmount,JsonNode note){this(serviceId,fileId,paperSize,colorMode,sideMode,copies,expectedPriceVersion,expectedPayableAmount,note,null);}
}
