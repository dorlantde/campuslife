package com.campuslife.auth.service;

import com.campuslife.common.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.http.HttpStatus;

/** Shared normalization and validation; passwords are checked without rewriting their contents. */
public final class RegistrationPolicy {
    private RegistrationPolicy() {
    }

    public static String username(String value) {
        if (value == null || !value.strip().matches("[A-Za-z0-9_]{4,32}")) {
            throw invalid("用户名须为 4～32 位英文字母、数字或下划线");
        }
        return value.strip().toLowerCase(Locale.ROOT);
    }

    public static String nickname(String value) {
        if (value == null || !StandardCharsets.UTF_8.newEncoder().canEncode(value)) {
            throw invalid("昵称须为有效的 Unicode 文本");
        }
        String normalized = value.strip();
        int characters = normalized.codePointCount(0, normalized.length());
        if (characters < 1 || characters > 40) {
            throw invalid("昵称须为 1～40 字符");
        }
        return normalized;
    }

    public static void password(String value) {
        if (value == null || !StandardCharsets.UTF_8.newEncoder().canEncode(value)
                || value.codePointCount(0, value.length()) < 8
                || value.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw invalid("密码至少 8 字符，UTF-8 编码不得超过 72 字节");
        }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
    }
}
