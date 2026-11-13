package com.campuslife.merchant.service;

import com.campuslife.auth.mapper.AccountMapper;
import com.campuslife.auth.mapper.AuthSessionMapper;
import com.campuslife.auth.mapper.model.AuthSessionState;
import com.campuslife.auth.security.AuthPrincipal;
import com.campuslife.auth.security.TokenService;
import com.campuslife.common.exception.BusinessException;
import com.campuslife.merchant.dto.BusinessHoursRequest;
import com.campuslife.merchant.mapper.BusinessHourMapper;
import com.campuslife.merchant.mapper.ShopMapper;
import com.campuslife.merchant.mapper.ShopMemberMapper;
import com.campuslife.merchant.vo.MerchantShopView;
import com.campuslife.merchant.vo.ShopCategoryView;
import java.time.Clock;
import java.time.ZoneOffset;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@Service
@Profile("mysql")
public class BusinessHoursService {
    private final AccountMapper accounts;
    private final AuthSessionMapper sessions;
    private final ShopMapper shops;
    private final ShopMemberMapper members;
    private final BusinessHourMapper hours;
    private final Clock clock;
    private final ScheduleHoursProtection scheduleProtection;

    public BusinessHoursService(AccountMapper accounts, AuthSessionMapper sessions, ShopMapper shops,
                                ShopMemberMapper members, BusinessHourMapper hours, Clock clock, ScheduleHoursProtection scheduleProtection) {
        this.accounts = accounts; this.sessions = sessions; this.shops = shops;
        this.members = members; this.hours = hours; this.clock = clock;
        this.scheduleProtection = scheduleProtection;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MerchantShopView replace(AuthPrincipal principal, BusinessHoursRequest request) {
        requireMerchant(principal.account().role());
        long expected = MerchantShopPolicy.version(request.expectedVersion());
        var normalized = BusinessHoursPolicy.normalize(request.intervals());
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
        if (!"ACTIVE".equals(members.lockOwnOwnerStatus(own.id(), principal.accountId()))) { throw forbidden("ROLE_FORBIDDEN", "本人店主成员未激活或已停用"); }
        if (shop.getVersion() != expected) { throw conflict(); }
        // RC reads after shop X see the latest committed week; the gate excludes all other writers.
        // Lock guards before acquiring child-hour write locks, in the ADR-0018 resource order.
        var current = hours.views(own.id());
        boolean changed = !current.equals(normalized);
        validSession(session);
        if (changed) {
            if (expected == Long.MAX_VALUE) { throw conflict(); }
            scheduleProtection.check(own.id(), normalized);
            hours.delete(own.id());
            for (var hour : normalized) {
                if (hours.insert(own.id(), hour) != 1) { throw new IllegalStateException("Business hour insert failed"); }
            }
            // Inserts can also wait for a database lock. Recheck before the final write and commit.
            validSession(session);
            if (shops.incrementVersion(own.id(), expected) != 1) { throw conflict(); }
            validSession(session);
        }
        return new MerchantShopView(Long.toString(own.id()), shop.getName(),
                new ShopCategoryView(Long.toString(own.categoryId()), own.categoryCode(), own.categoryName()),
                shop.getLocationText(), shop.getDescription(), own.campusCode(), shop.getApprovalStatus(),
                shop.getApprovalReason(), shop.getBusinessEnabled(), "ACTIVE", Long.toString(changed ? expected + 1 : expected), normalized);
    }

    private void validSession(AuthSessionState session) {
        if (session == null) { throw TokenService.failure("TOKEN_INVALID", "登录会话不存在"); }
        if (!clock.instant().isBefore(session.expiresAt().toInstant(ZoneOffset.UTC))) { throw TokenService.failure("SESSION_EXPIRED", "登录已过期"); }
        if (session.revokedAt() != null) { throw TokenService.failure("SESSION_REVOKED", "登录已退出"); }
    }
    private static void requireMerchant(String role) { if (!"MERCHANT".equals(role)) { throw forbidden("ROLE_FORBIDDEN", "仅商家可修改本人营业时间"); } }
    private static BusinessException forbidden(String code, String message) { return new BusinessException(HttpStatus.FORBIDDEN, code, message); }
    private static BusinessException missing() { return new BusinessException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "本人店铺不存在"); }
    private static BusinessException conflict() { return new BusinessException(HttpStatus.CONFLICT, "CONFIG_VERSION_CONFLICT", "店铺版本已变化或无法递增，请重新读取"); }
}
