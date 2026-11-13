package com.campuslife.merchant.vo;

import io.swagger.v3.oas.annotations.media.Schema;

public record ShopCategoryView(@Schema(type = "string", example = "1") String id, String code, String name) {
}
