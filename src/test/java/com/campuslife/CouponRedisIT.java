package com.campuslife;

import com.campuslife.auth.entity.AccountEntity;
import com.campuslife.coupon.entity.ClaimRequestEntity;
import com.campuslife.coupon.mapper.*;
import com.campuslife.coupon.redis.CouponRedisStore;
import com.campuslife.coupon.service.*;
import com.campuslife.coupon.service.CouponRecoveryTransactions.*;
import io.lettuce.core.*;
import io.lettuce.core.api.StatefulRedisConnection;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("mysql")
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="CAMPUSLIFE_COUPON_REDIS_IT",matches="owned-isolated-fixture")
class CouponRedisIT extends CouponAsyncFixture {
 @MockitoSpyBean CouponRedisStore store;
 @MockitoSpyBean CouponRecoveryTransactions recoveryTx;
 @MockitoSpyBean CouponRecoveryMapper recoveryRows;
 @Autowired CouponRedisRecovery recovery;
 @Autowired CouponRedisRepairJob job;
 @Autowired @Qualifier("couponRedisTemplate") StringRedisTemplate redis;
 private RedisClient adminClient;private StatefulRedisConnection<String,String> admin;
 private String container,identity;
 @BeforeAll void requireOwnedRedis(){
  assertThat(System.getenv("CAMPUSLIFE_COUPON_REDIS_IT")).isEqualTo("owned-isolated-fixture");
  container=System.getenv("CAMPUSLIFE_COUPON_REDIS_CONTAINER");identity=System.getenv("CAMPUSLIFE_COUPON_REDIS_IDENTITY");assertThat(container).matches("[a-f0-9]{64}");assertThat(identity).matches("[a-f0-9]{32}");verifyContainer();
  var uri=RedisURI.builder().withHost("127.0.0.1").withPort(Integer.parseInt(System.getenv("COUPON_REDIS_PORT"))).withTimeout(Duration.ofSeconds(3)).withAuthentication("fixture_admin",System.getenv("CAMPUSLIFE_COUPON_REDIS_ADMIN_PASSWORD")).build();
  adminClient=RedisClient.create(uri);admin=adminClient.connect();
 }
 @BeforeEach void resetRedis(){admin.sync().configSet("maxmemory","128mb");admin.sync().flushdb();}
 @AfterEach void restore(){
  reset(store,recoveryTx,recoveryRows);
  docker("start");waitRedis();waitApplicationRedis();admin.sync().configSet("maxmemory","128mb");admin.sync().flushdb();
 }
 @AfterAll void closeAdmin(){if(admin!=null){admin.close();}if(adminClient!=null){adminClient.shutdown();}}
 private void verifyContainer(){var labels=tools.jackson.databind.json.JsonMapper.builder().build().readTree(rawDocker("inspect","--format","{{json .Config.Labels}}",container));assertThat(labels.path("campuslife.coupon.identity").asString()).isEqualTo(identity);}

