package com.campuslife.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record ShopRejectionRequest(@Schema(type = "string", example = "0", pattern = "^(0|[1-9][0-9]{0,18})$",
        requiredMode = Schema.RequiredMode.REQUIRED) JsonNode expectedVersion,
        @Schema(type = "string", example = "请补充准确的店铺位置", requiredMode = Schema.RequiredMode.REQUIRED,
                description = "去首尾空白后 1～300 Unicode 码点") JsonNode reason) {
}
