package com.campuslife.coupon.service;
import com.campuslife.coupon.service.CouponMaintenanceTransactions.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** No HTTP endpoint: invoked only by the explicit local maintenance process. */
@Service @Profile("mysql") @Transactional(propagation=Propagation.NEVER)
public class CouponMaintenance {
 private final CouponMaintenanceTransactions tx;private final ObjectProvider<CouponRedisRecovery> recovery;private final boolean messaging;
 public CouponMaintenance(CouponMaintenanceTransactions tx,ObjectProvider<CouponRedisRecovery> recovery,@Value("${campuslife.messaging.enabled:false}") boolean messaging){this.tx=tx;this.recovery=recovery;this.messaging=messaging;}
 public Inspection run(String action,long id,Long version,boolean upgraded){
  if(id<1){throw new Refused("INVALID_ID");}if("Inspect".equals(action)){return tx.inspect(id);}
  if(version==null||version<0){throw new Refused("CONTROL_VERSION_REQUIRED");}
  switch(action){
   case "Pause"->tx.pause(id,version);
   case "Async"->{if(!upgraded){throw new Refused("ALL_INSTANCES_UPGRADED_REQUIRED");}available();tx.mode(id,version,"ASYNC");}
   case "Resume"->{var s=tx.inspect(id);if(s.controlVersion()!=version){throw new Refused("CONTROL_VERSION_CHANGED");}
    if("ASYNC".equals(s.mode())){var r=available();if(!"PAUSED".equals(s.phase())){throw new Refused("PAUSE_FIRST");}
     r.preflightMaintenanceConnection();r.complete(tx.resumeAsync(id,version));}else{tx.resumeSync(id,version);}}
   case "Sync"->{var s=tx.inspect(id);if(s.controlVersion()!=version){throw new Refused("CONTROL_VERSION_CHANGED");}
    if(!"PAUSED".equals(s.phase())){throw new Refused("PAUSE_FIRST");}if(s.pending()!=0){throw new Refused("PENDING_MUST_DRAIN");}
    if("ASYNC".equals(s.mode())){
     // Rebuild a new PAUSED generation before removing Redis from this activity's admission path.
     // Atomic begin also checks the operator version; stale operators cannot freeze newer OPEN intent.
     var r=availableRedis();r.preflightMaintenanceConnection();var t=tx.prepareSync(id,version);r.complete(t);tx.mode(id,Math.addExact(version,2),"SYNC");
    }else{tx.mode(id,version,"SYNC");}}
   default->throw new Refused("INVALID_ACTION");
  }return tx.inspect(id);
 }
 private CouponRedisRecovery available(){if(!messaging){throw new Refused("MESSAGING_MUST_BE_ENABLED");}return availableRedis();}
 private CouponRedisRecovery availableRedis(){var r=recovery.getIfAvailable();if(r==null){throw new Refused("COUPON_REDIS_MUST_BE_ENABLED");}return r;}
}
