package com.campuslife.coupon.service;

import com.campuslife.auth.mapper.*;
import com.campuslife.auth.mapper.model.*;
import com.campuslife.auth.security.*;
import com.campuslife.common.exception.BusinessException;
import com.campuslife.coupon.dto.*;
import com.campuslife.coupon.entity.CouponCampaignEntity;
import com.campuslife.coupon.mapper.CampaignMapper;
import com.campuslife.coupon.mapper.DispatchGateMapper;
import com.campuslife.coupon.vo.*;
import com.campuslife.merchant.mapper.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Profile("mysql")
public class MerchantCampaignService {
    private final AccountMapper accounts;
    private final AuthSessionMapper sessions;
    private final ShopMapper shops;
    private final ShopMemberMapper members;
    private final CampaignMapper campaigns;
    private final Clock clock;
    private final DispatchGateMapper gates;
    public MerchantCampaignService(AccountMapper accounts,AuthSessionMapper sessions,ShopMapper shops,ShopMemberMapper members,CampaignMapper campaigns,DispatchGateMapper gates,Clock clock){
        this.accounts=accounts;this.sessions=sessions;this.shops=shops;this.members=members;this.campaigns=campaigns;this.gates=gates;this.clock=clock;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public CampaignPage list(AuthPrincipal principal,String pageText,String sizeText){
        requireMerchant(principal.account().role());
        int page=CampaignPolicy.page(pageText,1,Integer.MAX_VALUE),size=CampaignPolicy.page(sizeText,20,100);
        var access=readAccess(principal);
        long total=campaigns.countOwn(access.shop());
        var items=campaigns.pageOwn(access.shop(),size,((long)page-1)*size).stream().map(MerchantCampaignService::view).toList();
        valid(access.session());return new CampaignPage(page,size,total,items);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public CampaignView detail(AuthPrincipal principal,String id){
        requireMerchant(principal.account().role());long campaign=CampaignPolicy.id(id);var access=readAccess(principal);
        var row=campaigns.findOwn(campaign,access.shop());if(row==null){throw missing();}
        valid(access.session());return view(row);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public CampaignView create(AuthPrincipal principal,CampaignCreate body){
        requireMerchant(principal.account().role());var fields=CampaignPolicy.fields(body);var access=lockAccess(principal,true);
        var now=now();var row=new CouponCampaignEntity();row.setShopId(access.shop());apply(row,fields);
        row.setStatus("DRAFT");row.setIssuedCount(0);row.setVersion(0L);row.setCreatedAt(now);row.setUpdatedAt(now);
        requireOne(campaigns.insert(row));requireOne(gates.create(row.getId()));valid(access.session());return view(row);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public CampaignView patch(AuthPrincipal principal,String id,CampaignWrite body){
        requireMerchant(principal.account().role());long campaign=CampaignPolicy.id(id);
        if(body==null){throw CampaignPolicy.invalid();}long expected=CampaignPolicy.version(body.expectedVersion());var fields=CampaignPolicy.fields(body.fields());
        var access=lockAccess(principal,true);var row=locked(campaign,access.shop());
        if(row.getVersion()!=expected){throw versionConflict();}
        if(!"DRAFT".equals(row.getStatus())){throw stateConflict();}
        if(!same(row,fields)){
            incrementable(expected);var now=decisionTime(row);apply(row,fields);row.setUpdatedAt(now);
            requireOne(campaigns.updateDraft(row,expected));row.setVersion(expected+1);
        }
        valid(access.session());return view(row);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public CampaignView transition(AuthPrincipal principal,String id,CampaignVersion body,boolean publish){
        requireMerchant(principal.account().role());long campaign=CampaignPolicy.id(id);
        if(body==null){throw CampaignPolicy.invalid();}long expected=CampaignPolicy.version(body.expectedVersion());
        var access=lockAccess(principal,publish);var row=locked(campaign,access.shop());
        boolean replay=publish?"PUBLISHED".equals(row.getStatus())&&Long.valueOf(principal.accountId()).equals(row.getPublishedBy())
                &&Long.valueOf(expected).equals(row.getPublishExpectedVersion())&&expected==row.getVersion()-1
            :"STOPPED".equals(row.getStatus())&&Long.valueOf(principal.accountId()).equals(row.getStoppedBy())
                &&Long.valueOf(expected).equals(row.getStopExpectedVersion())&&expected==row.getVersion()-1;
        if(replay){valid(access.session());return view(row);}
        if(row.getVersion()!=expected){throw versionConflict();}
        if(!(publish?"DRAFT":"PUBLISHED").equals(row.getStatus())){throw stateConflict();}
        incrementable(expected);var now=decisionTime(row);
        if(publish&&!now.isBefore(row.getClaimEndAt())){throw conflict("COUPON_CLAIM_WINDOW_ENDED","领取窗口已结束，不能首次发布");}
        if(publish){requireOne(campaigns.publish(campaign,principal.accountId(),expected,now));}
        else{requireOne(campaigns.stop(campaign,principal.accountId(),expected,now));}
        valid(access.session());return view(locked(campaign,access.shop()));
    }
    private record Access(long shop,AuthSessionState session) {}
    private Access readAccess(AuthPrincipal principal){
        var account=sessions.find(principal.accountId(),principal.jti());
        if(account==null){throw TokenService.failure("TOKEN_INVALID","登录会话不存在");}
        if(!"ACTIVE".equals(account.status())){throw forbidden("ACCOUNT_DISABLED","账号已停用");}requireMerchant(account.role());
        var session=new AuthSessionState(account.accountId(),principal.jti(),account.expiresAt(),account.revokedAt());valid(session);
        var own=shops.findOwn(principal.accountId());if(own==null){throw missing();}owner(own.memberStatus());
        return new Access(own.id(),session);
    }
    private Access lockAccess(AuthPrincipal principal,boolean approved){
        // Account -> session -> shop S -> OWNER -> gate X -> campaign. Never acquire a target account after resources.
        var account=accounts.lockById(principal.accountId());if(account==null){throw TokenService.failure("TOKEN_INVALID","登录账号不存在");}
        if(!"ACTIVE".equals(account.getStatus())){throw forbidden("ACCOUNT_DISABLED","账号已停用");}requireMerchant(account.getRole());
        var session=sessions.lockOwnSession(principal.accountId(),principal.jti());valid(session);
        var own=shops.findOwn(principal.accountId());if(own==null){throw missing();}
        var shop=shops.share(own.id());if(shop==null){throw missing();}
        owner(members.lockOwnOwnerStatus(own.id(),principal.accountId()));
        if(approved&&!"APPROVED".equals(shop.getApprovalStatus())){throw forbidden("SHOP_NOT_APPROVED","店铺未获经营资格或已被暂停");}
        valid(session);return new Access(own.id(),session);
    }
    private CouponCampaignEntity locked(long id,long shop){if(campaigns.findOwn(id,shop)==null){throw missing();}if(gates.lock(id)==null){throw new IllegalStateException("Missing campaign dispatch gate");}var row=campaigns.lockOwn(id,shop);if(row==null){throw missing();}return row;}
    private void valid(AuthSessionState session){
        if(session==null){throw TokenService.failure("TOKEN_INVALID","登录会话不存在");}
        if(!clock.instant().isBefore(session.expiresAt().toInstant(ZoneOffset.UTC))){throw TokenService.failure("SESSION_EXPIRED","登录已过期");}
        if(session.revokedAt()!=null){throw TokenService.failure("SESSION_REVOKED","登录已退出");}
    }
    private LocalDateTime now(){
        var now=LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MILLIS),ZoneOffset.UTC);
        if(now.getYear()<1000||now.getYear()>9999){throw conflict("COUPON_TIME_INVALID","系统时间超出活动可保存范围");}return now;
    }
    private LocalDateTime decisionTime(CouponCampaignEntity row){
        var now=now();if(now.isBefore(row.getUpdatedAt())){throw conflict("COUPON_TIME_INVALID","系统时间早于已有活动事实");}return now;
    }
    private static void apply(CouponCampaignEntity row,CampaignPolicy.Fields f){
        row.setName(f.name());row.setThresholdAmount(f.threshold());row.setReductionAmount(f.reduction());row.setTotalQuantity(f.quantity());
        row.setClaimStartAt(f.claimStart());row.setClaimEndAt(f.claimEnd());row.setUseStartAt(f.useStart());row.setUseEndAt(f.useEnd());
    }
    private static boolean same(CouponCampaignEntity row,CampaignPolicy.Fields f){
        return row.getName().equals(f.name())&&row.getThresholdAmount()==f.threshold()&&row.getReductionAmount()==f.reduction()&&row.getTotalQuantity()==f.quantity()
            &&row.getClaimStartAt().equals(f.claimStart())&&row.getClaimEndAt().equals(f.claimEnd())&&row.getUseStartAt().equals(f.useStart())&&row.getUseEndAt().equals(f.useEnd());
    }
    private static Instant instant(LocalDateTime value){return value==null?null:value.toInstant(ZoneOffset.UTC);}
    private static String string(Long value){return value==null?null:value.toString();}
    private static CampaignView view(CouponCampaignEntity row){
        return new CampaignView(row.getId().toString(),row.getShopId().toString(),row.getName(),row.getThresholdAmount(),row.getReductionAmount(),row.getTotalQuantity(),row.getIssuedCount(),row.getStatus(),
            instant(row.getClaimStartAt()),instant(row.getClaimEndAt()),instant(row.getUseStartAt()),instant(row.getUseEndAt()),row.getVersion().toString(),instant(row.getCreatedAt()),instant(row.getUpdatedAt()),
            instant(row.getPublishedAt()),string(row.getPublishedBy()),string(row.getPublishExpectedVersion()),instant(row.getStoppedAt()),string(row.getStoppedBy()),string(row.getStopExpectedVersion()));
    }
    private static void requireMerchant(String role){if(!"MERCHANT".equals(role)){throw forbidden("ROLE_FORBIDDEN","仅商家可维护本店活动");}}
    private static void owner(String status){if(!"ACTIVE".equals(status)){throw forbidden("ROLE_FORBIDDEN","店主成员未激活或已停用");}}
    private static void incrementable(long version){if(version==Long.MAX_VALUE){throw versionConflict();}}
    private static void requireOne(int changed){if(changed!=1){throw new IllegalStateException("Unexpected campaign write row count");}}
    private static BusinessException forbidden(String code,String message){return new BusinessException(HttpStatus.FORBIDDEN,code,message);}
    private static BusinessException missing(){return new BusinessException(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND","本店活动或店铺不存在");}
    private static BusinessException versionConflict(){return conflict("CONFIG_VERSION_CONFLICT","活动版本已变化或无法递增，请重新查询");}
    private static BusinessException stateConflict(){return conflict("COUPON_CAMPAIGN_STATE_CONFLICT","活动状态不允许该操作，发布后不可修改，停止后不可恢复");}
    private static BusinessException conflict(String code,String message){return new BusinessException(HttpStatus.CONFLICT,code,message);}
}
