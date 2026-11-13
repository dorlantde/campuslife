package com.campuslife.merchant.service;

import java.util.Set;
import tools.jackson.databind.JsonNode;

final class MerchantServicePolicy {
    private MerchantServicePolicy() {}

    static String type(JsonNode value) {
        if (value == null || !value.isString() || !Set.of("HAIRCUT", "PRINT").contains(value.asString())) {
            throw MerchantShopPolicy.invalid("serviceType 须为 HAIRCUT 或 PRINT");
        }
        return value.asString();
    }

    static String text(JsonNode value, int min, int max, String field) {
        if (value == null || !value.isString()) { throw MerchantShopPolicy.invalid(field + "须为字符串，不能为 null"); }
        return MerchantShopPolicy.text(value.asString(), min, max, field);
    }

    static int amount(JsonNode value) { return amount(value, "priceAmount"); }
    static int amount(JsonNode value, String field) { return integer(value, 1, 100000, field); }

    static Integer duration(String type, JsonNode value) {
        if ("PRINT".equals(type)) {
            if (value == null || !value.isNull()) { throw MerchantShopPolicy.invalid("PRINT 的 durationMinutes 须显式为 null"); }
            return null;
        }
        int minutes = integer(value, 15, 240, "durationMinutes");
        if (minutes % 15 != 0) { throw MerchantShopPolicy.invalid("理发时长须为 15 分钟倍数"); }
        return minutes;
    }

    static boolean enabled(JsonNode value) {
        if (value == null || !value.isBoolean()) { throw MerchantShopPolicy.invalid("enabled 须为 JSON 布尔值，不能为 null"); }
        return value.asBoolean();
    }

    private static int integer(JsonNode value, int min, int max, String field) {
        if (value == null || !value.isIntegralNumber() || !value.toString().matches("[0-9]{1,6}")) {
            throw MerchantShopPolicy.invalid(field + " 须为范围内的 JSON 整数，不接受小数或数字字符串");
        }
        int result = Integer.parseInt(value.toString());
        if (result < min || result > max) { throw MerchantShopPolicy.invalid(field + " 须为 " + min + "～" + max); }
        return result;
    }

    static long id(String value) {
        return id(value, "serviceId");
    }

    static long id(String value, String field) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) { throw MerchantShopPolicy.invalid(field + " 须为正十进制整数字符串"); }
        try { return Long.parseLong(value); }
        catch (NumberFormatException error) { throw MerchantShopPolicy.invalid(field + " 超出 BIGINT 范围"); }
    }
}
