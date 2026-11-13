package com.campuslife.coupon.service;
import com.campuslife.coupon.mapper.*;
import com.campuslife.coupon.service.CouponRecoveryTransactions.Rebuild;
import com.campuslife.merchant.mapper.ShopMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Local operator control, optimistic controlVersion plus the same shop -> gate lock order. */
@Service @Profile("mysql")
public class CouponMaintenanceTransactions {
 public static class Refused extends RuntimeException {public Refused(String code){super(code);}}
 public record Inspection(long campaign,String mode,String phase,long generation,long controlVersion,long pending,int issued,long actualCoupons) {}
 private final CampaignMapper campaigns;private final DispatchGateMapper gates;private final ShopMapper shops;private final CouponMaintenanceMapper rows;private final CouponRecoveryTransactions recovery;
 public CouponMaintenanceTransactions(CampaignMapper campaigns,DispatchGateMapper gates,ShopMapper shops,CouponMaintenanceMapper rows,CouponRecoveryTransactions recovery){this.campaigns=campaigns;this.gates=gates;this.shops=shops;this.rows=rows;this.recovery=recovery;}
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public Inspection inspect(long id){var c=campaigns.selectById(id);var g=gates.find(id);if(c==null||g==null){throw new Refused("NOT_FOUND");}return new Inspection(id,g.mode(),g.phase(),g.generation(),g.controlVersion(),rows.pending(id),c.getIssuedCount(),rows.issued(id));}
 @Transactional(isolation=Isolation.READ_COMMITTED)
 public void pause(long id,long version){var g=locked(id,version);if("REBUILDING".equals(g.phase())){throw new Refused("REBUILDING_WAIT_FOR_LEASE");}if("PAUSED".equals(g.phase())){return;}one(rows.change(id,version,g.mode(),"PAUSED"));}
 @Transactional(isolation=Isolation.READ_COMMITTED)
 public void mode(long id,long version,String target){
  if(!"SYNC".equals(target)&&!"ASYNC".equals(target)){throw new Refused("INVALID_MODE");}
  var g=locked(id,version);paused(g);balanced(id);if(target.equals(g.mode())){return;}one(rows.change(id,version,target,"PAUSED"));
 }
 @Transactional(isolation=Isolation.READ_COMMITTED)
 public Rebuild resumeAsync(long id,long version){
  var g=locked(id,version);paused(g);if(!"ASYNC".equals(g.mode())){throw new Refused("MODE_CHANGED");}
  var t=recovery.begin(id,g.generation());one(rows.resumeOpen(id,t.token()));return new Rebuild(t.campaign(),t.shop(),t.generation(),t.token(),"OPEN",t.total(),t.issued());
 }
 @Transactional(isolation=Isolation.READ_COMMITTED)
 public void resumeSync(long id,long version){var g=locked(id,version);paused(g);if(!"SYNC".equals(g.mode())){throw new Refused("MODE_CHANGED");}balanced(id);one(rows.change(id,version,"SYNC","OPEN"));}
 @Transactional(isolation=Isolation.READ_COMMITTED)
 public Rebuild prepareSync(long id,long version){var g=locked(id,version);paused(g);if(!"ASYNC".equals(g.mode())){throw new Refused("MODE_CHANGED");}balanced(id);return recovery.begin(id,g.generation());}
 private DispatchGateMapper.Gate locked(long id,long version){
  var c=campaigns.selectById(id);if(c==null||shops.share(c.getShopId())==null){throw new Refused("NOT_FOUND");}
  var g=gates.lock(id);if(g==null||g.controlVersion()!=version){throw new Refused("CONTROL_VERSION_CHANGED");}return g;
 }
 private void balanced(long id){
  if(rows.pending(id)!=0){throw new Refused("PENDING_MUST_DRAIN");}
  // Distinct query avoids a pre-gate MyBatis cached campaign read.
  var c=campaigns.selectById(id);
  // campaign current lock follows gate; issuance cannot advance underneath gate X.
  c=campaigns.lockOwn(id,c.getShopId());if(rows.issued(id)!=c.getIssuedCount()){throw new Refused("ISSUED_COUNT_MISMATCH");}
 }
 private static void paused(DispatchGateMapper.Gate g){if(!"PAUSED".equals(g.phase())){throw new Refused("PAUSE_FIRST");}}
 private static void one(int n){if(n!=1){throw new Refused("CONTROL_WRITE_REFUSED");}}
}
