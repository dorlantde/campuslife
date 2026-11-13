package com.campuslife.coupon.service;
import com.campuslife.coupon.mapper.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
@Component @Profile("mysql") @ConditionalOnProperty(name="campuslife.coupon-redis.enabled",havingValue="true")
public class CouponRedisRepairJob {
 private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(CouponRedisRepairJob.class);
 private final DispatchGateMapper gates;private final CouponRecoveryMapper rows;private final CouponRedisRecovery recovery;private final Clock clock;private final boolean enabled;
 private final AtomicBoolean running=new AtomicBoolean(),closed=new AtomicBoolean();
 private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new SynchronousQueue<>(),Thread.ofPlatform().daemon(true).name("coupon-recovery-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
 private long gateCursor,terminalCursor;private final Map<Long,String> scanCursors=new HashMap<>();
 public CouponRedisRepairJob(DispatchGateMapper gates,CouponRecoveryMapper rows,CouponRedisRecovery recovery,Clock clock,@Value("${campuslife.coupon-redis.scheduling-enabled:true}") boolean enabled){this.gates=gates;this.rows=rows;this.recovery=recovery;this.clock=clock;this.enabled=enabled;}
 @Scheduled(initialDelay=1000,fixedDelay=1000) public void scheduled(){
  // The application's default scheduler must remain free for order expiry and outbox polling.
  if(!enabled||closed.get()||!running.compareAndSet(false,true)){return;}
  try{worker.execute(()->{try{tick();}catch(RuntimeException ex){log.warn("Coupon recovery round deferred ({})",ex.getClass().getSimpleName());}finally{running.set(false);}});}
  catch(RejectedExecutionException ex){running.set(false);}
 }
 @jakarta.annotation.PreDestroy public void close(){
  closed.set(true);worker.shutdownNow();try{if(!worker.awaitTermination(2,TimeUnit.SECONDS)){log.warn("Coupon recovery shutdown reached bounded wait; durable lease remains fenced");}}catch(InterruptedException ex){Thread.currentThread().interrupt();}
 }
 public void tick(){
  if(Thread.currentThread().isInterrupted()){return;}
  var page=gates.page(gateCursor);if(page.isEmpty()){gateCursor=0;}else{
   for(var g:page){
    if(Thread.currentThread().isInterrupted()){return;}
    gateCursor=g.campaignId();
    try{
     if("REBUILDING".equals(g.phase())&&clock.instant().isBefore(g.leaseUntil().toInstant(ZoneOffset.UTC))){continue;}
     if(!recovery.health(g)){recovery.rebuild(g.campaignId(),g.generation());scanCursors.remove(g.campaignId());}
     else{scanCursors.put(g.campaignId(),recovery.reap(g.campaignId(),scanCursors.getOrDefault(g.campaignId(),"0")));}
    }catch(RuntimeException ex){log.warn("Coupon Redis recovery deferred for campaign {} ({})",g.campaignId(),ex.getClass().getSimpleName());}
   }
  }
  if(Thread.currentThread().isInterrupted()){return;}
  var due=rows.unsettled(terminalCursor);if(due.isEmpty()){terminalCursor=0;}
  for(long id:due){if(Thread.currentThread().isInterrupted()){return;}terminalCursor=id;try{recovery.settle(id);}catch(RuntimeException ex){log.warn("Coupon settlement deferred for claim {} ({})",id,ex.getClass().getSimpleName());}}
 }
}
