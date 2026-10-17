package com.campuslife.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record PasswordChangeRequest(
        @Schema(description = "当前密码，原样校验", example = "DemoPass_123!", format = "password",
                accessMode = Schema.AccessMode.WRITE_ONLY) @NotNull String currentPassword,
        @Schema(description = "至少 8 个 Unicode 码点，UTF-8 最多 72 字节；不改变内容",
                example = "NewDemoPass_456!", format = "password", accessMode = Schema.AccessMode.WRITE_ONLY)
        @NotNull String newPassword) {
    @Override
    public String toString() { return "PasswordChangeRequest[currentPassword=REDACTED, newPassword=REDACTED]"; }
}
