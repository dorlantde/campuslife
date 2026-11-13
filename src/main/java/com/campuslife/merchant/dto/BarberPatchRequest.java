package com.campuslife.merchant.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record BarberPatchRequest(
        @NotNull @Schema(type = "string", pattern = "^(0|[1-9][0-9]{0,18})$", example = "0", description = "理发师版本，与绑定接口共用，不是店铺或服务版本") JsonNode expectedVersion,
        @Schema(type = "string", example = "模拟理发师林老师", description = "省略保持，null 拒绝；去首尾空白后 1～40 码点") JsonNode displayName,
        @Schema(type = "boolean", example = "true", description = "省略保持，必须是 JSON 布尔值；启用不等于已经可预约") JsonNode enabled) {}
