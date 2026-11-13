package com.campuslife.admin.service;

import com.campuslife.admin.mapper.AdminShopMapper;
import com.campuslife.admin.mapper.ShopReviewMapper;
import com.campuslife.admin.vo.AdminShopPage;
import com.campuslife.auth.entity.AccountEntity;
import com.campuslife.auth.mapper.AccountMapper;
import com.campuslife.auth.mapper.AuthSessionMapper;
import com.campuslife.auth.mapper.model.AuthSessionState;
import com.campuslife.auth.security.AuthPrincipal;
import com.campuslife.auth.security.TokenService;
import com.campuslife.common.exception.BusinessException;
import com.campuslife.merchant.mapper.ShopMemberMapper;
import com.campuslife.merchant.mapper.BusinessHourMapper;
import com.campuslife.merchant.vo.BusinessHourView;
import com.campuslife.merchant.mapper.model.BusinessHourRow;
import com.campuslife.merchant.mapper.model.OwnShopRow;
import com.campuslife.merchant.vo.MerchantShopView;
import com.campuslife.merchant.vo.ShopCategoryView;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import tools.jackson.databind.JsonNode;

@Service
@Profile("mysql")
public class ShopReviewService {
    private final AdminShopMapper shops;
    private final ShopMemberMapper members;
    private final ShopReviewMapper reviews;
    private final AccountMapper accounts;
    private final AuthSessionMapper sessions;
    private final Clock clock;
    private final BusinessHourMapper hours;

