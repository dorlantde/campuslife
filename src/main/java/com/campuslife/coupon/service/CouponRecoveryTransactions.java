package com.campuslife.coupon.service;

import com.campuslife.auth.mapper.AccountMapper;
import com.campuslife.coupon.entity.ClaimRequestEntity;
import com.campuslife.coupon.mapper.*;
import com.campuslife.merchant.mapper.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Database decisions only: this class never holds a SQL transaction across Redis IO. */
@Service @Profile("mysql")
public class CouponRecoveryTransactions {
 public static class Fenced extends RuntimeException { public Fenced(){super("Coupon recovery lease/generation no longer active");} }
 public record Rebuild(long campaign,long shop,long generation,String token,String resume,int total,int issued) {}
 public record Reservation(long campaign,long generation,String id,long student,String key,long at,String state) {}
 public record Settlement(Long claim,long campaign,long shop,long generation,String run,String reservation,long student,String key,String status,Long coupon) {}
 private final AccountMapper accounts;private final ShopMapper shops;private final PublicShopMapper visible;
 private final CampaignMapper campaigns;private final DispatchGateMapper gates;private final ClaimRequestMapper requests;private final CouponRecoveryMapper recovery;private final Clock clock;
 public CouponRecoveryTransactions(AccountMapper accounts,ShopMapper shops,PublicShopMapper visible,CampaignMapper campaigns,DispatchGateMapper gates,ClaimRequestMapper requests,CouponRecoveryMapper recovery,Clock clock){this.accounts=accounts;this.shops=shops;this.visible=visible;this.campaigns=campaigns;this.gates=gates;this.requests=requests;this.recovery=recovery;this.clock=clock;}
 @Transactional(isolation=Isolation.READ_COMMITTED)
 public Rebuild begin(long campaign){return begin(campaign,null);}
 @Transactional(isolation=Isolation.READ_COMMITTED)
 public Rebuild begin(long campaign,Long expectedGeneration){
  var location=campaigns.selectById(campaign);if(location==null||shops.share(location.getShopId())==null){throw new IllegalStateException("Missing campaign shop");}
  var g=gates.lock(campaign);if(g==null||!"ASYNC".equals(g.mode())){throw new Fenced();}
  if(expectedGeneration!=null&&g.generation()!=expectedGeneration){throw new Fenced();}
  var now=now();if("REBUILDING".equals(g.phase())&&now.isBefore(g.leaseUntil())){throw new Fenced();}
  if(g.generation()==Long.MAX_VALUE||g.controlVersion()>=Long.MAX_VALUE-1){throw new Fenced();}
  String resume="REBUILDING".equals(g.phase())?g.resumePhase():g.phase();String token=UUID.randomUUID().toString().replace("-","");
  one(gates.begin(campaign,g.generation()+1,token,now.plusSeconds(30),resume));
  var c=campaigns.lockOwn(campaign,location.getShopId());
  return new Rebuild(campaign,location.getShopId(),g.generation()+1,token,resume,c.getTotalQuantity(),c.getIssuedCount());
 }
 @Transactional(isolation=Isolation.READ_COMMITTED)
 public void renew(Rebuild t){shops.share(t.shop());lease(gates.lock(t.campaign()),t);var now=now();one(gates.renew(t.campaign(),t.generation(),t.token(),now,now.plusSeconds(30)));}
 @Transactional(isolation=Isolation.READ_COMMITTED)
 public void finish(Rebuild t,String run){shops.share(t.shop());lease(gates.lock(t.campaign()),t);one(gates.finish(t.campaign(),t.generation(),t.token(),run,now()));}
 @Transactional(isolation=Isolation.READ_COMMITTED)
 public List<ClaimRequestEntity> pendingOrdered(Rebuild t,LocalDateTime after,long id){lease(gates.share(t.campaign()),t);return recovery.pendingOrdered(t.campaign(),after,id);}
 @Transactional(isolation=Isolation.READ_COMMITTED)
 public List<ClaimRequestEntity> pendingPage(Rebuild t,long after){lease(gates.share(t.campaign()),t);return recovery.pendingPage(t.campaign(),after);}
 @Transactional(isolation=Isolation.READ_COMMITTED)
 public List<CouponRecoveryMapper.Issued> issuedPage(Rebuild t,long after){lease(gates.share(t.campaign()),t);return recovery.issued(t.campaign(),after);}
 @Transactional(isolation=Isolation.READ_COMMITTED)
 public boolean reconcile(Rebuild t,long id,boolean capacity){
  var initial=requests.selectById(id);if(initial==null||initial.getCampaignId()!=t.campaign()){throw new Fenced();}
  var account=accounts.lockById(initial.getStudentId());if(account==null){throw new IllegalStateException("Missing admission account");}
  shops.share(t.shop());lease(gates.share(t.campaign()),t);var r=requests.lock(id);var now=now();
  if(!"PENDING".equals(r.getStatus())){return false;}if(now.isBefore(r.getAcceptedAt())){throw new Fenced();}
  String failure=null;
  if(!now.isBefore(r.getDeadline())){failure="PROCESSING_EXPIRED";}
  else if(!"ACTIVE".equals(account.getStatus())||!"STUDENT".equals(account.getRole())||visible.visible(t.shop())==null){failure="QUALIFICATION_CHANGED";}
  else if(!capacity){failure="SOLD_OUT";}
  if(failure!=null){one(requests.finish(id,"FAILED",null,failure,now));return false;}return true;
 }
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public Settlement settlement(long id){
  var r=requests.selectById(id);if(r==null||!"ASYNC".equals(r.getSourceMode())||"PENDING".equals(r.getStatus())){return null;}
  var c=campaigns.selectById(r.getCampaignId());var g=gates.find(r.getCampaignId());
  if(c==null||g==null||!"ASYNC".equals(g.mode())||"REBUILDING".equals(g.phase())){return null;}
  return fact(r,c.getShopId(),g);
 }
 @Transactional(isolation=Isolation.READ_COMMITTED)
 public boolean checkpoint(Settlement t){
  if(t.claim()==null){return false;}if(accounts.lockById(t.student())==null){throw new Fenced();}shops.share(t.shop());var g=gates.share(t.campaign());
  if(g==null||g.generation()!=t.generation()||!"ASYNC".equals(g.mode())||"REBUILDING".equals(g.phase())||!Objects.equals(g.redisRunId(),t.run())){return false;}
  var r=requests.lock(t.claim());if(r==null||!r.getStatus().equals(t.status())||!Objects.equals(r.getReservationId(),t.reservation())){return false;}
  return recovery.checkpoint(t.claim(),t.generation(),t.status(),t.reservation())==1;
 }
 @Transactional(isolation=Isolation.READ_COMMITTED)
 public Settlement orphan(Reservation v){
  var now=now();if(now.toInstant(ZoneOffset.UTC).toEpochMilli()-v.at()<30000){return null;}
  if(accounts.lockById(v.student())==null){throw new IllegalStateException("Unknown reservation account");}
  var c=campaigns.selectById(v.campaign());if(c==null||shops.share(c.getShopId())==null){throw new Fenced();}
  var g=gates.share(v.campaign());if(g==null||!"ASYNC".equals(g.mode())||"REBUILDING".equals(g.phase())||g.generation()!=v.generation()){throw new Fenced();}
  var prior=recovery.lockKey(v.student(),v.key());
  if(prior!=null){
   if(prior.getCampaignId()!=v.campaign()||!Objects.equals(prior.getReservationId(),v.id())){
    // The immutable global key already prevents this orphan identity from ever being admitted.
    return new Settlement(null,v.campaign(),c.getShopId(),g.generation(),g.redisRunId(),v.id(),v.student(),v.key(),"FAILED",null);
   }
   return "PENDING".equals(prior.getStatus())?null:fact(prior,c.getShopId(),g);
  }
  var r=new ClaimRequestEntity();r.setStudentId(v.student());r.setCampaignId(v.campaign());r.setRequestKey(v.key());r.setStatus("FAILED");r.setSourceMode("ASYNC");r.setCreatedAt(now);r.setCompletedAt(now);r.setFailureCode("ORPHAN_EXPIRED");r.setReservationId(v.id());r.setAcceptedGeneration(v.generation());one(requests.insert(r));return fact(r,c.getShopId(),g);
 }
 private Settlement fact(ClaimRequestEntity r,long shop,DispatchGateMapper.Gate g){return new Settlement(r.getId(),r.getCampaignId(),shop,g.generation(),g.redisRunId(),r.getReservationId(),r.getStudentId(),r.getRequestKey(),r.getStatus(),r.getUserCouponId());}
 private void lease(DispatchGateMapper.Gate g,Rebuild t){if(g==null||!"REBUILDING".equals(g.phase())||g.generation()!=t.generation()||!Objects.equals(g.rebuildToken(),t.token())||!now().isBefore(g.leaseUntil())){throw new Fenced();}}
 private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MILLIS),ZoneOffset.UTC);}
 private static void one(int n){if(n!=1){throw new Fenced();}}
}
