package com.campuslife.merchant.service;

import com.campuslife.auth.mapper.AccountMapper;
import com.campuslife.auth.mapper.AuthSessionMapper;
import com.campuslife.auth.mapper.model.AuthSessionState;
import com.campuslife.auth.security.AuthPrincipal;
import com.campuslife.auth.security.TokenService;
import com.campuslife.common.exception.BusinessException;
import com.campuslife.merchant.dto.BarberCreateRequest;
import com.campuslife.merchant.dto.BarberPatchRequest;
import com.campuslife.merchant.dto.BarberServicesRequest;
import com.campuslife.merchant.entity.BarberEntity;
import com.campuslife.merchant.mapper.BarberMapper;
import com.campuslife.merchant.mapper.BarberServiceMapper;
import com.campuslife.merchant.mapper.ServiceItemMapper;
import com.campuslife.merchant.mapper.ShopMapper;
import com.campuslife.merchant.mapper.ShopMemberMapper;
import com.campuslife.merchant.vo.MerchantBarberView;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("mysql")
public class BarberManagementService {
    private final AccountMapper accounts;
    private final AuthSessionMapper sessions;
    private final ShopMapper shops;
    private final ShopMemberMapper members;
    private final ServiceItemMapper services;
    private final BarberMapper barbers;
    private final BarberServiceMapper links;
    private final Clock clock;
    private final com.campuslife.order.service.BookingConfigurationProtection bookingProtection;

