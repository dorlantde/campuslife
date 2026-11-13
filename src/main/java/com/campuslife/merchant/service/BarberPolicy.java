package com.campuslife.merchant.service;

import java.util.List;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;

final class BarberPolicy {
    private BarberPolicy() {}

    static String name(JsonNode value) { return MerchantServicePolicy.text(value, 1, 40, "理发师姓名"); }

    static List<Long> serviceIds(JsonNode value) {
        if (value == null || !value.isArray() || value.size() > 50) { throw MerchantShopPolicy.invalid("serviceIds 须为 0～50 项的数组"); }
        var ids = new TreeSet<Long>();
        for (var item : value) {
            if (!item.isString()) { throw MerchantShopPolicy.invalid("serviceIds 元素须为服务 ID 字符串"); }
            long id = MerchantServicePolicy.id(item.asString());
            if (!ids.add(id)) { throw MerchantShopPolicy.invalid("serviceIds 不能重复"); }
        }
        return List.copyOf(ids);
    }
}
