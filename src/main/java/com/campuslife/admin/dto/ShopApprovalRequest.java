package com.campuslife.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record ShopApprovalRequest(@Schema(type = "string", example = "0", pattern = "^(0|[1-9][0-9]{0,18})$",
        requiredMode = Schema.RequiredMode.REQUIRED, description = "管理员详情返回的店铺版本，必须为十进制字符串") JsonNode expectedVersion) {
}