    public BarberManagementService(AccountMapper accounts, AuthSessionMapper sessions, ShopMapper shops,
                                   ShopMemberMapper members, ServiceItemMapper services, BarberMapper barbers,
                                   BarberServiceMapper links, Clock clock, com.campuslife.order.service.BookingConfigurationProtection bookingProtection) {
        this.accounts = accounts; this.sessions = sessions; this.shops = shops; this.members = members;
        this.services = services; this.barbers = barbers; this.links = links; this.clock = clock;
        this.bookingProtection = bookingProtection;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<MerchantBarberView> list(AuthPrincipal principal) {
        requireMerchant(principal.account().role());
        var own = shops.findOwn(principal.accountId());
        if (own == null) { throw missing(); }
        requireApproved(own.approvalStatus()); requireOwner(own.memberStatus()); requireHaircut(own.categoryCode());
        var rows = barbers.listOwn(own.id());
        if (rows.isEmpty()) { return List.of(); }
        var byBarber = new HashMap<Long, List<Long>>();
        for (var link : links.listOwnShop(own.id())) {
            byBarber.computeIfAbsent(link.barberId(), ignored -> new ArrayList<>()).add(link.serviceId());
        }
        return rows.stream().map(row -> view(row, byBarber.getOrDefault(row.getId(), List.of()))).toList();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MerchantBarberView create(AuthPrincipal principal, BarberCreateRequest request) {
        requireMerchant(principal.account().role());
        String name = BarberPolicy.name(request.displayName());
        var ids = BarberPolicy.serviceIds(request.serviceIds());
        var access = lockAccess(principal);
        lockServices(access.shopId(), ids); validSession(access.session());
        var row = new BarberEntity(); row.setShopId(access.shopId()); row.setDisplayName(name);
        row.setEnabled(false); row.setVersion(0L);
        if (barbers.insert(row) != 1) { throw new IllegalStateException("Barber insert failed"); }
        validSession(access.session());
        for (long service : ids) {
            if (links.insert(row.getId(), service) != 1) { throw new IllegalStateException("Barber service insert failed"); }
            validSession(access.session());
        }
        return view(row, ids);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MerchantBarberView patch(AuthPrincipal principal, String id, BarberPatchRequest request) {
        requireMerchant(principal.account().role());
        long barberId = MerchantServicePolicy.id(id, "barberId");
        long expected = MerchantShopPolicy.version(request.expectedVersion());
        var access = lockAccess(principal);
        bookingProtection.lockBarberDays(access.shopId(), barberId);
        var row = lockBarber(barberId, access.shopId(), expected);
        // Read bindings after the parent lock, never from a pre-wait snapshot.
        var ids = links.current(barberId);
        String name = request.displayName() == null ? row.getDisplayName() : BarberPolicy.name(request.displayName());
        boolean enabled = request.enabled() == null ? row.getEnabled() : MerchantServicePolicy.enabled(request.enabled());
        boolean changed = !name.equals(row.getDisplayName()) || enabled != row.getEnabled();
        validSession(access.session());
        if (changed) {
            bookingProtection.barber(access.shopId(), barberId, enabled, null);
            update(row, expected, name, enabled); validSession(access.session());
        }
        return view(row, ids);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MerchantBarberView replaceServices(AuthPrincipal principal, String id, BarberServicesRequest request) {
        requireMerchant(principal.account().role());
        long barberId = MerchantServicePolicy.id(id, "barberId");
        long expected = MerchantShopPolicy.version(request.expectedVersion());
        var ids = BarberPolicy.serviceIds(request.serviceIds());
        var access = lockAccess(principal);
        bookingProtection.lockBarberDays(access.shopId(), barberId);
        lockServices(access.shopId(), ids);
        var row = lockBarber(barberId, access.shopId(), expected);
        var current = links.current(barberId);
        validSession(access.session());
        if (!current.equals(ids)) {
            bookingProtection.barber(access.shopId(), barberId, row.getEnabled(), ids);
            if (expected == Long.MAX_VALUE) { throw conflict(); }
            var targetSet = new HashSet<>(ids); var currentSet = new HashSet<>(current);
            for (long old : current) {
                if (!targetSet.contains(old)) {
                    if (links.delete(barberId, old) != 1) { throw new IllegalStateException("Barber service delete failed"); }
                    validSession(access.session());
                }
            }
            for (long added : ids) {
                if (!currentSet.contains(added)) {
                    if (links.insert(barberId, added) != 1) { throw new IllegalStateException("Barber service insert failed"); }
                    validSession(access.session());
                }
            }
            update(row, expected, row.getDisplayName(), row.getEnabled()); validSession(access.session());
        }
        return view(row, ids);
    }

    private Access lockAccess(AuthPrincipal principal) {
        // Account -> session -> shop -> own OWNER -> target services (ID asc) -> barber -> bindings.
        var account = accounts.lockById(principal.accountId());
        if (account == null) { throw TokenService.failure("TOKEN_INVALID", "登录账号不存在"); }
        if (!"ACTIVE".equals(account.getStatus())) { throw forbidden("ACCOUNT_DISABLED", "账号已停用"); }
        requireMerchant(account.getRole());
        var session = sessions.lockOwnSession(principal.accountId(), principal.jti()); validSession(session);
        var own = shops.findOwn(principal.accountId());
        if (own == null) { throw missing(); }
        var shop = shops.lock(own.id());
        if (shop == null) { throw missing(); }
        requireApproved(shop.getApprovalStatus());
        requireOwner(members.lockOwnOwnerStatus(own.id(), principal.accountId()));
        requireHaircut(own.categoryCode()); validSession(session);
        return new Access(own.id(), session);
    }

    private void lockServices(long shopId, List<Long> ids) {
        for (long id : ids) {
            var service = services.lockOwn(id, shopId);
            if (service == null) { throw missing(); }
            if (!"HAIRCUT".equals(service.getServiceType())) { throw MerchantShopPolicy.invalid("只能绑定本店 HAIRCUT 服务"); }
        }
    }
    private BarberEntity lockBarber(long id, long shopId, long expected) {
        var row = barbers.lockOwn(id, shopId);
        if (row == null) { throw missing(); }
        if (row.getVersion() != expected) { throw conflict(); }
        return row;
    }
    private void update(BarberEntity row, long expected, String name, boolean enabled) {
        if (expected == Long.MAX_VALUE || barbers.updateOwn(row.getId(), row.getShopId(), expected, name, enabled) != 1) { throw conflict(); }
        row.setDisplayName(name); row.setEnabled(enabled); row.setVersion(expected + 1);
    }
    private void validSession(AuthSessionState session) {
        if (session == null) { throw TokenService.failure("TOKEN_INVALID", "登录会话不存在"); }
        if (!clock.instant().isBefore(session.expiresAt().toInstant(ZoneOffset.UTC))) { throw TokenService.failure("SESSION_EXPIRED", "登录已过期"); }
        if (session.revokedAt() != null) { throw TokenService.failure("SESSION_REVOKED", "登录已退出"); }
    }
    private static MerchantBarberView view(BarberEntity row, List<Long> ids) {
        return new MerchantBarberView(Long.toString(row.getId()), row.getDisplayName(), row.getEnabled(),
                ids.stream().map(String::valueOf).toList(), Long.toString(row.getVersion()));
    }
    private record Access(long shopId, AuthSessionState session) {}
    private static void requireMerchant(String role) { if (!"MERCHANT".equals(role)) { throw forbidden("ROLE_FORBIDDEN", "仅商家可管理本人理发师"); } }
    private static void requireApproved(String status) { if (!"APPROVED".equals(status)) { throw forbidden("SHOP_NOT_APPROVED", "店铺未获经营资格或已被暂停"); } }
    private static void requireOwner(String status) { if (!"ACTIVE".equals(status)) { throw forbidden("ROLE_FORBIDDEN", "本人店主成员未激活或已停用"); } }
    private static void requireHaircut(String category) { if (!"HAIRCUT".equals(category)) { throw MerchantShopPolicy.invalid("仅 HAIRCUT 分类店铺支持理发师管理"); } }
    private static BusinessException forbidden(String code, String message) { return new BusinessException(HttpStatus.FORBIDDEN, code, message); }
    private static BusinessException missing() { return new BusinessException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "本人店铺、理发师或服务不存在"); }
    private static BusinessException conflict() { return new BusinessException(HttpStatus.CONFLICT, "CONFIG_VERSION_CONFLICT", "理发师版本已变化或无法递增，请重新查询"); }
}
