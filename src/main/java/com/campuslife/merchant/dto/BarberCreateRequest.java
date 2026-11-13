package com.campuslife.merchant.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record BarberCreateRequest(
        @NotNull @Schema(type = "string", example = "模拟理发师小林", description = "去首尾空白后 1～40 Unicode 码点，允许重名") JsonNode displayName,
        @NotNull @ArraySchema(maxItems = 50, schema = @Schema(type = "string", pattern = "^[1-9][0-9]{0,18}$", example = "201"),
                arraySchema = @Schema(description = "必填，0～50 个不同本店 HAIRCUT 服务 ID；空数组允许，可以绑定下架服务")) JsonNode serviceIds) {}
