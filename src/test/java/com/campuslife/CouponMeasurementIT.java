package com.campuslife;

import com.campuslife.auth.security.*;
import com.campuslife.coupon.redis.CouponRedisStore;
import com.campuslife.coupon.service.CouponMaintenance;
import io.lettuce.core.*;
import io.lettuce.core.api.StatefulRedisConnection;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.reset;
import static com.campuslife.messaging.config.RabbitMessagingConfiguration.*;

/** Measurement workflow, not a throughput threshold test. All timed requests go to an unmocked child JVM. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"campuslife.messaging.enabled=true","campuslife.messaging.consumer-enabled=false","campuslife.messaging.scheduling-enabled=false","campuslife.coupon-redis.scheduling-enabled=false","campuslife.coupon.expiry-enabled=false","campuslife.booking.expiry-enabled=false","campuslife.print.expiry-enabled=false","campuslife.files.cleanup-enabled=false"})
@ActiveProfiles("mysql")
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="CAMPUSLIFE_COUPON_BENCH",matches="owned-isolated-fixture")
class CouponMeasurementIT extends CouponAsyncFixture {
 @Autowired CouponMaintenance maintenance;@Autowired CouponRedisStore store;@Autowired TokenService tokens;@Autowired ObjectMapper json;
 final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
 final List<Map<String,Object>> results=new ArrayList<>();Path folder,comparisonRoot;Process child;Connection observer;RedisClient redisClient;StatefulRedisConnection<String,String> redis;int port,serial;boolean complete;String profile,binary;Map<String,Object> environment;Map<String,Object> comparisonIdentity;
 record Person(long id,String token){}
 static final class Observation {
  final String id,token;volatile long start,complete;volatile String state="PENDING";volatile String failure;volatile long lastPoll,maxGap;final AtomicInteger polls=new AtomicInteger();
  Observation(String id,String token,long start){this.id=id;this.token=token;this.start=start;}
 }
 @BeforeAll void owned()throws Exception{
  guard("CAMPUSLIFE_MQ_CONTAINER","CAMPUSLIFE_MQ_IDENTITY","campuslife.rabbit");guard("CAMPUSLIFE_COUPON_REDIS_CONTAINER","CAMPUSLIFE_COUPON_REDIS_IDENTITY","campuslife.coupon");
  profile=System.getenv("COUPON_BENCH_PROFILE");assertThat(profile).isIn("Pilot","Measure");folder=Files.createTempDirectory(Path.of("target"),"coupon-measurement-");
  // Root is passwordless only on this freshly initialized, labelled/guarded loopback test instance.
  observer=DriverManager.getConnection("jdbc:mysql://127.0.0.1:"+System.getenv("DB_PORT")+"/performance_schema?sslMode=PREFERRED&connectTimeout=3000&socketTimeout=5000","root","");
  try(var s=observer.createStatement();var r=s.executeQuery("SELECT @@datadir,@@performance_schema,VERSION()")){r.next();String data=r.getString(1).replace('\\','/');assertThat(data.toLowerCase(Locale.ROOT)).contains("/campuslife/.cache/mysql-it/");assertThat(r.getBoolean(2)).as("Performance Schema must be available").isTrue();environment=new LinkedHashMap<>(Map.of("mysql",r.getString(3),"performanceSchema",true));}
  redisClient=RedisClient.create(RedisURI.builder().withHost("127.0.0.1").withPort(Integer.parseInt(System.getenv("COUPON_REDIS_PORT"))).withAuthentication("fixture_admin",System.getenv("CAMPUSLIFE_COUPON_REDIS_ADMIN_PASSWORD")).withTimeout(Duration.ofSeconds(3)).build());redis=redisClient.connect();
  if("OLD_NEW".equals(System.getenv("COUPON_BENCH_COMPARE"))){comparisonIdentity=PublisherComparisonClasspath.validate();comparisonRoot=folder;binary="OLD";folder=Files.createDirectory(comparisonRoot.resolve("old"));}
  startChild();environment.put("profile",profile);environment.put("pollIntervalMs",250);environment.put("sampleIntervalMs",1000);environment.put("seed",20261101);environment.put("observerSchema","performance_schema");
 }
 @BeforeEach void realClock(){reset(clock);}
 @AfterAll void close()throws Exception{
  stopChild();
  if(redis!=null){redis.close();}if(redisClient!=null){redisClient.shutdown();}if(observer!=null){observer.close();}
  save();
 }
 void stopChild()throws Exception{if(child!=null){child.destroy();if(!child.waitFor(15,TimeUnit.SECONDS)){child.destroyForcibly();assertThat(child.waitFor(10,TimeUnit.SECONDS)).isTrue();}child=null;}}
 void save()throws Exception{if(folder!=null){Files.writeString(folder.resolve("results.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("profile",profile,"complete",complete,"environment",environment==null?Map.of():environment,"runs",results)));Files.writeString(Path.of("target/coupon-measurement-latest.json"),json.writeValueAsString(Map.of("folder",folder.toString(),"profile",profile,"complete",complete)));}}
 void startChild()throws Exception{
  String cp=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));if(binary!=null){cp=PublisherComparisonClasspath.select(cp,binary);}var b=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"-Xms256m","-Xmx512m","-cp",cp,CouponMeasurementChild.class.getName());
  if(binary!=null){b.environment().put("COUPON_BENCH_BINARY",binary);}
  b.environment().put("COUPON_BENCH_FOLDER",folder.toAbsolutePath().toString());b.redirectErrorStream(true).redirectOutput(folder.resolve("application.log").toFile());long start=System.nanoTime();child=b.start();
  await(()->Files.exists(folder.resolve("ready.json"))||!child.isAlive(),75);assertThat(child.isAlive()).as(folder.resolve("application.log").toString()).isTrue();var ready=json.readTree(Files.readString(folder.resolve("ready.json")));port=ready.path("port").asInt();environment.put("child",json.convertValue(ready,Map.class));environment.put("startupMs",elapsed(start));await(()->Files.exists(folder.resolve("process.json")),5);
 }
 @Test void pairedPublicHttpMeasurement()throws Exception{
  measureVersion();
  if(comparisonRoot!=null){
   // End the old JVM completely, verify empty SQL/queues, then start a fresh new JVM. No overlap.
   stopChild();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM message_outbox WHERE status IN ('READY','PUBLISHING')",Integer.class)).isZero();
   awaitBrokerDrain();
   environment=new LinkedHashMap<>(environment);environment.remove("child");results.clear();serial=0;complete=false;binary="NEW";folder=Files.createDirectory(comparisonRoot.resolve("new"));startChild();measureVersion();
   Files.writeString(comparisonRoot.resolve("comparison.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("complete",true,"order",List.of("OLD","NEW"),"identity",comparisonIdentity,"oldResults",comparisonRoot.resolve("old/results.json").toString(),"newResults",folder.resolve("results.json").toString(),"limitation","versions execute serially; compare each version's SYNC reference for temporal drift")));
   Files.writeString(Path.of("target/coupon-comparison-latest.json"),json.writeValueAsString(Map.of("folder",comparisonRoot.toString(),"complete",true)));
  }
 }
 void measureVersion()throws Exception{
  boolean pilot=profile.equals("Pilot");int people=pilot?6:100,stock=pilot?2:20,window=pilot?5:25;
  run("warmup",0,"SYNC",5,people,stock,false,window);run("warmup",0,"ASYNC",5,people,stock,false,window);
  // Three matched pairs per concurrency: AB, BA, AB. First four trials form ABBA.
  for(int concurrency:new int[]{1,5,10}){for(int pair=1;pair<=(pilot?1:3);pair++){String[] order=pair%2==1?new String[]{"SYNC","ASYNC"}:new String[]{"ASYNC","SYNC"};for(String mode:order){run("scarce",pair,mode,concurrency,people,stock,false,window);}}}
  for(String mode:new String[]{"SYNC","ASYNC"}){run("duplicate",1,mode,10,1,1,true,pilot?5:6);run("adequate",1,mode,5,pilot?3:10,pilot?3:10,false,pilot?5:15);}
  complete=true;save();
 }
 void run(String kind,int pair,String mode,int concurrency,int people,int stock,boolean duplicates,int minimumSeconds)throws Exception{
  reset(clock);base=Instant.now();var c=campaign(stock);long campaign=c.row().getId();int trial=++serial;long switchStart=System.nanoTime();
  if(mode.equals("ASYNC")){action(c,"Pause");action(c,"Async");action(c,"Resume");}long switchMs=elapsed(switchStart);
  var persons=new ArrayList<Person>();for(int n=0;n<people;n++){var p=principal(account("STUDENT"));var t=tokens.issue(p.accountId(),p.jti());jdbc.update("UPDATE auth_session SET expires_at=?,created_at=? WHERE account_id=? AND jti=?",time(t.expiresAt()),time(t.issuedAt()),p.accountId(),p.jti());persons.add(new Person(p.accountId(),t.value()));}
  var jobs=new ArrayList<Integer>();if(duplicates){for(int n=0;n<16;n++){jobs.add(0);}}else{for(int n=0;n<people;n++){jobs.add(n);}}Collections.shuffle(jobs,new Random(20261101));
  var admissions=new ConcurrentLinkedQueue<Map<String,Object>>();var errors=new ConcurrentLinkedQueue<String>();var observations=new ConcurrentHashMap<String,Observation>();var samples=new ConcurrentLinkedQueue<Map<String,Object>>();
  var sqlBefore=sqlSnapshot();var redisBefore=redisSnapshot();var processBefore=processSample();long started=System.nanoTime();long wall=System.currentTimeMillis();
  var scheduled=Executors.newScheduledThreadPool(2);var workers=Executors.newFixedThreadPool(concurrency);List<Future<?>> pending=new ArrayList<>();AtomicInteger gets=new AtomicInteger();AtomicBoolean abort=new AtomicBoolean();
  scheduled.scheduleWithFixedDelay(()->{try{for(var o:observations.values()){if(o.complete==0&&!abort.get()){poll(o,gets);}}}catch(Exception|AssertionError ex){errors.add("poll:"+ex.getClass().getSimpleName());abort.set(true);}},250,250,TimeUnit.MILLISECONDS);
  scheduled.scheduleWithFixedDelay(()->{try{samples.add(sample(campaign,started));}catch(Exception|AssertionError ex){errors.add("sample:"+ex.getClass().getSimpleName());abort.set(true);}},0,1,TimeUnit.SECONDS);
  try{
   for(int student:jobs){pending.add(workers.submit(()->{
    if(abort.get()){admissions.add(Map.of("studentIndex",student,"offsetMs",elapsed(started),"latencyMs",0,"http",-1,"code","cancelled_after_fault"));return;}
    long sent=System.nanoTime();var p=persons.get(student);String key=String.format(Locale.ROOT,"bench_%05d_%03d",trial,student);
    try{
     var response=call("POST","/api/v1/coupon-claims",p.token(),key,json.writeValueAsString(Map.of("campaignId",Long.toString(campaign))));long nanos=System.nanoTime()-sent;var body=json.readTree(response.body());
     var row=new LinkedHashMap<String,Object>();row.put("studentIndex",student);row.put("offsetMs",(sent-started)/1_000_000.0);row.put("latencyMs",nanos/1_000_000.0);row.put("http",response.statusCode());row.put("replay",response.headers().firstValue("Idempotency-Replayed").orElse("absent"));row.put("code",body.path("code").asString());
     if(!Set.of(200,201,202,409).contains(response.statusCode())){errors.add("post:http_"+response.statusCode());abort.set(true);}
     if(Set.of(200,201,202).contains(response.statusCode())){
      String id=body.path("data").path("id").asString();row.put("claimId",id);var o=observations.compute(id,(k,prior)->{if(prior==null){return new Observation(id,p.token(),sent);}prior.start=Math.min(prior.start,sent);return prior;});poll(o,gets);
     }admissions.add(row);
    }catch(Exception|AssertionError ex){errors.add("post:"+ex.getClass().getSimpleName());abort.set(true);admissions.add(Map.of("studentIndex",student,"offsetMs",(sent-started)/1_000_000.0,"latencyMs",(System.nanoTime()-sent)/1_000_000.0,"http",0));}
   }));}
   for(var future:pending){future.get(60,TimeUnit.SECONDS);}
   long minimum=TimeUnit.SECONDS.toNanos(minimumSeconds);while(System.nanoTime()-started<minimum||observations.values().stream().anyMatch(o->o.complete==0)){
    if(abort.get()){break;}if(elapsed(started)>310000){errors.add("drain:original_deadline_exceeded");break;}Thread.sleep(100);
   }
  }finally{workers.shutdownNow();scheduled.shutdown();assertThat(scheduled.awaitTermination(15,TimeUnit.SECONDS)).isTrue();}
  long windowMs=elapsed(started);var processAfter=processSample();var redisAfter=redisSnapshot();var sqlAfter=sqlSnapshot();
  var raw=new LinkedHashMap<String,Object>();raw.put("trial",trial);raw.put("kind",kind);raw.put("pair",pair);raw.put("mode",mode);raw.put("concurrency",concurrency);raw.put("people",people);raw.put("stock",stock);raw.put("posts",jobs.size());raw.put("windowMs",windowMs);raw.put("plannedWindowSeconds",minimumSeconds);raw.put("switchMs",switchMs);raw.put("wallStarted",Instant.ofEpochMilli(wall).toString());raw.put("getRequests",gets.get());raw.put("admissions",admissions.stream().sorted(Comparator.comparingDouble(m->((Number)m.get("offsetMs")).doubleValue())).toList());
  raw.put("observations",observations.values().stream().map(o->{var m=new LinkedHashMap<String,Object>();m.put("id",o.id);m.put("state",o.state);m.put("completeMs",o.complete==0?null:(o.complete-o.start)/1_000_000.0);m.put("cohortCompleteMs",o.complete==0?null:(o.complete-started)/1_000_000.0);m.put("polls",o.polls.get());m.put("maxPollGapMs",o.maxGap/1_000_000.0);m.put("failure",o.failure);return m;}).toList());
  raw.put("samples",samples);raw.put("errors",errors);raw.put("sql",sqlDelta(sqlBefore,sqlAfter));raw.put("redisBefore",redisBefore);raw.put("redisAfter",redisAfter);raw.put("processBefore",processBefore);raw.put("processAfter",processAfter);results.add(raw);save();
  assertThat(errors).as("Observer/HTTP errors invalidate this run; raw evidence retained").isEmpty();assertThat(child.isAlive()).isTrue();
  int awarded=Math.min(people,stock);assertThat(observations.size()).isEqualTo(awarded);assertThat(observations.values()).allMatch(o->o.state.equals("SUCCEEDED"));
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_coupon WHERE campaign_id=?",Integer.class,campaign)).isEqualTo(awarded);assertThat(issued(c)).isEqualTo(awarded);
  assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT student_id) FROM user_coupon WHERE campaign_id=?",Integer.class,campaign)).isEqualTo(awarded);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon_claim_request WHERE campaign_id=? AND status='SUCCEEDED'",Integer.class,campaign)).isEqualTo(awarded);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon_claim_request WHERE campaign_id=?",Integer.class,campaign)).isEqualTo(awarded);
  long accepted=admissions.stream().filter(m->((Number)m.get("http")).intValue()==(mode.equals("ASYNC")?202:201)).count();
  if(!duplicates){assertThat(accepted).isEqualTo(awarded);assertThat(admissions.stream().filter(m->((Number)m.get("http")).intValue()==409).count()).isEqualTo(people-awarded);}else{assertThat(admissions).allMatch(m->Set.of(200,201,202).contains(((Number)m.get("http")).intValue()));assertThat(observations.size()).isEqualTo(1);}
  int outboxes=jdbc.queryForObject("SELECT COUNT(*) FROM message_outbox WHERE event_type='COUPON_ISSUE_REQUESTED' AND aggregate_id IN (SELECT id FROM coupon_claim_request WHERE campaign_id=?)",Integer.class,campaign);assertThat(outboxes).isEqualTo(mode.equals("ASYNC")?awarded:0);
  if(mode.equals("ASYNC")){
   var g=gates.find(campaign);assertThat(store.inspect(campaign,g.generation(),g.redisRunId())).isEqualTo(new CouponRedisStore.Stats(stock,stock-awarded,0,awarded));
   assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon_claim_request WHERE campaign_id=? AND redis_settled_generation=?",Integer.class,campaign,g.generation())).isEqualTo(awarded);
   assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM message_outbox WHERE aggregate_id IN (SELECT id FROM coupon_claim_request WHERE campaign_id=?) AND status='SENT' AND attempts=1",Integer.class,campaign)).isEqualTo(awarded);assertThat(receiptCount()).isEqualTo(awarded);
  }
  // Management counters refresh asynchronously. Observe drain after the fixed timed cost window;
  // never purge/ACK to make a measurement pass, and retain the extra wait separately.
  long drainStarted=System.nanoTime();var drainSamples=awaitBrokerDrain();raw.put("postWindowBrokerDrainMs",elapsed(drainStarted));raw.put("postWindowBrokerDrainSamples",drainSamples);
  raw.put("invariants","passed");save();System.out.println("COUPON_BENCH trial="+trial+" kind="+kind+" pair="+pair+" mode="+mode+" concurrency="+concurrency+" awarded="+awarded+" windowMs="+windowMs);
  clean();
 }
 void action(Campaign c,String action){maintenance.run(action,c.row().getId(),maintenance.run("Inspect",c.row().getId(),null,false).controlVersion(),true);}
 HttpResponse<String> call(String method,String path,String token,String key,String body)throws Exception{
  var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15)).header("Authorization","Bearer "+token);if(key!=null){b.header("Idempotency-Key",key);}if(body!=null){b.header("Content-Type","application/json");}
  return http.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
 }
 void poll(Observation o,AtomicInteger total)throws Exception{synchronized(o){if(o.complete!=0){return;}long now=System.nanoTime();if(o.lastPoll!=0){o.maxGap=Math.max(o.maxGap,now-o.lastPoll);}o.lastPoll=now;var r=call("GET","/api/v1/coupon-claims/"+o.id,o.token,null,null);assertThat(r.statusCode()).as("Completion GET").isEqualTo(200);var data=json.readTree(r.body()).path("data");o.polls.incrementAndGet();total.incrementAndGet();o.state=data.path("status").asString();if(!o.state.equals("PENDING")){o.complete=System.nanoTime();o.failure=data.path("failureCode").asString();}}}
 synchronized Map<String,Object> sample(long campaign,long start)throws Exception{
  var m=new LinkedHashMap<String,Object>();m.put("elapsedMs",elapsed(start));
  try(var s=observer.createStatement();var r=s.executeQuery("SELECT (SELECT COUNT(*) FROM campuslife_it.user_coupon WHERE campaign_id="+campaign+") issued,(SELECT COUNT(*) FROM campuslife_it.coupon_claim_request WHERE campaign_id="+campaign+" AND status='PENDING') pending,(SELECT COUNT(*) FROM campuslife_it.message_outbox o JOIN campuslife_it.coupon_claim_request r ON r.id=o.aggregate_id WHERE r.campaign_id="+campaign+" AND o.status IN ('READY','PUBLISHING')) backlog")){r.next();m.put("issued",r.getLong(1));m.put("pending",r.getLong(2));m.put("outboxBacklog",r.getLong(3));}
  var q=queue();m.put("queueReady",q.path("messages_ready").asLong());m.put("queueUnacked",q.path("messages_unacknowledged").asLong());m.put("dead",queue(COUPON_DEAD_QUEUE).path("messages").asLong());m.put("redis",redisSnapshot());m.put("process",processSample());return m;
 }
 JsonNode queue()throws Exception{return queue(COUPON_QUEUE);}
 List<Map<String,Object>> awaitBrokerDrain()throws Exception{
  long start=System.nanoTime();var samples=new ArrayList<Map<String,Object>>();
  for(;;){var q=queue();var dead=queue(COUPON_DEAD_QUEUE);long total=q.path("messages").asLong(),unacked=q.path("messages_unacknowledged").asLong(),dlq=dead.path("messages").asLong();
   samples.add(Map.of("elapsedMs",elapsed(start),"messages",total,"unacked",unacked,"dead",dlq));assertThat(dlq).as("No dead letter during healthy measurement").isZero();
   if(total==0&&unacked==0){return samples;}assertThat(elapsed(start)).as("Broker drain did not converge; retain invalid run").isLessThan(30000);Thread.sleep(250);
  }
 }
 JsonNode queue(String name)throws Exception{
  String auth=Base64.getEncoder().encodeToString((System.getenv("MESSAGING_USERNAME")+":"+System.getenv("MESSAGING_PASSWORD")).getBytes(StandardCharsets.UTF_8));String uri="http://127.0.0.1:"+System.getenv("CAMPUSLIFE_MQ_MANAGEMENT_PORT")+"/api/queues/"+System.getenv("MESSAGING_VHOST")+"/"+name;
  var r=http.send(HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(3)).header("Authorization","Basic "+auth).build(),HttpResponse.BodyHandlers.ofString());assertThat(r.statusCode()).as("Owned broker management").isEqualTo(200);return json.readTree(r.body());
 }
 Map<String,Object> processSample()throws Exception{return json.readValue(Files.readString(folder.resolve("process.json")),Map.class);}
 Map<String,Double> redisSnapshot(){var m=new LinkedHashMap<String,Double>();String info=redis.sync().info();for(String line:info.split("\\r?\\n")){int split=line.indexOf(':');if(split>0){String key=line.substring(0,split);if(Set.of("used_memory","used_cpu_sys","used_cpu_user","total_commands_processed","evicted_keys","rejected_connections","aof_pending_bio_fsync").contains(key)){m.put(key,Double.parseDouble(line.substring(split+1).strip()));}}}return m;}
 synchronized Map<String,Map<String,Object>> sqlSnapshot()throws Exception{
  var rows=new LinkedHashMap<String,Map<String,Object>>();
  try(var s=observer.createStatement();var r=s.executeQuery("SELECT DIGEST,DIGEST_TEXT,COUNT_STAR,SUM_TIMER_WAIT,SUM_LOCK_TIME,SUM_ROWS_EXAMINED,SUM_ROWS_AFFECTED FROM performance_schema.events_statements_summary_by_digest WHERE SCHEMA_NAME='campuslife_it' AND DIGEST IS NOT NULL")){while(r.next()){rows.put(r.getString(1),new LinkedHashMap<>(Map.of("text",r.getString(2),"count",r.getLong(3),"waitPs",r.getDouble(4),"lockPs",r.getDouble(5),"examined",r.getLong(6),"affected",r.getLong(7))));}}
  try(var s=observer.createStatement();var r=s.executeQuery("SELECT VARIABLE_NAME,VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME IN ('Innodb_row_lock_waits','Innodb_row_lock_time','Innodb_row_lock_current_waits','Innodb_os_log_written','Threads_running')")){while(r.next()){rows.put("global:"+r.getString(1),new LinkedHashMap<>(Map.of("text",r.getString(1),"count",Long.parseLong(r.getString(2)))));}}return rows;
 }
 List<Map<String,Object>> sqlDelta(Map<String,Map<String,Object>> before,Map<String,Map<String,Object>> after){var rows=new ArrayList<Map<String,Object>>();after.forEach((key,value)->{var old=before.getOrDefault(key,Map.of());var row=new LinkedHashMap<String,Object>();row.put("digest",key);row.put("text",value.get("text"));for(String field:List.of("count","waitPs","lockPs","examined","affected")){if(value.containsKey(field)){row.put(field,((Number)value.get(field)).doubleValue()-((Number)old.getOrDefault(field,0)).doubleValue());}}if(((Number)row.get("count")).doubleValue()!=0||key.startsWith("global:")){rows.add(row);}});return rows;}
 void guard(String containerEnv,String identityEnv,String prefix)throws Exception{assertThat(System.getenv("DB_NAME")).isEqualTo("campuslife_it");String id=System.getenv(containerEnv);assertThat(id).matches("[a-f0-9]{64}");var p=new ProcessBuilder("docker","inspect","--format","{{json .Config.Labels}}",id).redirectErrorStream(true).start();assertThat(p.waitFor(10,TimeUnit.SECONDS)).isTrue();assertThat(p.exitValue()).isZero();var labels=json.readTree(new String(p.getInputStream().readAllBytes(),StandardCharsets.UTF_8));assertThat(labels.path(prefix+".identity").asString()).isEqualTo(System.getenv(identityEnv));assertThat(labels.path(prefix+".kind").asString()).isEqualTo("test");}
 void await(Callable<Boolean> check,int seconds)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);while(System.nanoTime()<until){if(check.call()){return;}Thread.sleep(100);}throw new AssertionError("Benchmark fixture startup timed out");}
 static long elapsed(long start){return (System.nanoTime()-start)/1_000_000;}
}
