package com.campuslife.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** Registration input. Never include the raw password in diagnostic output. */
@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record RegisterRequest(
        @Schema(description = "去首尾空白、转小写后为 4～32 位字母、数字或下划线", example = "demo_student_01")
        @NotNull String username,
        @Schema(description = "至少 8 个 Unicode 码点，UTF-8 最多 72 字节；不去空白、不改大小写",
                example = "DemoPass_123!", format = "password", accessMode = Schema.AccessMode.WRITE_ONLY)
        @NotNull String password,
        @Schema(description = "去首尾空白后为 1～40 个 Unicode 码点", example = "星海同学")
        @NotNull String nickname) {
    @Override
    public String toString() {
        return "RegisterRequest[password=REDACTED]";
    }
}
