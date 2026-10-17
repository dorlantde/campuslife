package com.campuslife.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record LoginRequest(
        @Schema(description = "注册时的用户名，去首尾空白并转小写", example = "demo_student_01") @NotNull String username,
        @Schema(description = "原样填写密码，不去空白", example = "DemoPass_123!", format = "password",
                accessMode = Schema.AccessMode.WRITE_ONLY) @NotNull String password) {
    @Override
    public String toString() { return "LoginRequest[password=REDACTED]"; }
}
