package com.campuslife.order.dto;
import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;
@Schema(description = "创建待支付预约；身份、结束时刻和金额均由服务端确定，未知字段拒绝", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record BookingCreate(
 @Schema(type="string", example="201",requiredMode=Schema.RequiredMode.REQUIRED) JsonNode serviceId,
 @Schema(type="string", example="301",requiredMode=Schema.RequiredMode.REQUIRED) JsonNode barberId,
 @Schema(type="string",format="date-time",example="2026-10-23T01:00:00Z",requiredMode=Schema.RequiredMode.REQUIRED) JsonNode startAt,
 @Schema(type="string",example="0",requiredMode=Schema.RequiredMode.REQUIRED) JsonNode expectedServiceVersion,
 @Schema(type="integer",minimum="1",maximum="100000",example="3000",requiredMode=Schema.RequiredMode.REQUIRED) JsonNode expectedPayableAmount,
 @Schema(type="string",nullable=true,maxLength=300,example="模拟预约") JsonNode note, @Schema(type="string",nullable=true,description="可省略或null表示不用券") JsonNode userCouponId) {
    public BookingCreate(JsonNode serviceId,JsonNode barberId,JsonNode startAt,JsonNode expectedServiceVersion,JsonNode expectedPayableAmount,JsonNode note){this(serviceId,barberId,startAt,expectedServiceVersion,expectedPayableAmount,note,null);}
}
