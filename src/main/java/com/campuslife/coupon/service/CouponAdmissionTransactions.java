package com.campuslife.coupon.service;

import com.campuslife.auth.mapper.*;
import com.campuslife.auth.mapper.model.AuthSessionState;
import com.campuslife.auth.security.*;
import com.campuslife.common.exception.BusinessException;
import com.campuslife.coupon.entity.*;
import com.campuslife.coupon.mapper.*;
import com.campuslife.merchant.mapper.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Short SQL phases only. Reservation IO belongs to the caller between these transactions. */
@Service @Profile("mysql")
public class CouponAdmissionTransactions {
 public record Plan(long campaign,long shop,DispatchGateMapper.Gate gate,CouponClaimService.Creation prior) {}
 private final AccountMapper accounts;private final AuthSessionMapper sessions;private final ShopMapper shops;
 private final PublicShopMapper visible;private final CampaignMapper campaigns;private final DispatchGateMapper gates;
 private final ClaimRequestMapper requests;private final OwnedCouponMapper coupons;private final CouponClaimService claims;
 private final CouponIssueMessage messages;private final Clock clock;
 public CouponAdmissionTransactions(AccountMapper accounts,AuthSessionMapper sessions,ShopMapper shops,PublicShopMapper visible,CampaignMapper campaigns,DispatchGateMapper gates,ClaimRequestMapper requests,OwnedCouponMapper coupons,CouponClaimService claims,CouponIssueMessage messages,Clock clock){this.accounts=accounts;this.sessions=sessions;this.shops=shops;this.visible=visible;this.campaigns=campaigns;this.gates=gates;this.requests=requests;this.coupons=coupons;this.claims=claims;this.messages=messages;this.clock=clock;}
 @Transactional(isolation=Isolation.READ_COMMITTED)
 public Plan plan(AuthPrincipal p,long id,String key){
  var session=identity(p);var prior=prior(p,id,key,session);if(prior!=null){return new Plan(id,0,null,prior);}
  var location=campaigns.selectById(id);if(location==null||shops.share(location.getShopId())==null){throw missing();}
  var gate=gates.share(id);open(gate);
  // Different mapped query deliberately avoids a cached pre-lock campaign snapshot.
  var c=campaigns.findOwn(id,location.getShopId());if(c==null){throw missing();}
  if("ASYNC".equals(gate.mode())){eligible(p,c,now());}valid(session);
  return new Plan(id,c.getShopId(),gate,null);
 }
 @Transactional(isolation=Isolation.READ_COMMITTED)
 public CouponClaimService.Creation replay(AuthPrincipal p,long id,String key){var s=identity(p);return prior(p,id,key,s);}
 @Transactional(isolation=Isolation.READ_COMMITTED)
 public CouponClaimService.Creation accept(AuthPrincipal p,String key,Plan plan,String reservation){
  var s=identity(p);var prior=prior(p,plan.campaign(),key,s);if(prior!=null){return prior;}
  if(shops.share(plan.shop())==null){throw missing();}var g=gates.share(plan.campaign());open(g);
  if(!"ASYNC".equals(g.mode())||g.generation()!=plan.gate().generation()||!Objects.equals(g.redisRunId(),plan.gate().redisRunId())){throw unavailable();}
  var c=campaigns.findOwn(plan.campaign(),plan.shop());if(c==null){throw missing();}
  var now=now();eligible(p,c,now);valid(s);
  var r=new ClaimRequestEntity();r.setStudentId(p.accountId());r.setCampaignId(plan.campaign());r.setRequestKey(key);
  r.setStatus("PENDING");r.setSourceMode("ASYNC");r.setCreatedAt(now);r.setAcceptedAt(now);
  var cap=now.plusMinutes(5);r.setDeadline(cap.isBefore(c.getUseEndAt())?cap:c.getUseEndAt());
  r.setReservationId(reservation);r.setAcceptedGeneration(g.generation());r.setSourceCampaignVersion(c.getVersion());
  if(requests.insert(r)!=1){throw new IllegalStateException("Admission write failed");}messages.append(r);valid(s);
  return new CouponClaimService.Creation(claims.result(r,now),false);
 }
 private CouponClaimService.Creation prior(AuthPrincipal p,long id,String key,AuthSessionState s){
  var r=requests.findKey(p.accountId(),key);if(r==null){return null;}
  if(r.getCampaignId()!=id){throw conflict("IDEMPOTENCY_KEY_CONFLICT","原请求键对应其他活动");}
  var result=claims.result(r,now());valid(s);return new CouponClaimService.Creation(result,true);
 }
 private void eligible(AuthPrincipal p,CouponCampaignEntity c,LocalDateTime now){
  if(coupons.findCampaign(c.getId(),p.accountId())!=null){throw conflict("COUPON_ALREADY_CLAIMED","每账号每活动仅可成功领取一次");}
  if(requests.active(p.accountId(),c.getId())!=null){throw conflict("COUPON_CLAIM_PENDING","已有进行中的领取请求");}
  if(visible.visible(c.getShopId())==null){throw missing();}
  if(!"PUBLISHED".equals(c.getStatus())||now.isBefore(c.getClaimStartAt())||!now.isBefore(c.getClaimEndAt())||!now.isBefore(c.getUseEndAt())){throw conflict("COUPON_NOT_CLAIMABLE","活动未开放、已停止或领取窗口已结束");}
  if(c.getPublishedAt()==null||now.isBefore(c.getPublishedAt())||now.isBefore(c.getUpdatedAt())){throw conflict("COUPON_TIME_INVALID","当前时间早于活动事实");}
 }
 private AuthSessionState identity(AuthPrincipal p){
  role(p.account().role());var a=accounts.lockById(p.accountId());if(a==null){throw TokenService.failure("TOKEN_INVALID","账号不存在");}
  if(!"ACTIVE".equals(a.getStatus())){throw new BusinessException(HttpStatus.FORBIDDEN,"ACCOUNT_DISABLED","账号已停用");}role(a.getRole());
  var s=sessions.lockOwnSession(p.accountId(),p.jti());valid(s);return s;
 }
 private void valid(AuthSessionState s){if(s==null){throw TokenService.failure("TOKEN_INVALID","会话不存在");}if(s.revokedAt()!=null){throw TokenService.failure("SESSION_REVOKED","会话已退出");}if(!clock.instant().isBefore(s.expiresAt().toInstant(ZoneOffset.UTC))){throw TokenService.failure("SESSION_EXPIRED","登录已过期");}}
 private static void role(String role){if(!"STUDENT".equals(role)){throw new BusinessException(HttpStatus.FORBIDDEN,"ROLE_FORBIDDEN","仅正常学生访问本人优惠券");}}
 private static void open(DispatchGateMapper.Gate g){if(g==null){throw new IllegalStateException("Missing campaign dispatch gate");}if(!"OPEN".equals(g.phase())){throw unavailable();}}
 static BusinessException unavailable(){return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,"COUPON_ADMISSION_UNAVAILABLE","当前活动领取暂不可用，请使用原请求键查询或重试");}
 private static BusinessException missing(){return new BusinessException(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND","可见活动不存在");}
 private static BusinessException conflict(String code,String text){return new BusinessException(HttpStatus.CONFLICT,code,text);}
 private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MILLIS),ZoneOffset.UTC);}
}
