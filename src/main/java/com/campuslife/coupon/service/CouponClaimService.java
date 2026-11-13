package com.campuslife.coupon.service;
import com.campuslife.auth.mapper.*;
import com.campuslife.auth.mapper.model.AuthSessionState;
import com.campuslife.auth.security.*;
import com.campuslife.common.exception.BusinessException;
import com.campuslife.coupon.dto.CouponClaim;
import com.campuslife.coupon.entity.*;
import com.campuslife.coupon.mapper.*;
import com.campuslife.coupon.vo.*;
import com.campuslife.merchant.mapper.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service @Profile("mysql")
public class CouponClaimService {
    private final AccountMapper accounts;private final AuthSessionMapper sessions;private final ShopMapper shops;
    private final DispatchGateMapper gates;
    private final PublicShopMapper publicShops;private final CampaignMapper campaigns;private final OwnedCouponMapper coupons;private final ClaimRequestMapper requests;private final Clock clock;
    public CouponClaimService(AccountMapper accounts,AuthSessionMapper sessions,ShopMapper shops,PublicShopMapper publicShops,CampaignMapper campaigns,OwnedCouponMapper coupons,ClaimRequestMapper requests,DispatchGateMapper gates,Clock clock){
        this.accounts=accounts;this.sessions=sessions;this.shops=shops;this.publicShops=publicShops;this.campaigns=campaigns;this.coupons=coupons;this.requests=requests;this.gates=gates;this.clock=clock;
    }
    public record Creation(ClaimResult result,boolean replayed) {}
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Creation claim(AuthPrincipal principal,String key,CouponClaim body){
        role(principal.account().role());
        if(key==null||!key.matches("[A-Za-z0-9_-]{1,64}")){throw error(HttpStatus.BAD_REQUEST,"IDEMPOTENCY_KEY_REQUIRED","须提供1～64位合法Idempotency-Key");}
        if(body==null||body.campaignId()==null||!body.campaignId().isString()){throw CampaignPolicy.invalid();}
        long id=CampaignPolicy.id(body.campaignId().asString());
        var account=accounts.lockById(principal.accountId());if(account==null){throw TokenService.failure("TOKEN_INVALID","账号不存在");}
        if(!"ACTIVE".equals(account.getStatus())){throw error(HttpStatus.FORBIDDEN,"ACCOUNT_DISABLED","账号已停用");}role(account.getRole());
        var session=sessions.lockOwnSession(principal.accountId(),principal.jti());valid(session);
        var prior=requests.findKey(principal.accountId(),key);
        if(prior!=null){
            if(prior.getCampaignId()!=id){throw conflict("IDEMPOTENCY_KEY_CONFLICT","原请求键对应其他活动");}
            var result=result(prior,now());valid(session);return new Creation(result,true);
        }
        var location=campaigns.selectById(id);if(location==null||shops.share(location.getShopId())==null){throw missing();}
        var gate=gates.share(id);if(gate==null){throw new IllegalStateException("Missing campaign dispatch gate");}
        if(!"SYNC".equals(gate.mode())||!"OPEN".equals(gate.phase())){throw error(HttpStatus.SERVICE_UNAVAILABLE,"COUPON_ADMISSION_UNAVAILABLE","当前活动领取入口暂未开放");}
        var campaign=campaigns.lockOwn(id,location.getShopId());if(campaign==null){throw missing();}
        var now=now();valid(session);
        if(coupons.findCampaign(id,principal.accountId())!=null){throw conflict("COUPON_ALREADY_CLAIMED","每账号每活动仅可成功领取一次");}
        if(requests.active(principal.accountId(),id)!=null){throw conflict("COUPON_CLAIM_PENDING","已有进行中的领取请求");}
        if(publicShops.visible(campaign.getShopId())==null){throw missing();}
        if(!"PUBLISHED".equals(campaign.getStatus())||now.isBefore(campaign.getClaimStartAt())||!now.isBefore(campaign.getClaimEndAt())){throw conflict("COUPON_NOT_CLAIMABLE","活动未开放、已停止或领取窗口已结束");}
        if(now.isBefore(campaign.getPublishedAt())||now.isBefore(campaign.getUpdatedAt())){throw conflict("COUPON_TIME_INVALID","当前时间早于活动事实");}
        if(campaign.getIssuedCount()>=campaign.getTotalQuantity()){throw conflict("COUPON_SOLD_OUT","活动优惠券已领完");}
        var coupon=new OwnedCouponEntity();coupon.setStudentId(principal.accountId());coupon.setCampaignId(id);coupon.setShopId(campaign.getShopId());coupon.setNameSnapshot(campaign.getName());
        coupon.setThresholdAmount(campaign.getThresholdAmount());coupon.setReductionAmount(campaign.getReductionAmount());coupon.setUseStartAt(campaign.getUseStartAt());coupon.setUseEndAt(campaign.getUseEndAt());coupon.setSourceCampaignVersion(campaign.getVersion());coupon.setClaimedAt(now);coupon.setState("AVAILABLE");coupon.setVersion(0L);
        one(campaigns.issue(id));one(coupons.insert(coupon));
        var request=new ClaimRequestEntity();request.setStudentId(principal.accountId());request.setCampaignId(id);request.setRequestKey(key);request.setUserCouponId(coupon.getId());request.setCompletedAt(now);request.setStatus("SUCCEEDED");request.setSourceMode("SYNC");request.setCreatedAt(now);request.setSourceCampaignVersion(campaign.getVersion());one(requests.insert(request));
        valid(session);return new Creation(new ClaimResult(request.getId().toString(),Long.toString(id),"SUCCEEDED",instant(now),view(coupon,now)),false);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public ClaimResult detail(AuthPrincipal principal,String id){var session=read(principal);var row=requests.findOwn(principal.accountId(),CampaignPolicy.id(id));if(row==null){throw missing();}var result=result(row,now());valid(session);return result;}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public OwnedCouponPage own(AuthPrincipal principal,String shopText,String state,String pageText,String sizeText){
        var session=read(principal);Long shop=shopText==null?null:CampaignPolicy.id(shopText);
        if(state!=null&&!Set.of("AVAILABLE","EXPIRED","LOCKED","USED").contains(state)){throw CampaignPolicy.invalid();}
        int page=CampaignPolicy.page(pageText,1,Integer.MAX_VALUE),size=CampaignPolicy.page(sizeText,20,100);var now=now();
        long total=coupons.countOwn(principal.accountId(),shop,state,now);
        var items=coupons.pageOwn(principal.accountId(),shop,state,now,size,((long)page-1)*size).stream().map(c->view(c,now)).toList();valid(session);return new OwnedCouponPage(page,size,total,items);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PublicCampaignPage catalog(String id,String pageText,String sizeText){
        long shop=CampaignPolicy.id(id);int page=CampaignPolicy.page(pageText,1,Integer.MAX_VALUE),size=CampaignPolicy.page(sizeText,20,100);
        if(publicShops.visible(shop)==null){throw missing();}var now=now();long total=campaigns.countPublic(shop,now);
        var items=campaigns.pagePublic(shop,now,size,((long)page-1)*size).stream().map(c->new PublicCampaignView(c.getId().toString(),Long.toString(shop),c.getName(),c.getThresholdAmount(),c.getReductionAmount(),c.getTotalQuantity(),c.getIssuedCount(),c.getTotalQuantity()-c.getIssuedCount(),instant(c.getClaimStartAt()),instant(c.getClaimEndAt()),instant(c.getUseStartAt()),instant(c.getUseEndAt()))).toList();return new PublicCampaignPage(page,size,total,items);
    }
    private AuthSessionState read(AuthPrincipal p){
        role(p.account().role());var a=sessions.find(p.accountId(),p.jti());if(a==null){throw TokenService.failure("TOKEN_INVALID","会话不存在");}
        if(!"ACTIVE".equals(a.status())){throw error(HttpStatus.FORBIDDEN,"ACCOUNT_DISABLED","账号已停用");}role(a.role());var s=new AuthSessionState(a.accountId(),p.jti(),a.expiresAt(),a.revokedAt());valid(s);return s;
    }
    ClaimResult result(ClaimRequestEntity row,LocalDateTime now){
        if(!"SUCCEEDED".equals(row.getStatus())){return new ClaimResult(row.getId().toString(),row.getCampaignId().toString(),row.getStatus(),instant(row.getCompletedAt()),null,instant(row.getAcceptedAt()),instant(row.getDeadline()),row.getFailureCode());}
        var c=coupons.selectById(row.getUserCouponId());
        if(c==null||!c.getStudentId().equals(row.getStudentId())||!c.getCampaignId().equals(row.getCampaignId())){throw new IllegalStateException("Inconsistent claim fact");}
        return new ClaimResult(row.getId().toString(),row.getCampaignId().toString(),"SUCCEEDED",instant(row.getCompletedAt()),view(c,now),instant(row.getAcceptedAt()),instant(row.getDeadline()),null);
    }
    private void valid(AuthSessionState s){if(s==null){throw TokenService.failure("TOKEN_INVALID","会话不存在");}if(!clock.instant().isBefore(instant(s.expiresAt()))){throw TokenService.failure("SESSION_EXPIRED","登录已过期");}if(s.revokedAt()!=null){throw TokenService.failure("SESSION_REVOKED","会话已退出");}}
    private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MILLIS),ZoneOffset.UTC);}
    private static Instant instant(LocalDateTime t){return t==null?null:t.toInstant(ZoneOffset.UTC);}
    private static OwnedCouponView view(OwnedCouponEntity c,LocalDateTime now){return new OwnedCouponView(c.getId().toString(),c.getCampaignId().toString(),c.getShopId().toString(),c.getNameSnapshot(),c.getThresholdAmount(),c.getReductionAmount(),instant(c.getUseStartAt()),instant(c.getUseEndAt()),c.getSourceCampaignVersion().toString(),instant(c.getClaimedAt()),"AVAILABLE".equals(c.getState())&&!now.isBefore(c.getUseEndAt())?"EXPIRED":c.getState());}
    private static void role(String role){if(!"STUDENT".equals(role)){throw error(HttpStatus.FORBIDDEN,"ROLE_FORBIDDEN","仅正常学生访问本人优惠券");}}
    private static void one(int n){if(n!=1){throw new IllegalStateException("Unexpected claim write count");}}
    private static BusinessException missing(){return error(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND","可见活动或本人领取结果不存在");}
    private static BusinessException conflict(String c,String m){return error(HttpStatus.CONFLICT,c,m);}
    private static BusinessException error(HttpStatus s,String c,String m){return new BusinessException(s,c,m);}
}
