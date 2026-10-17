package com.campuslife.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;

/** Keep missing, null and non-string JSON values distinct for PATCH semantics. */
@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record ProfileUpdateRequest(
        @Schema(implementation = String.class, description = "去首尾空白后 1～40 个 Unicode 码点；遗漏不改，null 拒绝",
                example = "星海新昵称😀", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        JsonNode nickname,
        @Schema(implementation = String.class, description = "原样回传 GET /me 的 version 字符串，版本过期返回 409",
                example = "0", pattern = "^(0|[1-9][0-9]{0,18})$", requiredMode = Schema.RequiredMode.REQUIRED)
        JsonNode expectedVersion) {
}
