package com.campuslife.merchant.service;

import com.campuslife.merchant.dto.PrintPriceRuleRequest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

final class PrintPricePolicy {
    private PrintPricePolicy() {}

    static List<Rule> normalize(List<PrintPriceRuleRequest> values) {
        if (values == null || values.isEmpty() || values.size() > 4) { throw MerchantShopPolicy.invalid("rules 须包含 1～4 条规格"); }
        var rules = new ArrayList<Rule>();
        var keys = new HashSet<String>();
        for (var value : values) {
            if (value == null) { throw MerchantShopPolicy.invalid("规则不能为 null"); }
            choice(value.paperSize(), Set.of("A4"), "paperSize");
            String color = choice(value.colorMode(), Set.of("BW", "COLOR"), "colorMode");
            String side = choice(value.sideMode(), Set.of("SINGLE", "DOUBLE"), "sideMode");
            int amount = MerchantServicePolicy.amount(value.pricePerImpression(), "pricePerImpression");
            boolean enabled = MerchantServicePolicy.enabled(value.enabled());
            var version = value.expectedVersion();
            if (version == null) { throw MerchantShopPolicy.invalid("expectedVersion 必须提供，新规格显式传 null"); }
            Long expected = version.isNull() ? null : MerchantShopPolicy.version(version);
            var rule = new Rule(color, side, amount, enabled, expected);
            if (!keys.add(rule.key())) { throw MerchantShopPolicy.invalid("同一次请求不能重复提交规格"); }
            rules.add(rule);
        }
        rules.sort(Comparator.comparing(Rule::color).thenComparingInt(rule -> "SINGLE".equals(rule.side()) ? 0 : 1));
        return List.copyOf(rules);
    }

    private static String choice(JsonNode value, Set<String> allowed, String field) {
        if (value == null || !value.isString() || !allowed.contains(value.asString())) {
            throw MerchantShopPolicy.invalid(field + " 须为合法规格枚举");
        }
        return value.asString();
    }

    record Rule(String color, String side, long amount, boolean enabled, Long expected) {
        String key() { return color + "/" + side; }
    }
}