    public ShopReviewService(AdminShopMapper shops, ShopMemberMapper members, ShopReviewMapper reviews,
                             AccountMapper accounts, AuthSessionMapper sessions, Clock clock, BusinessHourMapper hours) {
        this.shops = shops; this.members = members; this.reviews = reviews;
        this.accounts = accounts; this.sessions = sessions; this.clock = clock; this.hours = hours;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AdminShopPage list(AuthPrincipal principal, String status, int page, int size) {
        requireAdmin(principal.account().role());
        if (page < 1 || size < 1 || size > 100) { throw invalid("page 须为正整数，size 须为 1～100"); }
        if (status != null && !Set.of("PENDING", "APPROVED", "REJECTED", "SUSPENDED").contains(status)) { throw invalid("非法审核状态"); }
        long total = shops.count(status);
        var rows = shops.page(status, ((long) page - 1) * size, size);
        Map<Long, List<BusinessHourView>> businessHours = rows.isEmpty() ? Map.of()
                : hours.batchRows(rows.stream().map(OwnShopRow::id).toList()).stream().collect(Collectors.groupingBy(
                        BusinessHourRow::shopId, Collectors.mapping(BusinessHourRow::view, Collectors.toList())));
        var items = rows.stream().map(row -> view(row, businessHours.getOrDefault(row.id(), List.of()))).toList();
        return new AdminShopPage(items, page, size, total, total / size + (total % size == 0 ? 0 : 1));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MerchantShopView detail(AuthPrincipal principal, String id) {
        requireAdmin(principal.account().role());
        long shopId = positiveId(id);
        return view(shops.detail(shopId), hours.views(shopId));
    }

    @Transactional
    public MerchantShopView decide(AuthPrincipal principal, String id, JsonNode expected, boolean approve, JsonNode reason) {
        requireAdmin(principal.account().role());
        long shopId = positiveId(id);
        long version = version(expected);
        String note = approve ? null : reason(reason);
        // Same initial account -> session order as account mutations, followed by shop -> owner.
        AccountEntity actor = accounts.lockById(principal.accountId());
        if (actor == null) { throw TokenService.failure("TOKEN_INVALID", "登录账号不存在"); }
        if (!"ACTIVE".equals(actor.getStatus())) { throw new BusinessException(HttpStatus.FORBIDDEN,"ACCOUNT_DISABLED","账号已停用"); }
        requireAdmin(actor.getRole());
        AuthSessionState session = sessions.lockOwnSession(principal.accountId(), principal.jti());
        validSession(session);
        var shop = shops.lock(shopId);
        if (shop == null) { throw missing(); }
        if (shop.version() != version || version == Long.MAX_VALUE) { throw conflict("CONFIG_VERSION_CONFLICT", "店铺版本已变化或无法递增，请重新读取"); }
        if (!"PENDING".equals(shop.approvalStatus())) { throw conflict("SHOP_STATE_CONFLICT", "仅待审核店铺可审核"); }
        if (!"PENDING".equals(members.lockOwnerStatus(shopId))) { throw conflict("SHOP_STATE_CONFLICT", "店主成员状态不允许审核"); }
        // A blocked shop lock can outlive the JWT; check again immediately before writing.
        validSession(session);
        String decision = approve ? "APPROVED" : "REJECTED";
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        now = now.withNano(now.getNano() / 1_000_000 * 1_000_000);
        if (shops.decide(shopId, version, decision, note, principal.accountId(), now) != 1) { throw conflict("CONFIG_VERSION_CONFLICT", "店铺版本已变化"); }
        if (approve && members.activateOwner(shopId) != 1) { throw conflict("SHOP_STATE_CONFLICT", "店主状态已变化"); }
        if (reviews.append(shopId, version, decision, note, principal.accountId(), now) != 1) { throw new IllegalStateException("Review insert failed"); }
        return view(shops.detail(shopId), hours.current(shopId));
    }

    private void validSession(AuthSessionState session) {
        if (session == null) { throw TokenService.failure("TOKEN_INVALID", "登录会话不存在"); }
        if (!clock.instant().isBefore(session.expiresAt().toInstant(ZoneOffset.UTC))) { throw TokenService.failure("SESSION_EXPIRED", "登录已过期"); }
        if (session.revokedAt() != null) { throw TokenService.failure("SESSION_REVOKED", "登录已退出"); }
    }

    private static void requireAdmin(String role) {
        if (!"ADMIN".equals(role)) { throw new BusinessException(HttpStatus.FORBIDDEN,"ROLE_FORBIDDEN","仅平台管理员可审核商家"); }
    }
    private static long positiveId(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) { throw invalid("shopId 须为正十进制整数字符串"); }
        try { return Long.parseLong(value); } catch (NumberFormatException error) { throw invalid("shopId 超出 BIGINT 范围"); }
    }
    private static long version(JsonNode value) {
        if (value == null || !value.isString() || !value.asString().matches("0|[1-9][0-9]{0,18}")) { throw invalid("expectedVersion 须为非负十进制整数字符串"); }
        try { return Long.parseLong(value.asString()); } catch (NumberFormatException error) { throw invalid("expectedVersion 超出 BIGINT 范围"); }
    }
    private static String reason(JsonNode value) {
        if (value == null || !value.isString() || !StandardCharsets.UTF_8.newEncoder().canEncode(value.asString())) { throw invalid("审核原因须为有效 Unicode 字符串"); }
        String normalized = value.asString().strip();
        int size = normalized.codePointCount(0, normalized.length());
        if (size < 1 || size > 300) { throw invalid("审核原因须为 1～300 字符"); }
        return normalized;
    }
    private static MerchantShopView view(OwnShopRow row, List<BusinessHourView> hours) {
        if (row == null) { throw missing(); }
        return new MerchantShopView(Long.toString(row.id()),row.name(),new ShopCategoryView(Long.toString(row.categoryId()),row.categoryCode(),row.categoryName()),
                row.locationText(),row.description(),row.campusCode(),row.approvalStatus(),row.approvalReason(),row.businessEnabled(),row.memberStatus(),Long.toString(row.version()),hours);
    }
    private static BusinessException missing() { return new BusinessException(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND","店铺不存在"); }
    private static BusinessException invalid(String message) { return new BusinessException(HttpStatus.BAD_REQUEST,"VALIDATION_FAILED",message); }
    private static BusinessException conflict(String code,String message) { return new BusinessException(HttpStatus.CONFLICT,code,message); }
}
