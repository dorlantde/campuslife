package com.campuslife.merchant.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record PrintPriceRuleRequest(
        @NotNull @Schema(type = "string", allowableValues = "A4", example = "A4") JsonNode paperSize,
        @NotNull @Schema(type = "string", allowableValues = {"BW", "COLOR"}, example = "BW") JsonNode colorMode,
        @NotNull @Schema(type = "string", allowableValues = {"SINGLE", "DOUBLE"}, example = "SINGLE") JsonNode sideMode,
        @NotNull @Schema(type = "integer", format = "int64", minimum = "1", maximum = "100000", example = "20",
                description = "严格 JSON 整数分/印刷面；双面不自动半价") JsonNode pricePerImpression,
        @NotNull @Schema(type = "boolean", example = "true", description = "必须显式提供，不接受 null、数字或字符串") JsonNode enabled,
        @NotNull @Schema(type = "string", nullable = true, pattern = "^(0|[1-9][0-9]{0,18})$", example = "0",
                description = "必须提供：新规格显式 null，已有规格传该价目查询返回的版本，不是服务或店铺版本") JsonNode expectedVersion) {}
