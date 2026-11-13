package com.campuslife.order.service;

import com.campuslife.auth.mapper.AuthSessionMapper;
import com.campuslife.auth.mapper.model.SessionAccount;
import com.campuslife.auth.security.AuthPrincipal;
import com.campuslife.auth.security.TokenService;
import com.campuslife.common.exception.BusinessException;
import com.campuslife.file.mapper.FileAssetMapper;
import com.campuslife.merchant.mapper.PrintPriceRuleMapper;
import com.campuslife.merchant.mapper.PublicShopMapper;
import com.campuslife.merchant.mapper.ServiceItemMapper;
import com.campuslife.order.dto.PrintQuoteRequest;
import com.campuslife.order.vo.PrintQuote;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Profile("mysql")
public class PrintQuoteService {
    private final AuthSessionMapper sessions;
    private final FileAssetMapper files;
    private final ServiceItemMapper services;
    private final PublicShopMapper shops;
    private final PrintPriceRuleMapper prices;
    private final Clock clock;
    private final com.campuslife.coupon.service.OrderCouponService coupons;

    public PrintQuoteService(AuthSessionMapper sessions,FileAssetMapper files,ServiceItemMapper services,
            PublicShopMapper shops,PrintPriceRuleMapper prices,Clock clock,com.campuslife.coupon.service.OrderCouponService coupons) {
        this.coupons=coupons;
        this.sessions=sessions;this.files=files;this.services=services;this.shops=shops;this.prices=prices;this.clock=clock;
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PrintQuote quote(AuthPrincipal principal,PrintQuoteRequest body) {
        var request=PrintQuotePolicy.parse(body);
        var account=sessions.find(principal.accountId(),principal.jti());
        // Only read eligible resources; defer errors until one final Clock decision preserves their priority.
        var file=eligible(account)?files.selectById(request.file()):null;
        boolean own=file!=null&&file.getOwnerId()==principal.accountId();
        var service=own&&"AVAILABLE".equals(file.getStatus())?services.selectById(request.service()):null;
        boolean printable=service!=null&&"PRINT".equals(service.getServiceType())&&Boolean.TRUE.equals(service.getEnabled());
        var shop=printable?shops.visible(service.getShopId()):null;
        boolean visible=shop!=null&&"PRINT".equals(shop.categoryCode());
        var rule=visible?prices.list(service.getId()).stream().filter(row->request.paper().equals(row.getPaperSize())
                &&request.color().equals(row.getColorMode())&&request.side().equals(row.getSideMode())).findFirst().orElse(null):null;
        Long coupon=com.campuslife.coupon.service.OrderCouponService.id(body.userCouponId());
        var couponRow=coupons.readQuote(coupon);
        Instant now=clock.instant();
        access(account,now);
        if(!own||"UPLOADING".equals(file.getStatus())){throw missing();}
        if(!"AVAILABLE".equals(file.getStatus())||(file.getExpiresAt()!=null&&!now.isBefore(file.getExpiresAt().toInstant(ZoneOffset.UTC)))) {
            throw new BusinessException(HttpStatus.GONE,"FILE_EXPIRED","文件已过期或进入删除流程");
        }
        if(!visible){throw missing();}
        if(rule==null||!Boolean.TRUE.equals(rule.getEnabled())) {
            throw new BusinessException(HttpStatus.CONFLICT,"PRINT_SPEC_UNAVAILABLE","打印规格未配置或已停用");
        }
        if(rule.getVersion()==null||rule.getVersion()<0){throw new IllegalStateException("Invalid print price version");}
        var totals=PrintQuotePolicy.calculate(file.getPdfPageCount(),request.copies(),request.side(),rule.getPricePerImpression());
        long discount=coupons.quote(coupon,couponRow,principal.accountId(),shop.id(),totals.amount(),now);
        return new PrintQuote(service.getId().toString(),Long.toString(shop.id()),file.getId().toString(),file.getPdfPageCount(),
                request.copies(),request.paper(),request.color(),request.side(),totals.sheets(),rule.getId().toString(),
                rule.getVersion().toString(),rule.getPricePerImpression(),totals.amount(),discount,totals.amount()-discount,"CNY",coupon==null?null:coupon.toString());
    }

    private static boolean eligible(SessionAccount account) {
        return account!=null&&"ACTIVE".equals(account.status())&&"STUDENT".equals(account.role())&&account.revokedAt()==null;
    }
    static void access(SessionAccount account,Instant now) {
        if(account==null){throw TokenService.failure("TOKEN_INVALID","登录会话不存在");}
        if(!now.isBefore(account.expiresAt().toInstant(ZoneOffset.UTC))){throw TokenService.failure("SESSION_EXPIRED","登录已过期");}
        if(account.revokedAt()!=null){throw TokenService.failure("SESSION_REVOKED","登录已退出");}
        if(!"ACTIVE".equals(account.status())){throw new BusinessException(HttpStatus.FORBIDDEN,"ACCOUNT_DISABLED","账号已停用");}
        if(!"STUDENT".equals(account.role())){throw new BusinessException(HttpStatus.FORBIDDEN,"ROLE_FORBIDDEN","仅学生可计算本人打印报价");}
    }
    private static BusinessException missing(){return new BusinessException(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND","可用文件或打印服务不存在");}
}
