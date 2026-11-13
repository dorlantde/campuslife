package com.campuslife.merchant.service;

import com.campuslife.auth.mapper.AccountMapper;
import com.campuslife.auth.mapper.AuthSessionMapper;
import com.campuslife.auth.mapper.model.AuthSessionState;
import com.campuslife.auth.security.AuthPrincipal;
import com.campuslife.auth.security.TokenService;
import com.campuslife.common.exception.BusinessException;
import com.campuslife.merchant.dto.ScheduleWrite;
import com.campuslife.merchant.mapper.*;
import com.campuslife.merchant.vo.ScheduleInterval;
import com.campuslife.merchant.vo.ScheduleView;
import java.time.*;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("mysql")
public class BarberScheduleService {
    private final AccountMapper accounts;
    private final AuthSessionMapper sessions;
    private final ShopMapper shops;
    private final ShopMemberMapper members;
    private final BarberMapper barbers;
    private final BarberScheduleMapper schedules;
    private final BusinessHourMapper hours;
    private final Clock clock;
    private final com.campuslife.order.service.BookingConfigurationProtection bookingProtection;

    public BarberScheduleService(AccountMapper accounts, AuthSessionMapper sessions, ShopMapper shops,
                                  ShopMemberMapper members, BarberMapper barbers, BarberScheduleMapper schedules,
                                  BusinessHourMapper hours, Clock clock, com.campuslife.order.service.BookingConfigurationProtection bookingProtection) {
        this.accounts = accounts; this.sessions = sessions; this.shops = shops; this.members = members;
        this.barbers = barbers; this.schedules = schedules; this.hours = hours; this.clock = clock;
        this.bookingProtection = bookingProtection;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ScheduleView get(AuthPrincipal principal, String id, String dateText) {
        requireMerchant(principal.account().role());
        long barberId = MerchantServicePolicy.id(id, "barberId");
        var date = SchedulePolicy.date(dateText);
        var own = shops.findOwn(principal.accountId());
        if (own == null) { throw missing(); }
        qualify(own.approvalStatus(), own.memberStatus(), own.categoryCode());
        var barber = barbers.findOwn(barberId, own.id());
        if (barber == null) { throw missing(); }
        return new ScheduleView(date.toString(), intervals(barberId, date), Long.toString(barber.getVersion()));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ScheduleView replace(AuthPrincipal principal, String id, ScheduleWrite request) {
        requireMerchant(principal.account().role());
        long barberId = MerchantServicePolicy.id(id, "barberId");
        var date = SchedulePolicy.date(request.date());
        long expected = MerchantShopPolicy.version(request.expectedBarberVersion());
        var target = SchedulePolicy.normalize(request.intervals());
        SchedulePolicy.writable(date, clock);
        var account = accounts.lockById(principal.accountId());
        if (account == null) { throw TokenService.failure("TOKEN_INVALID", "登录账号不存在"); }
        if (!"ACTIVE".equals(account.getStatus())) { throw forbidden("ACCOUNT_DISABLED", "账号已停用"); }
        requireMerchant(account.getRole());
        var session = sessions.lockOwnSession(principal.accountId(), principal.jti());
        valid(session, date);
        var own = shops.findOwn(principal.accountId());
        if (own == null) { throw missing(); }
        var shop = shops.lock(own.id());
        if (shop == null) { throw missing(); }
        qualify(shop.getApprovalStatus(), members.lockOwnOwnerStatus(own.id(), principal.accountId()), own.categoryCode());
        // Shop X gates all configuration writers. Ownership is checked before creating a guard (FK locks barber).
        if (barbers.findOwn(barberId, own.id()) == null) { throw missing(); }
        Long guard = schedules.lockGuard(barberId, date);
        valid(session, date);
        if (guard == null && !target.isEmpty()) {
            if (schedules.insertGuard(barberId, date) != 1) { throw new IllegalStateException("Day guard insert failed"); }
            schedules.lockGuard(barberId, date);
        }
        var barber = barbers.lockOwn(barberId, own.id());
        if (barber == null) { throw missing(); }
        if (barber.getVersion() != expected) { throw conflict(); }
        valid(session, date);
        var current = intervals(barberId, date);
        var businessHours = hours.views(own.id());
        if (target.stream().anyMatch(interval -> !SchedulePolicy.covered(date, interval, businessHours))) {
            throw MerchantShopPolicy.invalid("排班必须完整位于当天连续营业时段内，请刷新营业时间");
        }
        boolean changed = !current.equals(target);
        if (changed) {
            if (expected == Long.MAX_VALUE) { throw conflict(); }
            bookingProtection.schedule(barberId, date, target);
            schedules.delete(barberId, date);
            for (var interval : target) {
                if (schedules.insert(barberId, date, SchedulePolicy.utc(date, interval.startTime()), SchedulePolicy.utc(date, interval.endTime())) != 1) {
                    throw new IllegalStateException("Schedule insert failed");
                }
            }
            if (barbers.updateOwn(barberId, own.id(), expected, barber.getDisplayName(), barber.getEnabled()) != 1) { throw conflict(); }
        }
        // Includes waits for shop, guard, barber, FK checks, DELETE, INSERT and final version UPDATE.
        valid(session, date);
        return new ScheduleView(date.toString(), target, Long.toString(changed ? expected + 1 : expected));
    }

    private List<ScheduleInterval> intervals(long barberId, LocalDate date) {
        return schedules.rows(barberId, date).stream().map(row -> new ScheduleInterval(
                SchedulePolicy.localTime(row.startAt()), SchedulePolicy.localTime(row.endAt()))).toList();
    }
    private void valid(AuthSessionState session, LocalDate date) {
        if (session == null) { throw TokenService.failure("TOKEN_INVALID", "登录会话不存在"); }
        if (!clock.instant().isBefore(session.expiresAt().toInstant(ZoneOffset.UTC))) { throw TokenService.failure("SESSION_EXPIRED", "登录已过期"); }
        if (session.revokedAt() != null) { throw TokenService.failure("SESSION_REVOKED", "登录已退出"); }
        SchedulePolicy.writable(date, clock);
    }
    private static void qualify(String approval, String owner, String category) {
        if (!"APPROVED".equals(approval)) { throw forbidden("SHOP_NOT_APPROVED", "店铺未获经营资格或已被暂停"); }
        if (!"ACTIVE".equals(owner)) { throw forbidden("ROLE_FORBIDDEN", "本人店主成员未激活或已停用"); }
        if (!"HAIRCUT".equals(category)) { throw MerchantShopPolicy.invalid("仅 HAIRCUT 分类店铺支持排班"); }
    }
    private static void requireMerchant(String role) { if (!"MERCHANT".equals(role)) { throw forbidden("ROLE_FORBIDDEN", "仅商家可管理本人排班"); } }
    private static BusinessException forbidden(String code, String message) { return new BusinessException(HttpStatus.FORBIDDEN, code, message); }
    private static BusinessException missing() { return new BusinessException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "本人店铺或理发师不存在"); }
    private static BusinessException conflict() { return new BusinessException(HttpStatus.CONFLICT, "CONFIG_VERSION_CONFLICT", "理发师版本已变化或无法递增，请重新查询"); }
}
