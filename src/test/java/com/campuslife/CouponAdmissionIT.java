package com.campuslife;

import com.campuslife.auth.security.*;
import com.campuslife.common.exception.BusinessException;
import com.campuslife.coupon.dto.CouponClaim;
import com.campuslife.coupon.entity.ClaimRequestEntity;
import com.campuslife.coupon.redis.CouponRedisStore;
import com.campuslife.coupon.service.*;
import com.campuslife.coupon.service.CouponMaintenanceTransactions.*;
import com.campuslife.messaging.service.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import tools.jackson.databind.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.campuslife.messaging.config.RabbitMessagingConfiguration.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"campuslife.messaging.enabled=true","campuslife.messaging.consumer-enabled=false","campuslife.messaging.scheduling-enabled=false"})
@ActiveProfiles("mysql")
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="CAMPUSLIFE_COUPON_REDIS_IT",matches="owned-isolated-fixture")
class CouponAdmissionIT extends CouponAsyncFixture {
 @Autowired CouponAdmissionService admission;@Autowired CouponMaintenance maintenance;@Autowired CouponMaintenanceTransactions controls;
 @MockitoSpyBean CouponAdmissionTransactions admissionTx;@MockitoSpyBean CouponRedisStore store;@MockitoSpyBean com.campuslife.messaging.mapper.OutboxMapper outboxWrites;
 @Autowired CouponRedisRecovery recovery;@Autowired TokenService tokens;@Autowired ObjectMapper json;@Autowired Environment env;
 @Autowired RabbitAdmin rabbitAdmin;@Autowired @Qualifier("couponIssueContainer") SimpleMessageListenerContainer consumer;
 @Autowired OutboxTransactions publishing;@Autowired RabbitOutboxTransport transport;
 final HttpClient http=HttpClient.newHttpClient();
 @BeforeAll void owned(){assertThat(System.getenv("CAMPUSLIFE_MQ_IT")).isEqualTo("owned-isolated-fixture");}
 @AfterEach void resetAdmission(){consumer.stop();reset(admissionTx,store,outboxWrites);}
 long id(Campaign c){return c.row().getId();}
 Inspection inspect(Campaign c){return maintenance.run("Inspect",id(c),null,false);}
 Inspection action(Campaign c,String action){return maintenance.run(action,id(c),inspect(c).controlVersion(),true);}
 Campaign opened(int n){var c=campaign(n);action(c,"Pause");action(c,"Async");action(c,"Resume");return c;}
 CouponClaim body(Campaign c){return new CouponClaim(json.valueToTree(Long.toString(id(c))));}
 ClaimRequestEntity accept(Campaign c,AuthPrincipal p,String key){var r=admission.claim(p,key,body(c));assertThat(r.result().status()).isEqualTo("PENDING");return requests.selectById(Long.parseLong(r.result().id()));}
 String token(AuthPrincipal p){var t=tokens.issue(p.accountId(),p.jti());jdbc.update("UPDATE auth_session SET expires_at=?,created_at=? WHERE account_id=? AND jti=?",time(t.expiresAt()),time(t.issuedAt()),p.accountId(),p.jti());return t.value();}
 HttpResponse<String> post(Campaign c,String token,String key)throws Exception{return call("POST","/api/v1/coupon-claims",token,key,json.writeValueAsString(Map.of("campaignId",Long.toString(id(c)))));}
 HttpResponse<String> call(String method,String path,String token,String key,String body)throws Exception{
  var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+env.getRequiredProperty("local.server.port")+path)).timeout(Duration.ofSeconds(20));
  if(token!=null){b.header("Authorization","Bearer "+token);}if(key!=null){b.header("Idempotency-Key",key);}if(body!=null){b.header("Content-Type","application/json");}
  return http.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
 }
 JsonNode data(HttpResponse<String> r,int code){assertThat(r.statusCode()).as(r.body()).isEqualTo(code);return json.readTree(r.body()).path("data");}
 CouponRedisStore.Stats stats(Campaign c){var g=gates.find(id(c));return store.inspect(id(c),g.generation(),g.redisRunId());}
 void unavailable(Runnable r){assertThatThrownBy(r::run).isInstanceOf(BusinessException.class).satisfies(e->assertThat(((BusinessException)e).code()).isEqualTo("COUPON_ADMISSION_UNAVAILABLE"));}
 void refused(Runnable r,String code){assertThatThrownBy(r::run).isInstanceOf(Refused.class).hasMessage(code);}
 @Test void public202ReplayLocationOwnReadAndTerminal200()throws Exception{
  var c=opened(2);var p=principal(account("STUDENT"));String token=token(p);var first=post(c,token,"http");var result=data(first,202);String path=first.headers().firstValue("Location").orElseThrow();
  assertThat(path).isEqualTo("/api/v1/coupon-claims/"+result.path("id").asString());assertThat(first.headers().firstValue("Idempotency-Replayed")).contains("false");assertThat(result.path("coupon").isNull()).isTrue();
  var replay=post(c,token,"http");assertThat(data(replay,202)).isEqualTo(result);assertThat(replay.headers().firstValue("Idempotency-Replayed")).contains("true");assertThat(data(call("GET",path,token,null,null),200)).isEqualTo(result);
  var other=principal(account("STUDENT"));assertThat(call("GET",path,token(other),null,null).statusCode()).isEqualTo(404);
  var r=requests.findKey(p.accountId(),"http");deliver(r);recovery.settle(r.getId());assertThat(data(post(c,token,"http"),200).path("status").asString()).isEqualTo("SUCCEEDED");assertThat(issued(c)).isEqualTo(1);assertThat(stats(c).spent()).isEqualTo(1);
 }
 @Test void originalSync201And200DoNotUseRedis()throws Exception{var c=campaign(1);var p=principal(account("STUDENT"));var first=post(c,token(p),"sync");data(first,201);data(post(c,token(p),"sync"),200);verify(store,never()).reserve(anyLong(),anyLong(),any(),anyLong(),any(),any(),any());assertThat(inspect(c).mode()).isEqualTo("SYNC");}
 @ParameterizedTest @ValueSource(strings={"anonymous","merchant","missingKey","invalidKey","numericId","extraField"}) void publicIdentityAndStrictRequestValidation(String kind)throws Exception{
  var c=opened(1);var p=principal(account(kind.equals("merchant")?"MERCHANT":"STUDENT"));String t=kind.equals("anonymous")?null:token(p);String key=kind.equals("missingKey")?null:kind.equals("invalidKey")?"bad key":"valid";
  String body=kind.equals("numericId")?"{\"campaignId\":"+id(c)+"}":kind.equals("extraField")?"{\"campaignId\":\""+id(c)+"\",\"extra\":1}":json.writeValueAsString(Map.of("campaignId",Long.toString(id(c))));
  var r=call("POST","/api/v1/coupon-claims",t,key,body);assertThat(r.statusCode()).isEqualTo(kind.equals("anonymous")?401:kind.equals("merchant")?403:400);assertThat(stats(c).held()).isZero();
 }
 @Test void concurrentPublicAdmissionAndIssuanceNeverOversell()throws Exception{
  var c=opened(3);var users=new ArrayList<AuthPrincipal>();for(int i=0;i<9;i++){users.add(principal(account("STUDENT")));}var results=new ArrayList<Integer>();
  try(var pool=Executors.newFixedThreadPool(6)){var futures=new ArrayList<Future<Integer>>();for(var p:users){String t=token(p);futures.add(pool.submit(()->post(c,t,"stock").statusCode()));}for(var f:futures){results.add(f.get(30,TimeUnit.SECONDS));}}
  assertThat(Collections.frequency(results,202)).isEqualTo(3);assertThat(Collections.frequency(results,409)).isEqualTo(6);
  var ids=jdbc.queryForList("SELECT id FROM coupon_claim_request WHERE campaign_id=?",Long.class,id(c));for(long r:ids){deliver(requests.selectById(r));recovery.settle(r);}assertThat(issued(c)).isEqualTo(3);assertThat(stats(c)).isEqualTo(new CouponRedisStore.Stats(3,0,0,3));
 }
 @ParameterizedTest @ValueSource(booleans={true,false}) void concurrentSameStudentGetsOnePendingAndOneOutbox(boolean sameKey)throws Exception{
  var c=opened(3);var p=principal(account("STUDENT"));String t=token(p);var codes=new ArrayList<Integer>();
  try(var pool=Executors.newFixedThreadPool(4)){var fs=new ArrayList<Future<Integer>>();for(int i=0;i<4;i++){String k=sameKey?"one":"key"+i;fs.add(pool.submit(()->post(c,t,k).statusCode()));}for(var f:fs){codes.add(f.get(30,TimeUnit.SECONDS));}}
  assertThat(Collections.frequency(codes,202)).isEqualTo(sameKey?4:1);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon_claim_request WHERE campaign_id=?",Integer.class,id(c))).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM message_outbox WHERE event_type='COUPON_ISSUE_REQUESTED'",Integer.class)).isEqualTo(1);assertThat(stats(c).held()).isEqualTo(1);
 }
 @Test void durableOriginalKeyBypassesRedisAndCurrentShopVisibility()throws Exception{
  var c=opened(2);var p=principal(account("STUDENT"));var r=accept(c,p,"prior");clearInvocations(store);action(c,"Pause");jdbc.update("UPDATE shop SET approval_status='SUSPENDED' WHERE id=?",c.row().getShopId());doThrow(new IllegalStateException("test offline")).when(store).reserve(anyLong(),anyLong(),any(),anyLong(),any(),any(),any());
  data(post(c,token(p),"prior"),202);var other=campaign(1);var conflict=post(other,token(p),"prior");assertThat(conflict.statusCode()).isEqualTo(409);assertThat(json.readTree(conflict.body()).path("code").asString()).isEqualTo("IDEMPOTENCY_KEY_CONFLICT");assertThat(state(r).getStatus()).isEqualTo("PENDING");verify(store,never()).reserve(anyLong(),anyLong(),any(),anyLong(),any(),any(),any());
 }
 @Test void outboxFailureRollsBack202AndOrphanLaterArbitratesPermanentFailure()throws Exception{
  var c=opened(1);var p=principal(account("STUDENT"));doAnswer(i->{i.callRealMethod();throw new IllegalStateException("injected post-outbox rollback");}).when(outboxWrites).append(anyString(),anyString(),anyString(),anyLong(),anyLong(),anyLong(),anyString(),anyString(),any());
  assertThat(post(c,token(p),"rollback").statusCode()).isEqualTo(503);assertThat(requests.findKey(p.accountId(),"rollback")).isNull();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM message_outbox WHERE event_type='COUPON_ISSUE_REQUESTED'",Integer.class)).isZero();assertThat(stats(c).held()).isEqualTo(1);
  reset(outboxWrites);at(base.plusSeconds(30));recovery.reap(id(c),"0");var failed=data(post(c,token(p),"rollback"),200);assertThat(failed.path("status").asString()).isEqualTo("FAILED");assertThat(failed.path("acceptedAt").isNull()).isTrue();assertThat(stats(c).free()).isEqualTo(1);
 }
 @Test void unknownRedisReplyDoesNotReturn202OrAutomaticallyRelease()throws Exception{
  var c=opened(1);var p=principal(account("STUDENT"));doAnswer(i->{i.callRealMethod();throw new org.springframework.data.redis.RedisConnectionFailureException("lost reserve reply");}).when(store).reserve(anyLong(),anyLong(),any(),anyLong(),any(),any(),any());
  assertThat(post(c,token(p),"lost").statusCode()).isEqualTo(503);assertThat(requests.findKey(p.accountId(),"lost")).isNull();assertThat(stats(c).held()).isEqualTo(1);reset(store);data(post(c,token(p),"lost"),202);assertThat(stats(c).held()).isEqualTo(1);
 }
 @Test void commitSucceededButCallerLostReplyReplaysWithoutSecondOutbox()throws Exception{
  var c=opened(1);var p=principal(account("STUDENT"));doAnswer(i->{var reserved=(CouponRedisStore.Reserved)i.callRealMethod();admissionTx.accept(p,"commit",admissionTx.plan(p,id(c),"commit"),reserved.reservation());throw new org.springframework.data.redis.RedisConnectionFailureException("injected response lost after durable commit");}).when(store).reserve(anyLong(),anyLong(),any(),anyLong(),any(),any(),any());
  var response=post(c,token(p),"commit");data(response,202);assertThat(response.headers().firstValue("Idempotency-Replayed")).contains("true");assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM message_outbox WHERE event_type='COUPON_ISSUE_REQUESTED'",Integer.class)).isEqualTo(1);
 }
 @Test void stopBetweenLuaAndSqlRejectsAdmissionAndNoInventoryIsBlindlyReturned(){
  var c=opened(1);var p=principal(account("STUDENT"));doAnswer(i->{var r=i.callRealMethod();tx().executeWithoutResult(s->{shops.share(c.row().getShopId());gates.lock(id(c));campaigns.stop(id(c),c.merchant().getId(),1,time(base));});return r;}).when(store).reserve(anyLong(),anyLong(),any(),anyLong(),any(),any(),any());
  assertThatThrownBy(()->admission.claim(p,"stopped",body(c))).isInstanceOf(BusinessException.class).satisfies(e->assertThat(((BusinessException)e).code()).isEqualTo("COUPON_NOT_CLAIMABLE"));assertThat(requests.findKey(p.accountId(),"stopped")).isNull();assertThat(stats(c).held()).isEqualTo(1);at(base.plusSeconds(30));recovery.reap(id(c),"0");assertThat(stats(c).free()).isEqualTo(1);
 }
 @ParameterizedTest @ValueSource(strings={"pause","rebuild","logout","disabled","role","expiredSession"}) void changedAuthorityBetweenReservationAndAdmissionCannotCommit(String kind){
  var c=opened(1);var p=principal(account("STUDENT"));doAnswer(i->{var r=i.callRealMethod();switch(kind){case "pause"->action(c,"Pause");case "rebuild"->recovery.rebuild(id(c));case "logout"->jdbc.update("UPDATE auth_session SET revoked_at=? WHERE jti=?",time(base),p.jti());case "disabled"->jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?",p.accountId());case "role"->jdbc.update("UPDATE account SET role='MERCHANT' WHERE id=?",p.accountId());default->jdbc.update("UPDATE auth_session SET expires_at=? WHERE jti=?",time(base),p.jti());}return r;}).when(store).reserve(anyLong(),anyLong(),any(),anyLong(),any(),any(),any());
  assertThatThrownBy(()->admission.claim(p,"changed",body(c))).isInstanceOf(BusinessException.class);assertThat(requests.findKey(p.accountId(),"changed")).isNull();assertThat(issued(c)).isZero();
 }
 @Test void orphanTombstoneWinningAfterLuaIsReturnedAsFailedNotResurrected(){
  var c=opened(1);var p=principal(account("STUDENT"));doAnswer(i->{var r=i.callRealMethod();at(base.plusSeconds(30));recovery.reap(id(c),"0");return r;}).when(store).reserve(anyLong(),anyLong(),any(),anyLong(),any(),any(),any());
  var result=admission.claim(p,"arbitrated",body(c));assertThat(result.replayed()).isTrue();assertThat(result.result().status()).isEqualTo("FAILED");assertThat(stats(c).held()).isZero();assertThat(issued(c)).isZero();
 }
 @Test void deadlineIsClampedAndGetDoesNotExpirePending()throws Exception{
  var c=opened(1);jdbc.update("UPDATE coupon_campaign SET claim_end_at=?,use_end_at=? WHERE id=?",time(base.plusSeconds(60)),time(base.plusSeconds(90)),id(c));var p=principal(account("STUDENT"));var r=accept(c,p,"short");assertThat(r.getDeadline()).isEqualTo(time(base.plusSeconds(90)));at(base.plusSeconds(90));assertThat(data(call("GET","/api/v1/coupon-claims/"+r.getId(),token(p),null,null),200).path("status").asString()).isEqualTo("PENDING");assertThat(issuance.expire(r.getId())).isTrue();recovery.settle(r.getId());data(post(c,token(p),"short"),200);assertThat(stats(c).free()).isEqualTo(1);
 }
 @Test void pendingPrecedesStopAndOriginalSessionLogoutDoesNotPreventIssue(){var c=opened(1);var p=principal(account("STUDENT"));var r=accept(c,p,"beforeStop");tx().executeWithoutResult(s->{shops.share(c.row().getShopId());gates.lock(id(c));campaigns.stop(id(c),c.merchant().getId(),1,time(base));});jdbc.update("UPDATE auth_session SET revoked_at=? WHERE jti=?",time(base),p.jti());deliver(r);recovery.settle(r.getId());assertThat(state(r).getStatus()).isEqualTo("SUCCEEDED");assertThat(issued(c)).isEqualTo(1);}
 @Test void maintenanceRequiresPauseUpgradeProofAndExactControlVersion(){
  var c=campaign(2);refused(()->maintenance.run("Async",id(c),0L,false),"ALL_INSTANCES_UPGRADED_REQUIRED");refused(()->maintenance.run("Async",id(c),0L,true),"PAUSE_FIRST");action(c,"Pause");refused(()->maintenance.run("Async",id(c),0L,true),"CONTROL_VERSION_CHANGED");action(c,"Async");assertThat(inspect(c).phase()).isEqualTo("PAUSED");unavailable(()->admission.claim(principal(account("STUDENT")),"closed",body(c)));action(c,"Resume");assertThat(inspect(c).phase()).isEqualTo("OPEN");
 }
 @Test void pauseAllowsOldPendingToDrainButModeSwitchRefusesPending(){
  var c=opened(2);var r=accept(c,principal(account("STUDENT")),"drain");action(c,"Pause");refused(()->action(c,"Sync"),"PENDING_MUST_DRAIN");deliver(r);action(c,"Sync");assertThat(inspect(c).mode()).isEqualTo("SYNC");assertThat(inspect(c).phase()).isEqualTo("PAUSED");action(c,"Resume");var result=admission.claim(principal(account("STUDENT")),"syncNow",body(c));assertThat(result.result().status()).isEqualTo("SUCCEEDED");assertThat(issued(c)).isEqualTo(2);
 }
 @Test void switchSyncFencesOldReservationsAndRetainsLifetimeIssuedUsers(){
  var c=opened(3);var p=principal(account("STUDENT"));var r=accept(c,p,"issued");deliver(r);var old=gates.find(id(c));store.reserve(id(c),old.generation(),old.redisRunId(),account("STUDENT").getId(),"orphan","a".repeat(32),base);action(c,"Pause");action(c,"Sync");
  assertThatThrownBy(()->store.reserve(id(c),old.generation(),old.redisRunId(),account("STUDENT").getId(),"late","b".repeat(32),base)).isInstanceOf(CouponRedisStore.Unavailable.class);action(c,"Async");action(c,"Resume");assertThat(stats(c)).isEqualTo(new CouponRedisStore.Stats(3,2,0,1));assertThatThrownBy(()->admission.claim(p,"again",body(c))).isInstanceOf(BusinessException.class);assertThat(admission.claim(p,"issued",body(c)).result().status()).isEqualTo("SUCCEEDED");
 }
 @Test void rebuildCrashDuringResumePreservesExplicitOpenIntentAndLease(){
  var c=campaign(1);action(c,"Pause");action(c,"Async");doThrow(new IllegalStateException("injected initialize failure")).when(store).initialize(any(),any(),anyInt());assertThatThrownBy(()->action(c,"Resume")).isInstanceOf(IllegalStateException.class);assertThat(gates.find(id(c)).resumePhase()).isEqualTo("OPEN");unavailable(()->admission.claim(principal(account("STUDENT")),"during",body(c)));reset(store);at(base.plusSeconds(31));recovery.rebuild(id(c));assertThat(inspect(c).phase()).isEqualTo("OPEN");accept(c,principal(account("STUDENT")),"after");
 }
 @Test void staleModeOperatorCannotAdoptNewerResumeVersion(){
  var c=opened(1);action(c,"Pause");long before=inspect(c).controlVersion();action(c,"Resume");refused(()->maintenance.run("Sync",id(c),before,true),"CONTROL_VERSION_CHANGED");assertThat(inspect(c).mode()).isEqualTo("ASYNC");assertThat(inspect(c).phase()).isEqualTo("OPEN");
 }
 @Test void corruptSqlIssuanceCountPreventsModeChange(){var c=campaign(2);action(c,"Pause");jdbc.update("UPDATE coupon_campaign SET issued_count=1 WHERE id=?",id(c));refused(()->action(c,"Async"),"ISSUED_COUNT_MISMATCH");assertThat(inspect(c).mode()).isEqualTo("SYNC");}
 @Test void missingOrUntrustedRedisNeverFallsBackToSync(){var c=opened(1);doThrow(new CouponRedisStore.Unavailable("MISSING")).when(store).reserve(anyLong(),anyLong(),any(),anyLong(),any(),any(),any());unavailable(()->admission.claim(principal(account("STUDENT")),"missing",body(c)));assertThat(issued(c)).isZero();assertThat(inspect(c).mode()).isEqualTo("ASYNC");}
 @Test void finalSessionExpiryRollsBackRequestAndOutboxTogether(){
  var c=opened(1);var p=principal(account("STUDENT"));doAnswer(i->{var written=i.callRealMethod();at(base.plusSeconds(7200));return written;}).when(outboxWrites).append(anyString(),anyString(),anyString(),anyLong(),anyLong(),anyLong(),anyString(),anyString(),any());
  assertThatThrownBy(()->admission.claim(p,"expiry",body(c))).isInstanceOf(BusinessException.class);assertThat(requests.findKey(p.accountId(),"expiry")).isNull();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM message_outbox WHERE event_type='COUPON_ISSUE_REQUESTED'",Integer.class)).isZero();assertThat(stats(c).held()).isEqualTo(1);
 }
 @Test void sameGlobalKeyRacingAcrossActivitiesOnlyAdmitsFirstAndOtherOrphanReleases(){
  var c=opened(1);var other=opened(1);var p=principal(account("STUDENT"));
  doAnswer(i->{var reserved=i.callRealMethod();reset(store);accept(other,p,"global");return reserved;}).when(store).reserve(eq(id(c)),anyLong(),any(),anyLong(),any(),any(),any());
  assertThatThrownBy(()->admission.claim(p,"global",body(c))).isInstanceOf(BusinessException.class).satisfies(e->assertThat(((BusinessException)e).code()).isEqualTo("IDEMPOTENCY_KEY_CONFLICT"));assertThat(stats(c).held()).isEqualTo(1);assertThat(stats(other).held()).isEqualTo(1);at(base.plusSeconds(30));recovery.reap(id(c),"0");assertThat(stats(c).free()).isEqualTo(1);assertThat(stats(other).held()).isEqualTo(1);
 }
 @Test void admissionCannotRunInsideAnOuterSqlTransaction(){var c=opened(1);var p=principal(account("STUDENT"));assertThatThrownBy(()->tx().execute(s->admission.claim(p,"io",body(c)))).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);assertThat(stats(c).held()).isZero();}
 @Test void explicitMaintenanceMainRunsInSeparateNonWebProcess()throws Exception{
  var c=campaign(1);var output=java.nio.file.Path.of("target","coupon-maintenance-"+UUID.randomUUID()+".log");
  var pb=new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"-cp",System.getProperty("java.class.path"),"com.campuslife.coupon.bootstrap.CouponMaintenanceBootstrap").redirectErrorStream(true).redirectOutput(output.toFile());
  pb.environment().put("COUPON_MAINTENANCE_ACTION","Inspect");pb.environment().put("COUPON_CAMPAIGN_ID",Long.toString(id(c)));pb.environment().remove("COUPON_CONTROL_VERSION");
  var process=pb.start();if(!process.waitFor(45,TimeUnit.SECONDS)){process.destroyForcibly();throw new AssertionError("Owned maintenance process exceeded wait");}
  assertThat(process.exitValue()).as("Inspect the owned target/coupon-maintenance log if bootstrap failed").isZero();assertThat(java.nio.file.Files.readString(output)).contains("campaign="+id(c)+" mode=SYNC phase=OPEN").doesNotContain("Tomcat started on port");
 }
 @Test void unknownSqlCommitAndUnreadableReplayReturns503WithoutCompensatingGuess(){
  var c=opened(1);var p=principal(account("STUDENT"));doThrow(new IllegalStateException("injected unknown commit")).when(admissionTx).accept(any(),anyString(),any(),anyString());doThrow(new IllegalStateException("injected SQL unavailable")).when(admissionTx).replay(any(),anyLong(),anyString());
  unavailable(()->admission.claim(p,"unknownSql",body(c)));assertThat(stats(c).held()).isEqualTo(1);assertThat(issued(c)).isZero();
 }
 @RepeatedTest(3) void concurrentResumeAndSyncCannotOpenBothModes()throws Exception{
  var c=opened(1);action(c,"Pause");long v=inspect(c).controlVersion();var start=new CountDownLatch(1);var outcomes=new ArrayList<Boolean>();
  try(var pool=Executors.newFixedThreadPool(2)){var fs=new ArrayList<Future<Boolean>>();for(String a:List.of("Resume","Sync")){fs.add(pool.submit(()->{start.await();try{maintenance.run(a,id(c),v,true);return true;}catch(Refused|CouponRecoveryTransactions.Fenced e){return false;}}));}start.countDown();for(var f:fs){outcomes.add(f.get(20,TimeUnit.SECONDS));}}
  assertThat(Collections.frequency(outcomes,true)).isEqualTo(1);var s=inspect(c);assertThat(s.phase()).isIn("OPEN","PAUSED");if(s.mode().equals("SYNC")){assertThat(s.phase()).isEqualTo("PAUSED");action(c,"Resume");}else{assertThat(s.phase()).isEqualTo("OPEN");}assertThat(admission.claim(principal(account("STUDENT")),"winner",body(c)).result().status()).isEqualTo(s.mode().equals("SYNC")?"SUCCEEDED":"PENDING");
 }
 @Test void realHttpToOutboxRabbitConsumerAndRedisSettlement()throws Exception{
  rabbitAdmin.initialize();rabbitAdmin.purgeQueue(COUPON_QUEUE,false);var c=opened(1);var p=principal(account("STUDENT"));var response=post(c,token(p),"full");data(response,202);var r=requests.findKey(p.accountId(),"full");var row=publishing.claim();assertThat(transport.publish(row)).isEqualTo(OutboxTransactions.Outcome.ACK);publishing.finish(row,OutboxTransactions.Outcome.ACK);consumer.start();long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while("PENDING".equals(state(r).getStatus())&&System.nanoTime()<end){Thread.sleep(50);}consumer.stop();assertThat(state(r).getStatus()).isEqualTo("SUCCEEDED");recovery.settle(r.getId());assertThat(data(call("GET",response.headers().firstValue("Location").orElseThrow(),token(p),null,null),200).path("status").asString()).isEqualTo("SUCCEEDED");assertThat(issued(c)).isEqualTo(1);assertThat(stats(c).spent()).isEqualTo(1);rabbitAdmin.purgeQueue(COUPON_QUEUE,false);
 }
}
