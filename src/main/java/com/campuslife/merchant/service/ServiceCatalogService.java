package com.campuslife.merchant.service;

import com.campuslife.auth.mapper.AccountMapper;
import com.campuslife.auth.mapper.AuthSessionMapper;
import com.campuslife.auth.mapper.model.AuthSessionState;
import com.campuslife.auth.security.AuthPrincipal;
import com.campuslife.auth.security.TokenService;
import com.campuslife.common.exception.BusinessException;
import com.campuslife.merchant.dto.ServiceCreateRequest;
import com.campuslife.merchant.dto.ServicePatchRequest;
import com.campuslife.merchant.entity.ServiceItemEntity;
import com.campuslife.merchant.mapper.ServiceItemMapper;
import com.campuslife.merchant.mapper.ShopMapper;
import com.campuslife.merchant.mapper.ShopMemberMapper;
import com.campuslife.merchant.vo.MerchantServiceView;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("mysql")
public class ServiceCatalogService {
    private final AccountMapper accounts;
    private final AuthSessionMapper sessions;
    private final ShopMapper shops;
    private final ShopMemberMapper members;
    private final ServiceItemMapper services;
    private final Clock clock;

    public ServiceCatalogService(AccountMapper accounts, AuthSessionMapper sessions, ShopMapper shops,
                                   ShopMemberMapper members, ServiceItemMapper services, Clock clock) {
        this.accounts = accounts; this.sessions = sessions; this.shops = shops;
        this.members = members; this.services = services; this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<MerchantServiceView> list(AuthPrincipal principal, String type, String enabled) {
        requireMerchant(principal.account().role());
        if (type != null && !Set.of("HAIRCUT", "PRINT").contains(type)) { throw MerchantShopPolicy.invalid("非法 serviceType 筛选"); }
        if (enabled != null && !Set.of("true", "false").contains(enabled)) { throw MerchantShopPolicy.invalid("enabled 筛选须为 true 或 false"); }
        var own = shops.findOwn(principal.accountId());
        if (own == null) { throw missing(); }
        requireApproved(own.approvalStatus()); requireOwner(own.memberStatus());
        return services.listOwn(own.id(), type, enabled == null ? null : Boolean.valueOf(enabled)).stream().map(ServiceCatalogService::view).toList();
    }

    @Transactional
    public MerchantServiceView create(AuthPrincipal principal, ServiceCreateRequest request) {
        requireMerchant(principal.account().role());
        String type = MerchantServicePolicy.type(request.serviceType());
        String name = MerchantServicePolicy.text(request.name(), 1, 80, "服务名称");
        String description = request.description() == null ? "" : MerchantServicePolicy.text(request.description(), 0, 500, "服务介绍");
        int amount = MerchantServicePolicy.amount(request.priceAmount());
        Integer duration = MerchantServicePolicy.duration(type, request.durationMinutes());
        boolean enabled = request.enabled() != null && MerchantServicePolicy.enabled(request.enabled());
        var access = lockAccess(principal);
        if (!type.equals(access.categoryCode())) { throw MerchantShopPolicy.invalid("服务类型须与店铺分类匹配"); }
        validSession(access.session());
        var item = new ServiceItemEntity();
        item.setShopId(access.shopId()); item.setServiceType(type); item.setName(name); item.setDescription(description);
        item.setPriceAmount((long) amount); item.setDurationMinutes(duration); item.setEnabled(enabled); item.setVersion(0L);
        if (services.insert(item) != 1) { throw new IllegalStateException("Service insert failed"); }
        validSession(access.session());
        return view(item);
    }

    @Transactional
    public MerchantServiceView patch(AuthPrincipal principal, String id, ServicePatchRequest request) {
        requireMerchant(principal.account().role());
        long serviceId = MerchantServicePolicy.id(id);
        long expected = MerchantShopPolicy.version(request.expectedVersion());
        var access = lockAccess(principal);
        var item = services.lockOwn(serviceId, access.shopId());
        if (item == null) { throw missing(); }
        if (item.getVersion() != expected) { throw conflict(); }
        String name = request.name() == null ? item.getName() : MerchantServicePolicy.text(request.name(), 1, 80, "服务名称");
        String description = request.description() == null ? item.getDescription() : MerchantServicePolicy.text(request.description(), 0, 500, "服务介绍");
        long amount = request.priceAmount() == null ? item.getPriceAmount() : MerchantServicePolicy.amount(request.priceAmount());
        Integer duration = request.durationMinutes() == null ? item.getDurationMinutes() : MerchantServicePolicy.duration(item.getServiceType(), request.durationMinutes());
        boolean enabled = request.enabled() == null ? item.getEnabled() : MerchantServicePolicy.enabled(request.enabled());
        boolean changed = !name.equals(item.getName()) || !description.equals(item.getDescription()) || amount != item.getPriceAmount()
                || !Objects.equals(duration, item.getDurationMinutes()) || enabled != item.getEnabled();
        validSession(access.session());
        if (changed) {
            if (expected == Long.MAX_VALUE || services.updateOwn(serviceId, access.shopId(), expected, name, description, amount, duration, enabled) != 1) { throw conflict(); }
            validSession(access.session());
            item.setName(name); item.setDescription(description); item.setPriceAmount(amount);
            item.setDurationMinutes(duration); item.setEnabled(enabled); item.setVersion(expected + 1);
        }
        return view(item);
    }

    private Access lockAccess(AuthPrincipal principal) {
        // Account -> session -> shop -> own OWNER -> service; all callers have a write transaction.
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
        validSession(session);
        return new Access(own.id(), own.categoryCode(), session);
    }

    private void validSession(AuthSessionState session) {
        if (session == null) { throw TokenService.failure("TOKEN_INVALID", "登录会话不存在"); }
        if (!clock.instant().isBefore(session.expiresAt().toInstant(ZoneOffset.UTC))) { throw TokenService.failure("SESSION_EXPIRED", "登录已过期"); }
        if (session.revokedAt() != null) { throw TokenService.failure("SESSION_REVOKED", "登录已退出"); }
    }
    private static void requireMerchant(String role) { if (!"MERCHANT".equals(role)) { throw forbidden("ROLE_FORBIDDEN", "仅商家可管理本人服务"); } }
    private static void requireApproved(String status) { if (!"APPROVED".equals(status)) { throw forbidden("SHOP_NOT_APPROVED", "店铺未获经营资格或已被暂停"); } }
    private static void requireOwner(String status) { if (!"ACTIVE".equals(status)) { throw forbidden("ROLE_FORBIDDEN", "本人店主成员未激活或已停用"); } }
    private static MerchantServiceView view(ServiceItemEntity item) {
        return new MerchantServiceView(Long.toString(item.getId()), item.getServiceType(), item.getName(), item.getDescription(),
                item.getPriceAmount(), item.getDurationMinutes(), item.getEnabled(), Long.toString(item.getVersion()));
    }
    private record Access(long shopId, String categoryCode, AuthSessionState session) {}
    private static BusinessException forbidden(String code, String message) { return new BusinessException(HttpStatus.FORBIDDEN, code, message); }
    private static BusinessException missing() { return new BusinessException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "本人店铺或服务不存在"); }
    private static BusinessException conflict() { return new BusinessException(HttpStatus.CONFLICT, "CONFIG_VERSION_CONFLICT", "服务版本已变化或无法递增，请重新查询服务"); }
}
