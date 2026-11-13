package com.campuslife.merchant.service;

import com.campuslife.auth.mapper.AccountMapper;
import com.campuslife.auth.mapper.AuthSessionMapper;
import com.campuslife.auth.mapper.model.AuthSessionState;
import com.campuslife.auth.security.AuthPrincipal;
import com.campuslife.auth.security.TokenService;
import com.campuslife.common.exception.BusinessException;
import com.campuslife.merchant.dto.ShopPatchRequest;
import com.campuslife.merchant.mapper.ShopMapper;
import com.campuslife.merchant.mapper.BusinessHourMapper;
import com.campuslife.merchant.mapper.ShopMemberMapper;
import com.campuslife.merchant.vo.MerchantShopView;
import com.campuslife.merchant.vo.ShopCategoryView;
import java.time.Clock;
import java.time.ZoneOffset;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Service
@Profile("mysql")
public class MerchantOperationService {
    private final AccountMapper accounts;
    private final AuthSessionMapper sessions;
    private final ShopMapper shops;
    private final ShopMemberMapper members;
    private final Clock clock;
    private final BusinessHourMapper hours;

    public MerchantOperationService(AccountMapper accounts, AuthSessionMapper sessions, ShopMapper shops,
                                    ShopMemberMapper members, Clock clock, BusinessHourMapper hours) {
        this.accounts = accounts; this.sessions = sessions; this.shops = shops; this.members = members; this.clock = clock; this.hours = hours;
    }

    @Transactional
    public MerchantShopView patch(AuthPrincipal principal, ShopPatchRequest request) {
        requireMerchant(principal.account().role());
        long expected = MerchantShopPolicy.version(request.expectedVersion());
        String requestedName = optionalText(request.name(), 1, 80, "店铺名称");
        String requestedLocation = optionalText(request.locationText(), 1, 200, "店铺位置");
        String requestedDescription = optionalText(request.description(), 0, 1000, "店铺介绍");
        Boolean requestedBusiness = null;
        if (request.businessEnabled() != null) {
            if (!request.businessEnabled().isBoolean()) { throw MerchantShopPolicy.invalid("businessEnabled 须为 JSON 布尔值，不能为 null"); }
            requestedBusiness = request.businessEnabled().asBoolean();
        }
        // Same account -> session -> shop -> own OWNER order as other merchant writes.
        var account = accounts.lockById(principal.accountId());
        if (account == null) { throw TokenService.failure("TOKEN_INVALID", "登录账号不存在"); }
        if (!"ACTIVE".equals(account.getStatus())) { throw forbidden("ACCOUNT_DISABLED", "账号已停用"); }
        requireMerchant(account.getRole());
        var session = sessions.lockOwnSession(principal.accountId(), principal.jti());
        validSession(session);
        var own = shops.findOwn(principal.accountId());
        if (own == null) { throw missing(); }
        var shop = shops.lock(own.id());
        if (shop == null) { throw missing(); }
        if (!"APPROVED".equals(shop.getApprovalStatus())) { throw forbidden("SHOP_NOT_APPROVED", "店铺未获经营资格或已被暂停"); }
        if (!"ACTIVE".equals(members.lockOwnOwnerStatus(own.id(), principal.accountId()))) {
            throw forbidden("ROLE_FORBIDDEN", "本人店主成员未激活或已停用");
        }
        if (shop.getVersion() != expected) { throw conflict(); }
        String name = requestedName == null ? shop.getName() : requestedName;
        String location = requestedLocation == null ? shop.getLocationText() : requestedLocation;
        String description = requestedDescription == null ? shop.getDescription() : requestedDescription;
        boolean business = requestedBusiness == null ? shop.getBusinessEnabled() : requestedBusiness;
        boolean changed = !name.equals(shop.getName()) || !location.equals(shop.getLocationText())
                || !description.equals(shop.getDescription()) || business != shop.getBusinessEnabled();
        var businessHours = hours.current(own.id());
        validSession(session);
        if (changed) {
            if (expected == Long.MAX_VALUE || shops.updateOperation(own.id(), expected, name, location, description, business) != 1) { throw conflict(); }
        }
        // The initial ownership read may precede a concurrent approval; use the locked current values.
        return new MerchantShopView(Long.toString(own.id()), name,
                new ShopCategoryView(Long.toString(own.categoryId()), own.categoryCode(), own.categoryName()),
                location, description, own.campusCode(), shop.getApprovalStatus(), shop.getApprovalReason(),
                business, "ACTIVE", Long.toString(changed ? expected + 1 : expected), businessHours);
    }

    private static String optionalText(JsonNode value, int min, int max, String field) {
        if (value == null) { return null; }
        if (!value.isString()) { throw MerchantShopPolicy.invalid(field + "须为字符串，不能为 null"); }
        return MerchantShopPolicy.text(value.asString(), min, max, field);
    }
    private void validSession(AuthSessionState session) {
        if (session == null) { throw TokenService.failure("TOKEN_INVALID", "登录会话不存在"); }
        if (!clock.instant().isBefore(session.expiresAt().toInstant(ZoneOffset.UTC))) { throw TokenService.failure("SESSION_EXPIRED", "登录已过期"); }
        if (session.revokedAt() != null) { throw TokenService.failure("SESSION_REVOKED", "登录已退出"); }
    }
    private static void requireMerchant(String role) {
        if (!"MERCHANT".equals(role)) { throw forbidden("ROLE_FORBIDDEN", "仅商家可修改本人店铺经营资料"); }
    }
    private static BusinessException forbidden(String code, String message) { return new BusinessException(HttpStatus.FORBIDDEN, code, message); }
    private static BusinessException missing() { return new BusinessException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "本人店铺不存在"); }
    private static BusinessException conflict() { return new BusinessException(HttpStatus.CONFLICT, "CONFIG_VERSION_CONFLICT", "店铺版本已变化或无法递增，请重新读取"); }
}