 private String rawDocker(String... args){try{var command=new ArrayList<String>();command.add("docker");command.addAll(Arrays.asList(args));var p=new ProcessBuilder(command).redirectErrorStream(true).start();if(!p.waitFor(25,TimeUnit.SECONDS)){p.destroyForcibly();throw new AssertionError("Owned Docker command timeout");}String text=new String(p.getInputStream().readAllBytes(),StandardCharsets.UTF_8);assertThat(p.exitValue()).as(text).isZero();return text;}catch(Exception ex){throw new IllegalStateException(ex);}}
 private void docker(String action){verifyContainer();if(!Set.of("start","stop","kill").contains(action)){throw new IllegalArgumentException();}if(action.equals("stop")){rawDocker("stop","--time","2",container);}else{rawDocker(action,container);}}
 private void waitRedis(){long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(System.nanoTime()<end){try{if("PONG".equals(admin.sync().ping())){return;}}catch(RuntimeException ignored){}try{Thread.sleep(100);}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new RuntimeException(ex);}}throw new AssertionError("Owned Redis did not restart");}
 private void waitApplicationRedis(){long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(System.nanoTime()<end){try{store.runId();return;}catch(RuntimeException ignored){}try{Thread.sleep(100);}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new RuntimeException(ex);}}throw new AssertionError("Application Redis connection did not reconnect");}
 private Campaign opened(int stock){var c=campaign(stock);jdbc.update("UPDATE coupon_dispatch_gate SET mode='ASYNC' WHERE campaign_id=?",c.row().getId());recovery.rebuild(c.row().getId());return c;}
 private DispatchGateMapper.Gate gate(Campaign c){return gates.find(c.row().getId());}
 private CouponRedisStore.Stats stats(Campaign c){var g=gate(c);return store.inspect(c.row().getId(),g.generation(),g.redisRunId());}
 private Reservation reserve(Campaign c,AccountEntity a,String key){var g=gate(c);String rid=UUID.randomUUID().toString().replace("-","");var r=store.reserve(c.row().getId(),g.generation(),g.redisRunId(),a.getId(),key,rid,clock.instant());assertThat(r.status()).isEqualTo("HELD");return new Reservation(c.row().getId(),g.generation(),r.reservation(),a.getId(),key,clock.instant().toEpochMilli(),"HELD");}
 private ClaimRequestEntity admit(Campaign c,Reservation v){
  // Test-only stand-in for future D admission, using the approved account/key and gate fences.
  return tx().execute(s->{accounts.lockById(v.student());shops.share(c.row().getShopId());var g=gates.share(v.campaign());
   if(!"OPEN".equals(g.phase())||!"ASYNC".equals(g.mode())||g.generation()!=v.generation()||recoveryRows.lockKey(v.student(),v.key())!=null){throw new IllegalStateException("Test admission fenced");}
   var now=time(clock.instant());var r=new ClaimRequestEntity();r.setStudentId(v.student());r.setCampaignId(v.campaign());r.setRequestKey(v.key());r.setStatus("PENDING");r.setSourceMode("ASYNC");r.setCreatedAt(now);r.setAcceptedAt(now);r.setDeadline(now.plusMinutes(5).isBefore(c.row().getUseEndAt())?now.plusMinutes(5):c.row().getUseEndAt());r.setReservationId(v.id());r.setAcceptedGeneration(v.generation());r.setSourceCampaignVersion(1L);requests.insert(r);writer.append(r);return r;});
 }
 private List<Map<String,Object>> seedPending(Rebuild t,Reservation v){var m=new LinkedHashMap<String,Object>();m.put("kind","HELD");m.put("campaign",Long.toString(t.campaign()));m.put("generation",Long.toString(t.generation()));m.put("student",Long.toString(v.student()));m.put("id",v.id());m.put("key",v.key());m.put("at",v.at());return List.of(m);}
 @Test void dedicatedConfigAclAndPersistentNoTtlState(){
  assertThat(admin.sync().configGet("appendonly")).containsEntry("appendonly","yes");assertThat(admin.sync().configGet("appendfsync")).containsEntry("appendfsync","everysec");assertThat(admin.sync().configGet("maxmemory-policy")).containsEntry("maxmemory-policy","noeviction");
  assertThatThrownBy(()->redis.opsForValue().set("unowned:key","denied")).isInstanceOf(RuntimeException.class);
  var c=opened(2);assertThat(stats(c)).isEqualTo(new CouponRedisStore.Stats(2,2,0,0));assertThat(redis.getExpire(store.controlKey(c.row().getId()))).isEqualTo(-1);assertThat(redis.getExpire(store.epochKey(c.row().getId(),gate(c).generation()))).isEqualTo(-1);
 }
 @Test void missingDatasetNeverInitializesOnReserve(){var c=campaign(1);var a=account("STUDENT");assertThatThrownBy(()->store.reserve(c.row().getId(),1,store.runId(),a.getId(),"x","a".repeat(32),base)).isInstanceOf(CouponRedisStore.Unavailable.class);assertThat(redis.hasKey(store.controlKey(c.row().getId()))).isFalse();assertThat(issued(c)).isZero();}
 @Test void concurrentLuaReservationsRespectStockAndPerStudentLimit()throws Exception{
  var c=opened(3);var users=new ArrayList<AccountEntity>();for(int i=0;i<12;i++){users.add(account("STUDENT"));}var g=gate(c);var result=new ArrayList<String>();
  try(var pool=Executors.newFixedThreadPool(6)){var tasks=new ArrayList<Future<String>>();for(var u:users){tasks.add(pool.submit(()->store.reserve(c.row().getId(),g.generation(),g.redisRunId(),u.getId(),"same","a"+UUID.randomUUID().toString().replace("-","").substring(1),base).status()));}for(var f:tasks){result.add(f.get(15,TimeUnit.SECONDS));}}
  assertThat(Collections.frequency(result,"HELD")).isEqualTo(3);assertThat(Collections.frequency(result,"SOLD_OUT")).isEqualTo(9);assertThat(stats(c)).isEqualTo(new CouponRedisStore.Stats(3,0,3,0));assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon_claim_request",Integer.class)).isZero();
 }
 @Test void duplicateKeysOrDifferentKeysCannotReserveTwice()throws Exception{
  var c=opened(3);var a=account("STUDENT");var v=reserve(c,a,"original");var g=gate(c);
  assertThat(store.reserve(v.campaign(),g.generation(),g.redisRunId(),a.getId(),"original","b".repeat(32),base).reservation()).isEqualTo(v.id());
  assertThat(store.reserve(v.campaign(),g.generation(),g.redisRunId(),a.getId(),"other","c".repeat(32),base).status()).isEqualTo("PENDING");assertThat(stats(c).held()).isEqualTo(1);
 }
 @Test void successfulIssueAndRepeatedSettlementNeverReturnInventory(){
  var c=opened(2);var a=account("STUDENT");var v=reserve(c,a,"issue");var r=admit(c,v);deliver(r);assertThat(stats(c).held()).isEqualTo(1);assertThat(recovery.settle(r.getId())).isTrue();assertThat(recovery.settle(r.getId())).isFalse();assertThat(stats(c)).isEqualTo(new CouponRedisStore.Stats(2,1,0,1));
  assertThat(store.reserve(v.campaign(),gate(c).generation(),gate(c).redisRunId(),a.getId(),"again","b".repeat(32),base).status()).isEqualTo("CLAIMED");assertThat(state(r).getRedisSettledGeneration()).isEqualTo(gate(c).generation());
 }
 @ParameterizedTest @ValueSource(longs={29999,30000,30001}) void orphanBoundaryIsThirtySecondsAndOneRelease(long elapsed){
  var c=opened(1);var a=account("STUDENT");var v=reserve(c,a,"orphan");at(base.plusMillis(elapsed));recovery.reap(c.row().getId(),"0");
  assertThat(stats(c).free()).isEqualTo(elapsed<30000?0:1);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon_claim_request",Integer.class)).isEqualTo(elapsed<30000?0:1);
  if(elapsed>=30000){var r=requests.findKey(a.getId(),"orphan");assertThat(r.getStatus()).isEqualTo("FAILED");assertThat(r.getAcceptedAt()).isNull();assertThat(r.getDeadline()).isNull();recovery.reap(c.row().getId(),"0");assertThat(stats(c).free()).isEqualTo(1);assertThat(store.reserve(v.campaign(),v.generation(),gate(c).redisRunId(),v.student(),v.key(),v.id(),base).status()).isEqualTo("CLOSED");}
 }
 @Test void compensationBeforeLateReserveWritesTombstoneWithoutIncrement(){
  var c=opened(1);var a=account("STUDENT");var g=gate(c);var v=new Reservation(c.row().getId(),g.generation(),"a".repeat(32),a.getId(),"late",base.minusSeconds(31).toEpochMilli(),"HELD");var proof=recoveryTx.orphan(v);store.settle(proof,base);assertThat(stats(c).free()).isEqualTo(1);assertThat(store.reserve(v.campaign(),v.generation(),g.redisRunId(),a.getId(),v.key(),v.id(),base).status()).isEqualTo("CLOSED");assertThat(stats(c).held()).isZero();
 }
 @Test void pendingAdmissionWinsAgainstOrphanAndSuccessIsNeverReleased(){var c=opened(1);var v=reserve(c,account("STUDENT"),"pending");var r=admit(c,v);at(base.plusSeconds(31));recovery.reap(v.campaign(),"0");assertThat(state(r).getStatus()).isEqualTo("PENDING");assertThat(stats(c).held()).isEqualTo(1);deliver(r);recovery.reap(v.campaign(),"0");assertThat(stats(c).spent()).isEqualTo(1);assertThat(stats(c).free()).isZero();}
 @Test void disabledOwnerStillAllowsOrphanCleanup(){var c=opened(1);var a=account("STUDENT");var v=reserve(c,a,"disabled");jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?",a.getId());at(base.plusSeconds(31));recovery.reap(v.campaign(),"0");assertThat(stats(c).free()).isEqualTo(1);assertThat(requests.findKey(a.getId(),v.key()).getStatus()).isEqualTo("FAILED");}
 @RepeatedTest(4) void durableAdmissionAndOrphanRaceHaveOneWinner()throws Exception{
  var c=opened(1);var a=account("STUDENT");var v=reserve(c,a,"race");at(base.plusSeconds(31));var start=new CountDownLatch(1);Settlement proof;
  try(var pool=Executors.newFixedThreadPool(2)){
   var admission=pool.submit(()->{start.await();try{admit(c,v);return true;}catch(IllegalStateException ex){return false;}});var orphan=pool.submit(()->{start.await();return recoveryTx.orphan(v);});start.countDown();boolean accepted=admission.get(20,TimeUnit.SECONDS);proof=orphan.get(20,TimeUnit.SECONDS);
   var r=requests.findKey(a.getId(),v.key());assertThat(r.getStatus()).isEqualTo(accepted?"PENDING":"FAILED");if(proof!=null){store.settle(proof,clock.instant());}
   assertThat(stats(c).held()).isEqualTo(accepted?1:0);assertThat(stats(c).free()).isEqualTo(accepted?0:1);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon_claim_request",Integer.class)).isEqualTo(1);
  }
 }
 @Test void permanentCrossCampaignKeyConflictReleasesOnlyTheOrphan(){
  var c=opened(1);var other=opened(1);var a=account("STUDENT");var first=reserve(c,a,"global");var r=admit(c,first);var orphan=reserve(other,a,"global");at(base.plusSeconds(31));recovery.reap(other.row().getId(),"0");assertThat(stats(other).free()).isEqualTo(1);assertThat(stats(c).held()).isEqualTo(1);assertThat(state(r).getStatus()).isEqualTo("PENDING");assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon_claim_request",Integer.class)).isEqualTo(1);
 }
 @Test void expiredFailureReleaseDoesNotClearNewReservation(){
  var c=opened(1);var a=account("STUDENT");var old=reserve(c,a,"old");var r=admit(c,old);at(base.plusSeconds(300));issuance.expire(r.getId());var oldFact=recoveryTx.settlement(r.getId());recovery.settle(r.getId());var fresh=reserve(c,a,"fresh");store.settle(oldFact,clock.instant());var g=gate(c);var result=store.reserve(c.row().getId(),g.generation(),g.redisRunId(),a.getId(),"third","c".repeat(32),clock.instant());assertThat(result.status()).isEqualTo("PENDING");assertThat(result.reservation()).isEqualTo(fresh.id());assertThat(stats(c)).isEqualTo(new CouponRedisStore.Stats(1,0,1,0));
 }
 @Test void rebuildKeepsIssuedAndPendingButDiscardsUnadmittedOrphan(){
  var c=opened(3);var s=reserve(c,account("STUDENT"),"success");var sr=admit(c,s);deliver(sr);recovery.settle(sr.getId());var p=reserve(c,account("STUDENT"),"pending");admit(c,p);reserve(c,account("STUDENT"),"orphan");long old=gate(c).generation();recovery.rebuild(c.row().getId());assertThat(stats(c)).isEqualTo(new CouponRedisStore.Stats(3,1,1,1));assertThat(redis.getExpire(store.epochKey(c.row().getId(),old))).isBetween(1L,3600L);assertThat(redis.getExpire(store.epochKey(c.row().getId(),gate(c).generation()))).isEqualTo(-1);assertThatThrownBy(()->store.reserve(s.campaign(),old,gate(c).redisRunId(),s.student(),"stale","a".repeat(32),base)).isInstanceOf(CouponRedisStore.Unavailable.class);
 }
 @Test void rebuildDeterministicallyFailsExcessPendingAndPreservesOriginalDeadlines(){
  var c=campaign(2);var a=pending(c,account("STUDENT"));var b=pending(c,account("STUDENT"));var excess=pending(c,account("STUDENT"));recovery.rebuild(c.row().getId());assertThat(state(a).getStatus()).isEqualTo("PENDING");assertThat(state(b).getStatus()).isEqualTo("PENDING");assertThat(state(excess).getFailureCode()).isEqualTo("SOLD_OUT");assertThat(state(a).getDeadline()).isEqualTo(a.getDeadline());assertThat(stats(c)).isEqualTo(new CouponRedisStore.Stats(2,0,2,0));
 }
 @Test void rebuildExpiresDueRequestsAndDoesNotConsumeCapacityForIneligibleStudent(){
  var c=campaign(2);var disabled=account("STUDENT");var invalid=pending(c,disabled);var kept=pending(c,account("STUDENT"));jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?",disabled.getId());recovery.rebuild(c.row().getId());assertThat(state(invalid).getFailureCode()).isEqualTo("QUALIFICATION_CHANGED");assertThat(state(kept).getStatus()).isEqualTo("PENDING");assertThat(stats(c).free()).isEqualTo(1);at(base.plusSeconds(300));recovery.rebuild(c.row().getId());assertThat(state(kept).getFailureCode()).isEqualTo("PROCESSING_EXPIRED");assertThat(stats(c).free()).isEqualTo(2);
 }
 @Test void pausedGateIsRestoredAfterRebuild(){var c=opened(1);jdbc.update("UPDATE coupon_dispatch_gate SET phase='PAUSED' WHERE campaign_id=?",c.row().getId());recovery.rebuild(c.row().getId());assertThat(gate(c).phase()).isEqualTo("PAUSED");assertThat(gate(c).resumePhase()).isNull();assertThatThrownBy(()->reserve(c,account("STUDENT"),"closed")).isInstanceOf(CouponRedisStore.Unavailable.class);}
 @Test void abandonedShadowAndLateCoordinatorCannotOpenTheNewGeneration(){
  var c=campaign(1);pending(c,account("STUDENT"));var captured=new AtomicReference<Rebuild>();
  doAnswer(i->{captured.set(i.getArgument(0));i.callRealMethod();throw new IllegalStateException("test crash after seed");}).when(store).seed(any(),anyString(),anyInt(),anyList());
  assertThatThrownBy(()->recovery.rebuild(c.row().getId())).isInstanceOf(IllegalStateException.class);assertThat(gate(c).phase()).isEqualTo("REBUILDING");var old=captured.get();reset(store);at(base.plusSeconds(31));var second=new CouponRedisRecovery(recoveryTx,store,gates,clock);second.rebuild(c.row().getId());assertThat(gate(c).generation()).isGreaterThan(old.generation());assertThatThrownBy(()->store.activate(old,store.runId())).isInstanceOf(CouponRedisStore.Unavailable.class);assertThatThrownBy(()->recoveryTx.finish(old,store.runId())).isInstanceOf(CouponRecoveryTransactions.Fenced.class);assertThat(stats(c).held()).isEqualTo(1);
 }
 @Test void redisActivationBeforeDatabaseFinishDoesNotAuthorizeAdmission(){
  var c=campaign(1);jdbc.update("UPDATE coupon_dispatch_gate SET mode='ASYNC' WHERE campaign_id=?",c.row().getId());var captured=new AtomicReference<Rebuild>();doAnswer(i->{captured.set(i.getArgument(0));throw new IllegalStateException("test crash after activation");}).when(recoveryTx).finish(any(),anyString());
  assertThatThrownBy(()->recovery.rebuild(c.row().getId())).isInstanceOf(IllegalStateException.class);assertThat(admin.sync().hget(store.controlKey(c.row().getId()),"phase")).isEqualTo("OPEN");assertThat(gate(c).phase()).isEqualTo("REBUILDING");var a=account("STUDENT");var v=new Reservation(c.row().getId(),gate(c).generation(),"b".repeat(32),a.getId(),"late",base.toEpochMilli(),"HELD");assertThatThrownBy(()->admit(c,v)).isInstanceOf(IllegalStateException.class);reset(recoveryTx);at(base.plusSeconds(31));recovery.rebuild(c.row().getId());assertThatThrownBy(()->recoveryTx.finish(captured.get(),store.runId())).isInstanceOf(CouponRecoveryTransactions.Fenced.class);
 }
 @ParameterizedTest @ValueSource(longs={29999,30000,30001}) void rebuildLeaseHasStrictBoundary(long elapsed){var c=opened(1);var t=recoveryTx.begin(c.row().getId());at(base.plusMillis(elapsed));if(elapsed<30000){recoveryTx.renew(t);}else{assertThatThrownBy(()->recoveryTx.renew(t)).isInstanceOf(CouponRecoveryTransactions.Fenced.class);assertThat(recoveryTx.begin(c.row().getId()).generation()).isGreaterThan(t.generation());}}
 @ParameterizedTest @ValueSource(strings={"controlType","epochType","sentinel","counter","record","controlTtl","epochTtl"}) void corruptDerivedStateRebuildsFromSqlInsteadOfGuessingStock(String kind){
  var c=opened(2);var v=reserve(c,account("STUDENT"),"held");admit(c,v);var old=gate(c);String control=store.controlKey(c.row().getId()),epoch=store.epochKey(c.row().getId(),old.generation());admin.sync().set("fixture:other","keep");
  switch(kind){case "controlType"->{admin.sync().del(control);admin.sync().set(control,"wrong");}case "epochType"->{admin.sync().del(epoch);admin.sync().set(epoch,"wrong");}case "sentinel"->admin.sync().hdel(epoch,"#token");case "counter"->admin.sync().hset(epoch,"#free","99999");case "controlTtl"->admin.sync().expire(control,60);case "epochTtl"->admin.sync().expire(epoch,60);default->admin.sync().hset(epoch,"r:"+v.id(),"{}");}
  if(kind.equals("record")){assertThatThrownBy(()->recovery.reap(c.row().getId(),"0")).isInstanceOf(RuntimeException.class);}else{assertThat(recovery.health(old)).isFalse();recovery.rebuild(c.row().getId());}
  assertThat(gate(c).generation()).isGreaterThan(old.generation());assertThat(stats(c)).isEqualTo(new CouponRedisStore.Stats(2,1,1,0));assertThat(admin.sync().get("fixture:other")).isEqualTo("keep");
 }
 @Test void serverRestartUsesAofButStillRequiresRunMarkerRebuild(){var c=opened(2);var v=reserve(c,account("STUDENT"),"persisted");admit(c,v);var old=gate(c);docker("stop");docker("start");waitRedis();waitApplicationRedis();assertThat(store.runId()).isNotEqualTo(old.redisRunId());assertThat(admin.sync().hget(store.controlKey(c.row().getId()),"generation")).isEqualTo(Long.toString(old.generation()));assertThatThrownBy(()->store.inspect(c.row().getId(),old.generation(),old.redisRunId())).isInstanceOf(CouponRedisStore.Unavailable.class);recovery.rebuild(c.row().getId());assertThat(stats(c)).isEqualTo(new CouponRedisStore.Stats(2,1,1,0));}
 @Test void killedRedisRecoversPendingFromSqlWithoutClaimingZeroDataLoss(){var c=opened(1);var v=reserve(c,account("STUDENT"),"durable");var r=admit(c,v);docker("kill");docker("start");waitRedis();try{recovery.rebuild(c.row().getId());}catch(RuntimeException deferred){assertThat(gate(c).phase()).isEqualTo("REBUILDING");waitApplicationRedis();at(base.plusSeconds(31));recovery.rebuild(c.row().getId());}assertThat(state(r).getStatus()).isEqualTo("PENDING");assertThat(stats(c).held()).isEqualTo(1);deliver(r);recovery.settle(r.getId());assertThat(issued(c)).isEqualTo(1);assertThat(stats(c).spent()).isEqualTo(1);}
 @Test void oomDoesNotPartiallyDeductAndRecoveryStaysClosedUntilCapacityRestored(){var c=opened(2);var a=account("STUDENT");var g=gate(c);admin.sync().configSet("maxmemory","1");assertThatThrownBy(()->store.reserve(c.row().getId(),g.generation(),g.redisRunId(),a.getId(),"oom","a".repeat(32),base)).isInstanceOf(RuntimeException.class);assertThatThrownBy(()->recovery.rebuild(c.row().getId())).isInstanceOf(RuntimeException.class);assertThat(gate(c).phase()).isEqualTo("REBUILDING");admin.sync().configSet("maxmemory","128mb");at(base.plusSeconds(31));recovery.rebuild(c.row().getId());assertThat(stats(c)).isEqualTo(new CouponRedisStore.Stats(2,2,0,0));assertThat(issued(c)).isZero();}
 @Test void unknownReserveReplyReusesIdentityThenOrphanReleasesOnce(){var c=opened(1);var a=account("STUDENT");var g=gate(c);String id="a".repeat(32);doAnswer(i->{i.callRealMethod();throw new org.springframework.data.redis.RedisConnectionFailureException("test lost reply");}).when(store).reserve(anyLong(),anyLong(),anyString(),anyLong(),anyString(),anyString(),any());assertThatThrownBy(()->store.reserve(c.row().getId(),g.generation(),g.redisRunId(),a.getId(),"unknown",id,base)).isInstanceOf(RuntimeException.class);reset(store);assertThat(store.reserve(c.row().getId(),g.generation(),g.redisRunId(),a.getId(),"unknown",id,base).reservation()).isEqualTo(id);assertThat(stats(c).held()).isEqualTo(1);at(base.plusSeconds(31));recovery.reap(c.row().getId(),"0");assertThat(stats(c).free()).isEqualTo(1);}
 @Test void checkpointFailureAndUnknownSettlementReplyCannotDoubleSpend(){
  var c=opened(1);var v=reserve(c,account("STUDENT"),"paid");var r=admit(c,v);deliver(r);var real=sql.getMapper(CouponRecoveryMapper.class);doAnswer(i->{real.checkpoint(i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(3));throw new IllegalStateException("test lost checkpoint");}).when(recoveryRows).checkpoint(anyLong(),anyLong(),anyString(),anyString());
  assertThatThrownBy(()->recovery.settle(r.getId())).isInstanceOf(IllegalStateException.class);assertThat(state(r).getRedisSettledGeneration()).isNull();reset(recoveryRows);recovery.settle(r.getId());assertThat(stats(c)).isEqualTo(new CouponRedisStore.Stats(1,0,0,1));assertThat(issued(c)).isEqualTo(1);
 }
 @Test void missingSuccessRecordRequiresProofAndRebuildDoesNotDoubleCount(){
  var c=opened(1);var v=reserve(c,account("STUDENT"),"proof");var r=admit(c,v);deliver(r);admin.sync().hdel(store.epochKey(c.row().getId(),gate(c).generation()),"r:"+v.id());assertThatThrownBy(()->recovery.settle(r.getId())).isInstanceOf(CouponRedisStore.Unavailable.class);var current=gate(c);admin.sync().hdel(store.epochKey(c.row().getId(),current.generation()),"r:"+v.id());recovery.settle(r.getId());assertThat(stats(c)).isEqualTo(new CouponRedisStore.Stats(1,0,0,1));
 }
 @Test void staleCheckpointAndOldLuaCannotAffectNewEpoch(){var c=opened(1);var v=reserve(c,account("STUDENT"),"old");var r=admit(c,v);deliver(r);var old=recoveryTx.settlement(r.getId());store.settle(old,base);recovery.rebuild(c.row().getId());assertThat(recoveryTx.checkpoint(old)).isFalse();assertThatThrownBy(()->store.settle(old,base)).isInstanceOf(CouponRedisStore.Unavailable.class);recovery.settle(r.getId());assertThat(state(r).getRedisSettledGeneration()).isEqualTo(gate(c).generation());assertThat(stats(c).spent()).isEqualTo(1);}
 @Test void incompleteOrConflictingSeedCannotActivate(){
  var c=opened(2);var v=reserve(c,account("STUDENT"),"seed");admit(c,v);var t=recoveryTx.begin(c.row().getId());String run=store.runId();store.prepare(t,run);store.initialize(t,run,1);assertThatThrownBy(()->store.activate(t,run)).isInstanceOf(CouponRedisStore.Unavailable.class);var batch=seedPending(t,v);store.seed(t,run,0,batch);store.seed(t,run,0,batch);var changed=new LinkedHashMap<>(batch.getFirst());changed.put("key","different");assertThatThrownBy(()->store.seed(t,run,0,List.of(changed))).isInstanceOf(CouponRedisStore.Unavailable.class);store.activate(t,run);recoveryTx.finish(t,run);assertThat(stats(c).held()).isEqualTo(1);
 }
 @Test void bigintGenerationsAreComparedAsExactDecimalStrings(){var c=opened(1);String run=store.runId();long big=9007199254740993L;var t=new Rebuild(c.row().getId(),c.row().getShopId(),big,"a".repeat(32),"OPEN",1,0);store.prepare(t,run);store.initialize(t,run,0);store.activate(t,run);var old=new Rebuild(t.campaign(),t.shop(),big-1,"b".repeat(32),"OPEN",1,0);assertThatThrownBy(()->store.prepare(old,run)).isInstanceOf(CouponRedisStore.Unavailable.class);assertThat(store.inspect(t.campaign(),big,run).free()).isEqualTo(1);}
 @Test void redisIoIsRejectedInsideSqlTransaction(){var c=opened(1);var g=gate(c);assertThatThrownBy(()->tx().execute(s->store.inspect(c.row().getId(),g.generation(),g.redisRunId()))).isInstanceOf(IllegalStateException.class);assertThat(stats(c).free()).isEqualTo(1);}
 @Test void backgroundRepairNeverEnablesSynchronousCampaigns(){var untouched=campaign(1);assertThatThrownBy(()->recovery.rebuild(untouched.row().getId())).isInstanceOf(CouponRecoveryTransactions.Fenced.class);assertThat(gate(untouched).mode()).isEqualTo("SYNC");var c=campaign(1);jdbc.update("UPDATE coupon_dispatch_gate SET mode='ASYNC' WHERE campaign_id=?",c.row().getId());job.tick();assertThat(gate(c).phase()).isEqualTo("OPEN");assertThat(stats(c).free()).isEqualTo(1);assertThat(redis.hasKey(store.controlKey(untouched.row().getId()))).isFalse();}
 @Test void rebuildAndScanUseMultipleBoundedBatches(){
  var c=campaign(205);for(int i=0;i<205;i++){pending(c,account("STUDENT"));}recovery.rebuild(c.row().getId());var g=gate(c);assertThat(stats(c)).isEqualTo(new CouponRedisStore.Stats(205,0,205,0));assertThat(admin.sync().hget(store.epochKey(c.row().getId(),g.generation()),"#next")).isEqualTo("2");
  var ids=new HashSet<String>();String cursor="0";int pages=0;do{var page=store.scan(c.row().getId(),g.generation(),g.redisRunId(),cursor);page.reservations().forEach(r->ids.add(r.id()));cursor=page.cursor();pages++;assertThat(pages).isLessThan(30);}while(!cursor.equals("0"));assertThat(ids).hasSize(205);assertThat(pages).isGreaterThan(1);
 }
 @Test void delayedSettlementDoesNotRebuildAnAlreadyRecoveredGeneration(){
  var c=opened(1);var v=reserve(c,account("STUDENT"),"late_settle");var r=admit(c,v);deliver(r);long old=gate(c).generation();var once=new AtomicBoolean();
  doAnswer(i->{if(once.compareAndSet(false,true)){recovery.rebuild(c.row().getId());}return i.callRealMethod();}).when(store).settle(any(),any());
  assertThatThrownBy(()->recovery.settle(r.getId())).isInstanceOf(CouponRedisStore.Unavailable.class);reset(store);assertThat(gate(c).generation()).isEqualTo(old+1);recovery.settle(r.getId());assertThat(stats(c).spent()).isEqualTo(1);
 }
 @Test void synchronousSuccessAndCouponExpiryRemainSpentAfterSwitchPreparation(){
  var c=campaign(2);var a=account("STUDENT");var p=principal(a);var node=tools.jackson.databind.json.JsonMapper.builder().build().valueToTree(c.row().getId().toString());var result=claims.claim(p,"legacy_sync",new com.campuslife.coupon.dto.CouponClaim(node));assertThat(result.result().status()).isEqualTo("SUCCEEDED");
  var before=jdbc.queryForList("SELECT * FROM user_coupon WHERE campaign_id=?",c.row().getId());jdbc.update("UPDATE coupon_dispatch_gate SET mode='ASYNC' WHERE campaign_id=?",c.row().getId());recovery.rebuild(c.row().getId());at(base.plusSeconds(1800));recovery.rebuild(c.row().getId());assertThat(stats(c)).isEqualTo(new CouponRedisStore.Stats(2,1,0,1));var g=gate(c);assertThat(store.reserve(c.row().getId(),g.generation(),g.redisRunId(),a.getId(),"again","b".repeat(32),clock.instant()).status()).isEqualTo("CLAIMED");assertThat(jdbc.queryForList("SELECT * FROM user_coupon WHERE campaign_id=?",c.row().getId())).isEqualTo(before);assertThat(jdbc.queryForObject("SELECT redis_settled_generation FROM coupon_claim_request WHERE id=?",Long.class,result.result().id())).isNull();
 }
 @Test void interruptedCoordinatorStaysFencedUntilLeaseTakeover(){
  var c=campaign(1);pending(c,account("STUDENT"));doAnswer(i->{i.callRealMethod();Thread.currentThread().interrupt();return null;}).when(store).seed(any(),anyString(),anyInt(),anyList());
  try{assertThatThrownBy(()->recovery.rebuild(c.row().getId())).isInstanceOf(CouponRecoveryTransactions.Fenced.class);assertThat(Thread.currentThread().isInterrupted()).isTrue();}finally{Thread.interrupted();reset(store);}
  assertThat(gate(c).phase()).isEqualTo("REBUILDING");at(base.plusSeconds(31));recovery.rebuild(c.row().getId());assertThat(stats(c).held()).isEqualTo(1);
 }
}
