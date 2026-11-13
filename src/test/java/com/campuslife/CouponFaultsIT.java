package com.campuslife;

import com.campuslife.auth.security.*;
import com.campuslife.coupon.entity.ClaimRequestEntity;
import com.campuslife.coupon.redis.CouponRedisStore;
import com.campuslife.coupon.service.*;
import com.campuslife.messaging.service.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import tools.jackson.databind.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.reset;
import static com.campuslife.messaging.config.RabbitMessagingConfiguration.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"campuslife.messaging.enabled=true","campuslife.messaging.consumer-enabled=false","campuslife.messaging.scheduling-enabled=false","campuslife.coupon-redis.scheduling-enabled=false","campuslife.coupon.expiry-enabled=false"})
@ActiveProfiles("mysql")
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="CAMPUSLIFE_COUPON_REDIS_IT",matches="owned-isolated-fixture")
class CouponFaultsIT extends CouponAsyncFixture {
 @Autowired CouponMaintenance maintenance;@Autowired CouponRedisStore store;@Autowired TokenService tokens;@Autowired ObjectMapper json;@Autowired Environment env;
 @Autowired RabbitAdmin admin;@Autowired @Qualifier("couponIssueContainer") SimpleMessageListenerContainer consumer;
 @Autowired OutboxTransactions publishing;@Autowired RabbitOutboxTransport transport;
 final HttpClient http=HttpClient.newHttpClient();final List<Process> children=new ArrayList<>();final List<Map<String,Object>> evidence=new ArrayList<>();Path folder;
 @BeforeAll void owned()throws Exception{guard("CAMPUSLIFE_MQ_CONTAINER","CAMPUSLIFE_MQ_IDENTITY","campuslife.rabbit");guard("CAMPUSLIFE_COUPON_REDIS_CONTAINER","CAMPUSLIFE_COUPON_REDIS_IDENTITY","campuslife.coupon");folder=Files.createTempDirectory(Path.of("target"),"coupon-faults-");}
 @BeforeEach void realTime(){reset(clock);admin.initialize();admin.purgeQueue(COUPON_QUEUE,false);admin.purgeQueue(COUPON_DEAD_QUEUE,false);}
 @AfterEach void stop()throws Exception{for(var p:children){kill(p);}children.clear();consumer.stop();admin.initialize();admin.purgeQueue(COUPON_QUEUE,false);admin.purgeQueue(COUPON_DEAD_QUEUE,false);}
 @AfterAll void save()throws Exception{Files.writeString(Path.of("target/coupon-faults-evidence.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("folder",folder.toString(),"clock","real system clock; no advancement","cases",evidence)),StandardCharsets.UTF_8);}
 long id(Campaign c){return c.row().getId();}
 void action(Campaign c,String action){maintenance.run(action,id(c),maintenance.run("Inspect",id(c),null,false).controlVersion(),true);}
 Campaign opened(){var c=campaign(1);action(c,"Pause");action(c,"Async");action(c,"Resume");return c;}
 String token(AuthPrincipal p){var t=tokens.issue(p.accountId(),p.jti());jdbc.update("UPDATE auth_session SET expires_at=?,created_at=? WHERE account_id=? AND jti=?",time(t.expiresAt()),time(t.issuedAt()),p.accountId(),p.jti());return t.value();}
 int parentPort(){return Integer.parseInt(env.getRequiredProperty("local.server.port"));}
 HttpRequest postRequest(int port,Campaign c,String token,String key){return HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/coupon-claims")).timeout(Duration.ofSeconds(100)).header("Authorization","Bearer "+token).header("Idempotency-Key",key).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("campaignId",Long.toString(id(c)))))).build();}
 HttpResponse<String> post(int port,Campaign c,String token,String key)throws Exception{return http.send(postRequest(port,c,token,key),HttpResponse.BodyHandlers.ofString());}
 JsonNode data(HttpResponse<String> response,int code){assertThat(response.statusCode()).as(response.body()).isEqualTo(code);return json.readTree(response.body()).path("data");}
 ClaimRequestEntity accept(Campaign c,AuthPrincipal p,String t,String key)throws Exception{data(post(parentPort(),c,t,key),202);return requests.findKey(p.accountId(),key);}
 CouponRedisStore.Stats stats(Campaign c){var g=gates.find(id(c));return store.inspect(id(c),g.generation(),g.redisRunId());}
 int requestCount(Campaign c){return jdbc.queryForObject("SELECT COUNT(*) FROM coupon_claim_request WHERE campaign_id=?",Integer.class,id(c));}
 int outboxCount(Campaign c){return jdbc.queryForObject("SELECT COUNT(*) FROM message_outbox WHERE event_type='COUPON_ISSUE_REQUESTED' AND aggregate_id IN (SELECT id FROM coupon_claim_request WHERE campaign_id=?)",Integer.class,id(c));}
 void await(Callable<Boolean> check,int seconds)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);while(System.nanoTime()<deadline){if(check.call()){return;}Thread.sleep(150);}throw new AssertionError("Recovery timed out; inspect "+folder);}
 record Child(Process process,Path marker,int port){}
 Child child(String mode,Campaign c)throws Exception{
  Path marker=folder.resolve(mode+"-"+UUID.randomUUID()+".ready");String cp=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));var b=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"-cp",cp,CouponCrashChild.class.getName());
  b.environment().put("COUPON_CRASH_MODE",mode);b.environment().put("COUPON_CRASH_CAMPAIGN",Long.toString(id(c)));b.environment().put("COUPON_CRASH_MARKER",marker.toAbsolutePath().toString());b.redirectErrorStream(true).redirectOutput(Path.of(marker+".log").toFile());var p=b.start();children.add(p);Path started=Path.of(marker+".started");await(()->Files.exists(started)||!p.isAlive(),75);assertThat(p.isAlive()).as("child startup; "+marker+".log").isTrue();assertThat(Files.exists(started)).isTrue();return new Child(p,marker,Integer.parseInt(Files.readString(started)));
 }
 void seam(Child child)throws Exception{await(()->Files.exists(child.marker())||!child.process().isAlive(),45);assertThat(child.process().isAlive()).isTrue();assertThat(Files.exists(child.marker())).as(child.marker().toString()).isTrue();}
 void kill(Process p)throws Exception{if(p.isAlive()){p.destroyForcibly();}assertThat(p.waitFor(20,TimeUnit.SECONDS)).isTrue();}
 void publish(ClaimRequestEntity r){var row=publishing.claim();assertThat(row).isNotNull();assertThat(row.messageId()).isEqualTo(message(r));assertThat(transport.publish(row)).isEqualTo(OutboxTransactions.Outcome.ACK);assertThat(publishing.finish(row,OutboxTransactions.Outcome.ACK)).isTrue();}
 void success(Campaign c,ClaimRequestEntity r)throws Exception{
  await(()->"SUCCEEDED".equals(state(r).getStatus())&&Objects.equals(state(r).getRedisSettledGeneration(),gates.find(id(c)).generation()),80);
  await(()->"SENT".equals(outbox.find(message(r)).status()),30);
  // Broker stays healthy in these JVM-only windows: do not hide initialization starvation behind retries.
  assertThat(outbox.find(message(r)).attempts()).isEqualTo(1);
  await(()->queueStat("messages")==0&&queueStat("messages_unacknowledged")==0,30);
  assertThat(issued(c)).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_coupon WHERE campaign_id=?",Integer.class,id(c))).isEqualTo(1);assertThat(receiptCount()).isEqualTo(1);assertThat(outboxCount(c)).isEqualTo(1);assertThat(stats(c)).isEqualTo(new CouponRedisStore.Stats(1,0,0,1));assertThat(state(r).getDeadline()).isEqualTo(r.getDeadline());assertThat(admin.getQueueInfo(COUPON_DEAD_QUEUE).getMessageCount()).isZero();
 }
 void record(String mode,Campaign c,Child killed,Child restart,long start){evidence.add(Map.of("case",mode,"campaignId",id(c),"killedPid",killed.process().pid(),"restartPid",restart.process().pid(),"killToVerifiedMs",(System.nanoTime()-start)/1_000_000,"generation",gates.find(id(c)).generation(),"issued",issued(c)));}

 @ParameterizedTest @ValueSource(strings={"AFTER_RESERVE","BEFORE_COMMIT"})
 void orphanIsArbitratedAfterRealJvmCrash(String mode)throws Exception{
  var c=opened();var p=principal(account("STUDENT"));String t=token(p);var crash=child(mode,c);var inflight=http.sendAsync(postRequest(crash.port(),c,t,"orphan"),HttpResponse.BodyHandlers.ofString());seam(crash);
  assertThat(requestCount(c)).isZero();assertThat(outboxCount(c)).isZero();assertThat(stats(c).held()).isEqualTo(1);assertThat(inflight.isDone()).isFalse();long start=System.nanoTime();kill(crash.process());inflight.handle((r,e)->null).get(15,TimeUnit.SECONDS);
  var restart=child("NORMAL",c);await(()->requests.findKey(p.accountId(),"orphan")!=null&&stats(c).free()==1,80);var failed=requests.findKey(p.accountId(),"orphan");assertThat(failed.getStatus()).isEqualTo("FAILED");assertThat(failed.getFailureCode()).isEqualTo("ORPHAN_EXPIRED");assertThat(failed.getAcceptedAt()).isNull();data(post(restart.port(),c,t,"orphan"),200);assertThat(outboxCount(c)).isZero();
  var r=accept(c,p,t,"fresh");success(c,r);assertThat(requestCount(c)).isEqualTo(2);record(mode,c,crash,restart,start);
 }
 @Test void committedAdmissionSurvivesLostHttpResponse()throws Exception{
  var c=opened();var p=principal(account("STUDENT"));String t=token(p);var crash=child("AFTER_ADMISSION",c);var inflight=http.sendAsync(postRequest(crash.port(),c,t,"committed"),HttpResponse.BodyHandlers.ofString());seam(crash);
  var r=requests.findKey(p.accountId(),"committed");assertThat(r.getStatus()).isEqualTo("PENDING");assertThat(outboxCount(c)).isEqualTo(1);assertThat(stats(c).held()).isEqualTo(1);assertThat(inflight.isDone()).isFalse();String message=message(r);long start=System.nanoTime();kill(crash.process());inflight.handle((v,e)->null).get(15,TimeUnit.SECONDS);
  var restart=child("NORMAL",c);success(c,r);assertThat(data(post(restart.port(),c,t,"committed"),200).path("id").asString()).isEqualTo(r.getId().toString());assertThat(message(r)).isEqualTo(message);record("AFTER_ADMISSION",c,crash,restart,start);
 }
 @ParameterizedTest @ValueSource(strings={"BEFORE_ISSUE_COMMIT","BEFORE_ACK"})
 void issuanceRollbackOrDuplicateDeliveryRecoversOnce(String mode)throws Exception{
  var c=opened();var p=principal(account("STUDENT"));String t=token(p);var r=accept(c,p,t,"consume");publish(r);var crash=child(mode,c);seam(crash);int visible=mode.equals("BEFORE_ACK")?1:0;
  assertThat(issued(c)).isEqualTo(visible);assertThat(receiptCount()).isEqualTo(visible);await(()->queueStat("messages_unacknowledged")>=1,20);long start=System.nanoTime();kill(crash.process());var restart=child("NORMAL",c);success(c,r);data(post(restart.port(),c,t,"consume"),200);record(mode,c,crash,restart,start);
 }
 @Test void redisSettlementSurvivesCrashBeforeSqlCheckpoint()throws Exception{
  var c=opened();var p=principal(account("STUDENT"));var r=accept(c,p,token(p),"checkpoint");deliver(r);var crash=child("BEFORE_CHECKPOINT",c);seam(crash);assertThat(stats(c).spent()).isEqualTo(1);assertThat(state(r).getRedisSettledGeneration()).isNull();long start=System.nanoTime();kill(crash.process());var restart=child("NORMAL",c);success(c,r);record("BEFORE_CHECKPOINT",c,crash,restart,start);
 }
 @ParameterizedTest @ValueSource(strings={"AFTER_SEED","AFTER_ACTIVATE"})
 void rebuildTakesOverExpiredRealLeaseAndKeepsOpenIntent(String mode)throws Exception{
  var c=opened();var p=principal(account("STUDENT"));String t=token(p);var r=accept(c,p,t,"rebuild");action(c,"Pause");var crash=child(mode,c);seam(crash);var old=gates.find(id(c));assertThat(old.phase()).isEqualTo("REBUILDING");assertThat(old.resumePhase()).isEqualTo("OPEN");
  assertThat(post(parentPort(),c,t,"blocked").statusCode()).isEqualTo(503);assertThat(requestCount(c)).isEqualTo(1);long start=System.nanoTime();kill(crash.process());
  // Keep MQ stopped during takeover; a bounded consumer retry is not an automatic dead-letter replay.
  var restart=child("RECOVER",c);await(()->"OPEN".equals(gates.find(id(c)).phase()),80);var current=gates.find(id(c));assertThat(current.generation()).isGreaterThan(old.generation());assertThat(state(r).getStatus()).isEqualTo("PENDING");assertThat(state(r).getDeadline()).isEqualTo(r.getDeadline());assertThat(stats(c).held()).isEqualTo(1);
  publish(r);consumer.start();success(c,r);record(mode,c,crash,restart,start);
 }
 long queueStat(String key)throws Exception{
  String auth=Base64.getEncoder().encodeToString((System.getenv("MESSAGING_USERNAME")+":"+System.getenv("MESSAGING_PASSWORD")).getBytes(StandardCharsets.UTF_8));
  String uri="http://127.0.0.1:"+System.getenv("CAMPUSLIFE_MQ_MANAGEMENT_PORT")+"/api/queues/"+System.getenv("MESSAGING_VHOST")+"/"+COUPON_QUEUE;
  var request=HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(3)).header("Authorization","Basic "+auth).build();var response=http.send(request,HttpResponse.BodyHandlers.ofString());assertThat(response.statusCode()).isEqualTo(200);return json.readTree(response.body()).path(key).asLong();
 }
 void guard(String containerEnv,String identityEnv,String prefix)throws Exception{
  assertThat(System.getenv("CAMPUSLIFE_MQ_IT")).isEqualTo("owned-isolated-fixture");String cid=System.getenv(containerEnv);assertThat(cid).matches("[a-f0-9]{64}");var p=new ProcessBuilder("docker","inspect","--format","{{json .Config.Labels}}",cid).redirectErrorStream(true).start();assertThat(p.waitFor(10,TimeUnit.SECONDS)).isTrue();assertThat(p.exitValue()).isZero();var labels=json.readTree(new String(p.getInputStream().readAllBytes(),StandardCharsets.UTF_8));assertThat(labels.path(prefix+".identity").asString()).isEqualTo(System.getenv(identityEnv));assertThat(labels.path(prefix+".kind").asString()).isEqualTo("test");
 }
}
