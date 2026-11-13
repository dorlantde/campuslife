package com.campuslife.merchant.vo;

import io.swagger.v3.oas.annotations.media.Schema;

public record PrintPriceView(
        @Schema(type = "string", example = "301") String id,
        @Schema(allowableValues = "A4", example = "A4") String paperSize,
        @Schema(allowableValues = {"BW", "COLOR"}, example = "BW") String colorMode,
        @Schema(allowableValues = {"SINGLE", "DOUBLE"}, example = "SINGLE") String sideMode,
        @Schema(minimum = "1", maximum = "100000", example = "20", description = "分/印刷面") long pricePerImpression,
        boolean enabled,
        @Schema(type = "string", example = "0", description = "此价目规则独立版本") String version) {}
