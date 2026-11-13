package com.campuslife.merchant.service;

import com.campuslife.common.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

final class MerchantShopPolicy {
    private MerchantShopPolicy() {}

    static long categoryId(JsonNode value) { return number(value, false, "categoryId"); }
    static long version(JsonNode value) { return number(value, true, "expectedVersion"); }

    private static long number(JsonNode value, boolean zeroAllowed, String field) {
        String pattern = zeroAllowed ? "0|[1-9][0-9]{0,18}" : "[1-9][0-9]{0,18}";
        if (value == null || !value.isString() || !value.asString().matches(pattern)) {
            throw invalid(field + " 须为" + (zeroAllowed ? "非负" : "正") + "十进制整数字符串");
        }
        try { return Long.parseLong(value.asString()); }
        catch (NumberFormatException error) { throw invalid(field + " 超出 BIGINT 范围"); }
    }

    static String text(JsonNode value, int min, int max, String field) {
        if (min == 0 && (value == null || value.isNull())) { return ""; }
        if (value == null || !value.isString()) { throw invalid(field + "须为文本字符串"); }
        return text(value.asString(), min, max, field);
    }

    static String text(String value, int min, int max, String field) {
        if (value == null || !StandardCharsets.UTF_8.newEncoder().canEncode(value)) { throw invalid(field + "须为有效 Unicode 文本"); }
        String normalized = value.strip();
        int count = normalized.codePointCount(0, normalized.length());
        if (count < min || count > max) { throw invalid(field + "长度须为 " + min + "～" + max + " 字符"); }
        return normalized;
    }

    static BusinessException invalid(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
    }
}
