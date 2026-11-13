package com.campuslife.admin.vo;

import com.campuslife.merchant.vo.MerchantShopView;
import java.util.List;

public record AdminShopPage(List<MerchantShopView> items, int page, int size, long total, long totalPages) {
}
