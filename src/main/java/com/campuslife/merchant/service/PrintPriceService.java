package com.campuslife.merchant.service;

import com.campuslife.auth.mapper.AccountMapper;
import com.campuslife.auth.mapper.AuthSessionMapper;
import com.campuslife.auth.mapper.model.AuthSessionState;
import com.campuslife.auth.security.AuthPrincipal;
import com.campuslife.auth.security.TokenService;
import com.campuslife.common.exception.BusinessException;
import com.campuslife.merchant.dto.PrintPricesRequest;
import com.campuslife.merchant.entity.PrintPriceRuleEntity;
import com.campuslife.merchant.entity.ServiceItemEntity;
import com.campuslife.merchant.mapper.PrintPriceRuleMapper;
import com.campuslife.merchant.mapper.ServiceItemMapper;
import com.campuslife.merchant.mapper.ShopMapper;
import com.campuslife.merchant.mapper.ShopMemberMapper;
import com.campuslife.merchant.vo.PrintPriceView;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("mysql")
public class PrintPriceService {
    private final AccountMapper accounts;
    private final AuthSessionMapper sessions;
    private final ShopMapper shops;
    private final ShopMemberMapper members;
    private final ServiceItemMapper services;
    private final PrintPriceRuleMapper prices;
    private final Clock clock;

    public PrintPriceService(AccountMapper accounts, AuthSessionMapper sessions, ShopMapper shops,
                             ShopMemberMapper members, ServiceItemMapper services, PrintPriceRuleMapper prices, Clock clock) {
        this.accounts = accounts; this.sessions = sessions; this.shops = shops; this.members = members;
        this.services = services; this.prices = prices; this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<PrintPriceView> list(AuthPrincipal principal, String id) {
        requireMerchant(principal.account().role());
        long serviceId = MerchantServicePolicy.id(id);
        var own = shops.findOwn(principal.accountId());
        if (own == null) { throw missing(); }
        requireApproved(own.approvalStatus()); requireOwner(own.memberStatus());
        requirePrint(services.findOwn(serviceId, own.id()));
        return prices.list(serviceId).stream().map(PrintPriceService::view).toList();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<PrintPriceView> save(AuthPrincipal principal, String id, PrintPricesRequest request) {
        requireMerchant(principal.account().role());
        long serviceId = MerchantServicePolicy.id(id);
        var normalized = PrintPricePolicy.normalize(request.rules());
        var session = lockAccess(principal, serviceId);
        // The parent protects missing specs; RC avoids broad empty-range gap locks between unrelated services.
        Map<String, PrintPriceRuleEntity> current = prices.current(serviceId).stream().collect(
                Collectors.toMap(row -> row.getColorMode() + "/" + row.getSideMode(), Function.identity()));
        for (var rule : normalized) {
            var row = current.get(rule.key());
            if (rule.expected() == null) {
                if (row != null) { throw conflict(); }
            } else if (row == null || !rule.expected().equals(row.getVersion())
                    || (changed(row, rule) && row.getVersion() == Long.MAX_VALUE)) { throw conflict(); }
        }
        validSession(session);
        for (var rule : normalized) {
            var row = current.get(rule.key());
            if (row == null) {
                var created = new PrintPriceRuleEntity();
                created.setServiceId(serviceId); created.setPaperSize("A4");
                created.setColorMode(rule.color()); created.setSideMode(rule.side());
                created.setPricePerImpression(rule.amount()); created.setEnabled(rule.enabled()); created.setVersion(0L);
                try {
                    if (prices.insert(created) != 1) { throw new IllegalStateException("Print price insert failed"); }
                } catch (DuplicateKeyException error) { throw conflict(); }
            } else if (changed(row, rule)) {
                if (prices.updateOwn(row.getId(), serviceId, rule.expected(), rule.amount(), rule.enabled()) != 1) { throw conflict(); }
            }
            // Every write may wait on a database lock; never commit an expired session's batch.
            validSession(session);
        }
        var result = prices.current(serviceId).stream().map(PrintPriceService::view).toList();
        validSession(session);
        return result;
    }

    private AuthSessionState lockAccess(AuthPrincipal principal, long serviceId) {
        // Account -> session -> shop -> own OWNER -> service -> prices (fixed specification order).
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
        requirePrint(services.lockOwn(serviceId, own.id()));
        validSession(session);
        return session;
    }

    private void validSession(AuthSessionState session) {
        if (session == null) { throw TokenService.failure("TOKEN_INVALID", "登录会话不存在"); }
        if (!clock.instant().isBefore(session.expiresAt().toInstant(ZoneOffset.UTC))) { throw TokenService.failure("SESSION_EXPIRED", "登录已过期"); }
        if (session.revokedAt() != null) { throw TokenService.failure("SESSION_REVOKED", "登录已退出"); }
    }
    private static boolean changed(PrintPriceRuleEntity row, PrintPricePolicy.Rule rule) {
        return row.getPricePerImpression() != rule.amount() || row.getEnabled() != rule.enabled();
    }
    private static void requirePrint(ServiceItemEntity item) {
        if (item == null) { throw missing(); }
        if (!"PRINT".equals(item.getServiceType())) { throw MerchantShopPolicy.invalid("仅 PRINT 服务可配置打印价目"); }
    }
    private static void requireMerchant(String role) { if (!"MERCHANT".equals(role)) { throw forbidden("ROLE_FORBIDDEN", "仅商家可管理本人打印价目"); } }
    private static void requireApproved(String status) { if (!"APPROVED".equals(status)) { throw forbidden("SHOP_NOT_APPROVED", "店铺未获经营资格或已被暂停"); } }
    private static void requireOwner(String status) { if (!"ACTIVE".equals(status)) { throw forbidden("ROLE_FORBIDDEN", "本人店主成员未激活或已停用"); } }
    private static PrintPriceView view(PrintPriceRuleEntity row) {
        return new PrintPriceView(Long.toString(row.getId()), row.getPaperSize(), row.getColorMode(), row.getSideMode(),
                row.getPricePerImpression(), row.getEnabled(), Long.toString(row.getVersion()));
    }
    private static BusinessException forbidden(String code, String message) { return new BusinessException(HttpStatus.FORBIDDEN, code, message); }
    private static BusinessException missing() { return new BusinessException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "本人店铺或服务不存在"); }
    private static BusinessException conflict() { return new BusinessException(HttpStatus.CONFLICT, "CONFIG_VERSION_CONFLICT", "价目规格已存在、版本变化或无法递增，请重新查询"); }
}
