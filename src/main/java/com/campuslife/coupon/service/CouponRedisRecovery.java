package com.campuslife.coupon.service;

import com.campuslife.coupon.mapper.*;
import com.campuslife.coupon.redis.CouponRedisStore;
import com.campuslife.coupon.service.CouponRecoveryTransactions.*;
import io.lettuce.core.RedisCommandTimeoutException;
import java.time.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service @Profile("mysql")
@ConditionalOnProperty(name="campuslife.coupon-redis.enabled",havingValue="true")
@Transactional(propagation=Propagation.NEVER)
public class CouponRedisRecovery {
 private final CouponRecoveryTransactions tx;private final CouponRedisStore store;private final DispatchGateMapper gates;private final Clock clock;
 public CouponRedisRecovery(CouponRecoveryTransactions tx,CouponRedisStore store,DispatchGateMapper gates,Clock clock){this.tx=tx;this.store=store;this.gates=gates;this.clock=clock;}
 /** Explicit maintenance only: initialize the connection before committing a rebuild ticket. */
 public void preflightMaintenanceConnection(){
  for(int attempt=0;attempt<2;attempt++){
   maintenanceNotInterrupted();
   try{store.runId();maintenanceNotInterrupted();return;}
   catch(RedisConnectionFailureException failure){
    maintenanceNotInterrupted();
    if(attempt==0&&connectionTimedOut(failure)){continue;}
    throw new CouponMaintenanceTransactions.Refused("COUPON_REDIS_PREFLIGHT_FAILED");
   }
  }
 }
 private static boolean connectionTimedOut(Throwable failure){
  // Bound traversal also handles malformed/cyclic cause chains by failing closed.
  for(int depth=0;failure!=null&&depth<16;depth++,failure=failure.getCause()){
   if(failure instanceof RedisCommandTimeoutException){return true;}
  }return false;
 }
 private static void maintenanceNotInterrupted(){
  if(Thread.currentThread().isInterrupted()){throw new CouponMaintenanceTransactions.Refused("COUPON_REDIS_PREFLIGHT_FAILED");}
 }
 public Rebuild rebuild(long campaign){return rebuild(campaign,null);}
 public Rebuild rebuild(long campaign,Long expectedGeneration){
  checkInterrupted();var t=tx.begin(campaign,expectedGeneration);return complete(t);
 }
 /** Continue an already committed maintenance/recovery ticket; lease fences every SQL step. */
 public Rebuild complete(Rebuild t){
  checkInterrupted();int kept=0;long last=0;LocalDateTime after=LocalDateTime.of(1000,1,1,0,0);
  while(true){
   checkInterrupted();
   var rows=tx.pendingOrdered(t,after,last);if(rows.isEmpty()){break;}
   for(var r:rows){checkInterrupted();if(tx.reconcile(t,r.getId(),kept<t.total()-t.issued())){kept++;}after=r.getAcceptedAt();last=r.getId();}
   tx.renew(t);
  }
  // Any exception from here leaves the durable gate REBUILDING. A new owner must wait for its lease.
  String run=store.runId();store.prepare(t,run);store.initialize(t,run,kept);int batch=0;long id=0;
  while(true){
   checkInterrupted();
   var rows=tx.issuedPage(t,id);if(rows.isEmpty()){break;}var values=new ArrayList<Map<String,Object>>();
   for(var r:rows){var v=entry(t,"SPENT",r.studentId());v.put("coupon",Long.toString(r.couponId()));if(r.reservationId()!=null){v.put("id",r.reservationId());v.put("key",r.requestKey());v.put("at",r.createdAt().toInstant(ZoneOffset.UTC).toEpochMilli());}values.add(v);id=r.couponId();}
   tx.renew(t);store.seed(t,run,batch++,values);
  }
  id=0;
  while(true){
   checkInterrupted();
   var rows=tx.pendingPage(t,id);if(rows.isEmpty()){break;}var values=new ArrayList<Map<String,Object>>();
   for(var r:rows){var v=entry(t,"HELD",r.getStudentId());v.put("id",r.getReservationId());v.put("key",r.getRequestKey());v.put("at",r.getCreatedAt().toInstant(ZoneOffset.UTC).toEpochMilli());values.add(v);id=r.getId();}
   tx.renew(t);store.seed(t,run,batch++,values);
  }
  checkInterrupted();tx.renew(t);store.activate(t,run);store.inspect(t.campaign(),t.generation(),run);checkInterrupted();tx.finish(t,run);return t;
 }
 private static void checkInterrupted(){if(Thread.currentThread().isInterrupted()){throw new CouponRecoveryTransactions.Fenced();}}
 private static Map<String,Object> entry(Rebuild t,String kind,long student){var v=new LinkedHashMap<String,Object>();v.put("kind",kind);v.put("campaign",Long.toString(t.campaign()));v.put("generation",Long.toString(t.generation()));v.put("student",Long.toString(student));return v;}
 public boolean settle(long claim){
  var fact=tx.settlement(claim);if(fact==null){return false;}
  try{store.settle(fact,clock.instant());return tx.checkpoint(fact);}
  catch(RuntimeException ex){attemptRepair(fact.campaign(),fact.generation());throw ex;}
 }
 public String reap(long campaign,String cursor){
  var gate=gates.find(campaign);if(gate==null||!"ASYNC".equals(gate.mode())||"REBUILDING".equals(gate.phase())){return "0";}
  try{
   var page=store.scan(campaign,gate.generation(),gate.redisRunId(),cursor);
   for(var r:page.reservations()){
    if(!"HELD".equals(r.state())||clock.instant().toEpochMilli()-r.at()<30000){continue;}
    var fact=tx.orphan(r);if(fact!=null){store.settle(fact,clock.instant());tx.checkpoint(fact);}
   }
   return page.cursor();
  }catch(RuntimeException ex){attemptRepair(campaign,gate.generation());throw ex;}
 }
 public boolean health(DispatchGateMapper.Gate gate){
  if("REBUILDING".equals(gate.phase())){return false;}
  try{store.inspect(gate.campaignId(),gate.generation(),gate.redisRunId());return true;}catch(RuntimeException ex){return false;}
 }
 private void attemptRepair(long campaign,long generation){try{rebuild(campaign,generation);}catch(RuntimeException deferred){/* Durable REBUILDING/lease keeps admission closed; the next monitor retries. */}}
}
