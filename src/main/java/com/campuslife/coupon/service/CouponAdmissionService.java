package com.campuslife.coupon.service;

import com.campuslife.auth.security.AuthPrincipal;
import com.campuslife.common.exception.BusinessException;
import com.campuslife.coupon.dto.CouponClaim;
import com.campuslife.coupon.redis.CouponRedisStore;
import java.time.Clock;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Three phases: SQL precheck -> Redis reservation -> SQL durable admission. */
@Service @Profile("mysql") @Transactional(propagation=Propagation.NEVER)
public class CouponAdmissionService {
 private final CouponAdmissionTransactions tx;private final CouponClaimService sync;private final ObjectProvider<CouponRedisStore> redis;private final Clock clock;private final boolean messaging;
 public CouponAdmissionService(CouponAdmissionTransactions tx,CouponClaimService sync,ObjectProvider<CouponRedisStore> redis,Clock clock,@Value("${campuslife.messaging.enabled:false}") boolean messaging){this.tx=tx;this.sync=sync;this.redis=redis;this.clock=clock;this.messaging=messaging;}
 public CouponClaimService.Creation claim(AuthPrincipal p,String key,CouponClaim body){
  if(key==null||!key.matches("[A-Za-z0-9_-]{1,64}")){throw new BusinessException(HttpStatus.BAD_REQUEST,"IDEMPOTENCY_KEY_REQUIRED","须提供1～64位合法Idempotency-Key");}
  if(body==null||body.campaignId()==null||!body.campaignId().isString()){throw CampaignPolicy.invalid();}long id=CampaignPolicy.id(body.campaignId().asString());
  var plan=tx.plan(p,id,key);if(plan.prior()!=null){return plan.prior();}
  if("SYNC".equals(plan.gate().mode())){return sync.claim(p,key,body);}
  var store=redis.getIfAvailable();if(store==null||!messaging){throw CouponAdmissionTransactions.unavailable();}
  CouponRedisStore.Reserved reservation;
  try{reservation=store.reserve(id,plan.gate().generation(),plan.gate().redisRunId(),p.accountId(),key,UUID.randomUUID().toString().replace("-",""),clock.instant());}
  catch(RuntimeException ex){var prior=replay(p,id,key);if(prior!=null){return prior;}throw CouponAdmissionTransactions.unavailable();}
  if(!"HELD".equals(reservation.status())){
   var prior=replay(p,id,key);if(prior!=null){return prior;}
   String code=switch(reservation.status()){case "SOLD_OUT"->"COUPON_SOLD_OUT";case "CLAIMED","SPENT"->"COUPON_ALREADY_CLAIMED";case "PENDING"->"COUPON_CLAIM_PENDING";default->null;};
   if(code==null){throw CouponAdmissionTransactions.unavailable();}throw new BusinessException(HttpStatus.CONFLICT,code,"当前无法领取，请查询原领取结果");
  }
  try{return tx.accept(p,key,plan,reservation.reservation());}
  catch(RuntimeException ex){
   // Unknown commit/rollback is never grounds for INCR. Durable replay wins; otherwise C arbitrates the orphan.
   var prior=replay(p,id,key);if(prior!=null){return prior;}
   if(ex instanceof BusinessException business){throw business;}throw CouponAdmissionTransactions.unavailable();
  }
 }
 private CouponClaimService.Creation replay(AuthPrincipal p,long id,String key){
  try{return tx.replay(p,id,key);}catch(BusinessException business){throw business;}catch(RuntimeException unknown){throw CouponAdmissionTransactions.unavailable();}
 }
}
