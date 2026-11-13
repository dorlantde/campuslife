package com.campuslife.merchant.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record MerchantApplicationRequest(
        @NotNull @Schema(description = "归一化规则与学生注册相同；所有角色共享唯一用户名", example = "demo_merchant_01") String username,
        @NotNull @Schema(description = "至少 8 码点，UTF-8 最多 72 字节；不修改原内容", example = "DemoPass_123!",
                format = "password", accessMode = Schema.AccessMode.WRITE_ONLY) String password,
        @NotNull @Schema(description = "去首尾空白后 1～40 Unicode 码点", example = "模拟店主") String nickname,
        @NotNull @Schema(type = "string", pattern = "^[1-9][0-9]{0,18}$", description = "GET /shop-categories 返回的启用分类 ID，必须为字符串", example = "1") JsonNode categoryId,
        @NotNull @Schema(description = "去首尾空白后 1～80 Unicode 码点", example = "星海模拟理发店") String shopName,
        @NotNull @Schema(description = "去首尾空白后 1～200 Unicode 码点", example = "虚构校区服务中心 101") String locationText,
        @Schema(description = "去首尾空白后最多 1000 码点，未填或空白存为空字符串", example = "仅用于学习验收的虚构店铺") String description) {
    @Override public String toString() { return "MerchantApplicationRequest[password=REDACTED]"; }
}
