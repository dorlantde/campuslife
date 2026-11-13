package com.campuslife.merchant.service;

import com.campuslife.auth.mapper.AccountMapper;
import com.campuslife.auth.mapper.AuthSessionMapper;
import com.campuslife.auth.mapper.model.AuthSessionState;
import com.campuslife.auth.security.AuthPrincipal;
import com.campuslife.auth.security.TokenService;
import com.campuslife.common.exception.BusinessException;
import com.campuslife.merchant.dto.MerchantResubmitRequest;
import com.campuslife.merchant.mapper.ShopCategoryMapper;
import com.campuslife.merchant.mapper.ShopMapper;
import com.campuslife.merchant.mapper.ShopMemberMapper;
import com.campuslife.merchant.vo.MerchantShopView;
import java.time.Clock;
import java.time.ZoneOffset;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("mysql")
public class MerchantResubmissionService {
    private final AccountMapper accounts;
    private final AuthSessionMapper sessions;
    private final ShopCategoryMapper categories;
    private final ShopMapper shops;
    private final ShopMemberMapper members;
    private final MerchantShopService views;
    private final Clock clock;

    public MerchantResubmissionService(AccountMapper accounts, AuthSessionMapper sessions, ShopCategoryMapper categories,
                                      ShopMapper shops, ShopMemberMapper members, MerchantShopService views, Clock clock) {
        this.accounts = accounts; this.sessions = sessions; this.categories = categories;
        this.shops = shops; this.members = members; this.views = views; this.clock = clock;
    }

    @Transactional
    public MerchantShopView resubmit(AuthPrincipal principal, MerchantResubmitRequest request) {
        requireMerchant(principal.account().role());
        long version = MerchantShopPolicy.version(request.expectedVersion());
        long categoryId = MerchantShopPolicy.categoryId(request.categoryId());
        String name = MerchantShopPolicy.text(request.shopName(), 1, 80, "店铺名称");
        String location = MerchantShopPolicy.text(request.locationText(), 1, 200, "店铺位置");
        String description = MerchantShopPolicy.text(request.description(), 0, 1000, "店铺介绍");
        // Account -> session -> category SHARE -> shop -> OWNER. Never lock membership before shop.
        var account = accounts.lockById(principal.accountId());
        if (account == null) { throw TokenService.failure("TOKEN_INVALID", "登录账号不存在"); }
        if (!"ACTIVE".equals(account.getStatus())) { throw new BusinessException(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED", "账号已停用"); }
        requireMerchant(account.getRole());
        var session = sessions.lockOwnSession(principal.accountId(), principal.jti());
        validSession(session);
        if (categories.lockEnabled(categoryId) == null) { throw MerchantShopPolicy.invalid("分类不存在或未启用，请重新选择"); }
        // Read ownership without a member lock; verify it again after the shop lock.
        var own = shops.findOwn(principal.accountId());
        if (own == null) { throw new BusinessException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "本人店铺不存在"); }
        var shop = shops.lock(own.id());
        if (shop == null) { throw new BusinessException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "本人店铺不存在"); }
        if (shop.getVersion() != version || version == Long.MAX_VALUE) { throw conflict("CONFIG_VERSION_CONFLICT", "店铺版本已变化或无法递增，请重新读取"); }
        if (!"REJECTED".equals(shop.getApprovalStatus()) || !"PENDING".equals(members.lockOwnOwnerStatus(own.id(), principal.accountId()))) {
            throw conflict("SHOP_STATE_CONFLICT", "仅被拒且成员待审核的本人店铺可重新提交");
        }
        // Locks may have waited beyond JWT expiry; never write with an expired session.
        validSession(session);
        if (shops.resubmit(own.id(), version, categoryId, name, location, description) != 1) {
            throw conflict("CONFIG_VERSION_CONFLICT", "店铺版本已变化");
        }
        // Only current review fields are cleared; immutable shop_review rows are preserved.
        return views.own(principal);
    }

    private void validSession(AuthSessionState session) {
        if (session == null) { throw TokenService.failure("TOKEN_INVALID", "登录会话不存在"); }
        if (!clock.instant().isBefore(session.expiresAt().toInstant(ZoneOffset.UTC))) { throw TokenService.failure("SESSION_EXPIRED", "登录已过期"); }
        if (session.revokedAt() != null) { throw TokenService.failure("SESSION_REVOKED", "登录已退出"); }
    }
    private static void requireMerchant(String role) {
        if (!"MERCHANT".equals(role)) { throw new BusinessException(HttpStatus.FORBIDDEN, "ROLE_FORBIDDEN", "仅商家可重新提交本人入驻申请"); }
    }
    private static BusinessException conflict(String code, String message) { return new BusinessException(HttpStatus.CONFLICT, code, message); }
}
