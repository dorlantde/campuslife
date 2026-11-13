package com.campuslife.merchant.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record ServiceCreateRequest(
        @NotNull @Schema(type = "string", allowableValues = {"HAIRCUT", "PRINT"}, example = "HAIRCUT", description = "必须与本人店铺分类代码匹配") JsonNode serviceType,
        @NotNull @Schema(type = "string", example = "模拟标准理发", description = "去首尾空白后 1～80 Unicode 码点") JsonNode name,
        @Schema(type = "string", example = "仅供学习验收", description = "可省略以保存空字符串，最多 500 码点；不接受 null") JsonNode description,
        @NotNull @Schema(type = "integer", format = "int64", minimum = "1", maximum = "100000", example = "3000", description = "严格整数分；理发固定价，打印仅参考起价") JsonNode priceAmount,
        @NotNull @Schema(type = "integer", nullable = true, minimum = "15", maximum = "240", example = "30", description = "必须提供；HAIRCUT 为 15～240 的 15 分钟倍数，PRINT 须显式 null") JsonNode durationMinutes,
        @Schema(type = "boolean", example = "false", description = "省略默认下架 false；不接受 null、字符串或数字") JsonNode enabled) {}
