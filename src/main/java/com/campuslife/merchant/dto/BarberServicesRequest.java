package com.campuslife.merchant.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record BarberServicesRequest(
        @NotNull @Schema(type = "string", pattern = "^(0|[1-9][0-9]{0,18})$", example = "1", description = "查询返回的理发师版本") JsonNode expectedVersion,
        @NotNull @ArraySchema(maxItems = 50, schema = @Schema(type = "string", pattern = "^[1-9][0-9]{0,18}$", example = "201"),
                arraySchema = @Schema(description = "完整目标集合，不是增量；空数组解除全部绑定，重复 ID 拒绝")) JsonNode serviceIds) {}
