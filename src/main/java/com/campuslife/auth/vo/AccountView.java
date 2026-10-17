package com.campuslife.auth.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/** Public response fields only; database IDs and versions remain decimal strings. */
public record AccountView(
        @Schema(description = "账号标识，十进制字符串", example = "101") String id,
        @Schema(example = "demo_student_01") String username,
        @Schema(example = "星海同学") String nickname,
        @Schema(allowableValues = {"STUDENT", "MERCHANT", "ADMIN"}, example = "STUDENT") String role,
        @Schema(allowableValues = {"ACTIVE", "DISABLED"}, example = "ACTIVE") String status,
        @Schema(description = "账号版本；修改昵称时原样回传，不转换为数值", example = "0") String version) {
}
