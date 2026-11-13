package com.campuslife.merchant.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record ServicePatchRequest(
        @NotNull @Schema(type = "string", pattern = "^(0|[1-9][0-9]{0,18})$", example = "0", description = "服务查询返回的版本，不是店铺版本") JsonNode expectedVersion,
        @Schema(type = "string", example = "模拟标准理发", description = "可省略以保留；1～80 码点，不接受 null") JsonNode name,
        @Schema(type = "string", example = "更新服务介绍", description = "可省略以保留；最多 500 码点，空白清空，不接受 null") JsonNode description,
        @Schema(type = "integer", format = "int64", minimum = "1", maximum = "100000", example = "3500", description = "可省略以保留；严格整数分") JsonNode priceAmount,
        @Schema(type = "integer", nullable = true, minimum = "15", maximum = "240", example = "30", description = "可省略以保留；HAIRCUT 为 15～240 的 15 分钟倍数，只有 PRINT 接受 null") JsonNode durationMinutes,
        @Schema(type = "boolean", example = "true", description = "可省略以保留；严格 JSON 布尔值，控制上下架") JsonNode enabled) {}
