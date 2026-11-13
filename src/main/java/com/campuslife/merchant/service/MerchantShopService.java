package com.campuslife.merchant.service;

import com.campuslife.auth.security.AuthPrincipal;
import com.campuslife.common.exception.BusinessException;
import com.campuslife.merchant.mapper.ShopMapper;
import com.campuslife.merchant.mapper.ShopCategoryMapper;
import com.campuslife.merchant.mapper.BusinessHourMapper;
import java.util.List;
import com.campuslife.merchant.vo.MerchantShopView;
import com.campuslife.merchant.vo.ShopCategoryView;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("mysql")
public class MerchantShopService {
    private final ShopMapper shops;
    private final ShopCategoryMapper categories;
    private final BusinessHourMapper hours;
    public MerchantShopService(ShopMapper shops, ShopCategoryMapper categories, BusinessHourMapper hours) { this.shops = shops; this.categories = categories; this.hours = hours; }

    public List<ShopCategoryView> categories() { return categories.enabled(); }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MerchantShopView own(AuthPrincipal principal) {
        if (!"MERCHANT".equals(principal.account().role())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ROLE_FORBIDDEN", "仅商家可查看本人店铺");
        }
        var row = shops.findOwn(principal.accountId());
        if (row == null) { throw new BusinessException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "本人店铺不存在"); }
        return new MerchantShopView(Long.toString(row.id()), row.name(),
                new ShopCategoryView(Long.toString(row.categoryId()), row.categoryCode(), row.categoryName()),
                row.locationText(), row.description(), row.campusCode(), row.approvalStatus(), row.approvalReason(),
                row.businessEnabled(), row.memberStatus(), Long.toString(row.version()), hours.views(row.id()));
    }
}
