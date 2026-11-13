package com.campuslife.merchant.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record ShopPatchRequest(
        @NotNull @Schema(type = "string", pattern = "^(0|[1-9][0-9]{0,18})$", example = "1",
                description = "GET /merchant/shop 返回的店铺版本，须为字符串；即使没有实际变更也校验") JsonNode expectedVersion,
        @Schema(type = "string", description = "可省略；去首尾空白后 1～80 Unicode 码点，不接受 null", example = "星海模拟理发店") JsonNode name,
        @Schema(type = "string", description = "可省略；去首尾空白后 1～200 Unicode 码点，不接受 null", example = "虚构校区服务中心 102") JsonNode locationText,
        @Schema(type = "string", description = "可省略以保留原值；最多 1000 码点，空字符串清空，不接受 null", example = "用于学习验收的虚构店铺") JsonNode description,
        @Schema(type = "boolean", description = "可省略；仅接受 JSON true/false，表示商家营业意愿，接单还需其他条件", example = "true") JsonNode businessEnabled) {}
