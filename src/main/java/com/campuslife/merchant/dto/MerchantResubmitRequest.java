package com.campuslife.merchant.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record MerchantResubmitRequest(
        @NotNull @Schema(type = "string", pattern = "^(0|[1-9][0-9]{0,18})$", example = "1",
                description = "GET /merchant/shop 返回的店铺版本，须为字符串") JsonNode expectedVersion,
        @NotNull @Schema(type = "string", pattern = "^[1-9][0-9]{0,18}$", example = "1",
                description = "启用分类 ID，须为字符串") JsonNode categoryId,
        @NotNull @Schema(type = "string", description = "去首尾空白后 1～80 Unicode 码点", example = "星海模拟理发店") JsonNode shopName,
        @NotNull @Schema(type = "string", description = "去首尾空白后 1～200 Unicode 码点", example = "虚构校区服务中心 102") JsonNode locationText,
        @Schema(type = "string", nullable = true, description = "去首尾空白后最多 1000 码点，未填或 null 统一为空字符串", example = "补充后的虚构店铺介绍") JsonNode description) {}
