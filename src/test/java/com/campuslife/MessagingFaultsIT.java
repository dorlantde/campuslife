package com.campuslife;
import com.campuslife.messaging.service.*;
import java.time.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.core.*;
import static org.assertj.core.api.Assertions.*;
import static com.campuslife.messaging.config.RabbitMessagingConfiguration.*;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"campuslife.messaging.enabled=true","campuslife.messaging.consumer-enabled=false","campuslife.messaging.scheduling-enabled=false","campuslife.booking.expiry-enabled=false","campuslife.files.cleanup-enabled=false"})
class MessagingFaultsIT extends OutboxOrderFixture {
 @Autowired NotificationListener originalListener;@Autowired SimpleMessageListenerContainer consumers;@Autowired RabbitAdmin admin;@Autowired RabbitTemplate rabbit;@Autowired RabbitOutboxTransport transport;
 private final List<Process> children=new ArrayList<>();private final List<Map<String,Object>> evidence=new ArrayList<>();private Path folder;
 @BeforeAll void owned()throws Exception{
  assertThat(System.getenv("CAMPUSLIFE_MQ_IT")).isEqualTo("owned-isolated-fixture");String cid=System.getenv("CAMPUSLIFE_MQ_CONTAINER");assertThat(cid).matches("[a-f0-9]{64}");var p=new ProcessBuilder("docker","inspect","--format","{{json .Config.Labels}}",cid).redirectErrorStream(true).start();assertThat(p.waitFor(10,TimeUnit.SECONDS)).isTrue();assertThat(p.exitValue()).isZero();var labels=json.readTree(new String(p.getInputStream().readAllBytes(),StandardCharsets.UTF_8));assertThat(labels.path("campuslife.rabbit.identity").asString()).isEqualTo(System.getenv("CAMPUSLIFE_MQ_IDENTITY"));assertThat(labels.path("campuslife.rabbit.kind").asString()).isEqualTo("test");folder=Files.createTempDirectory(Path.of("target"),"messaging-faults-");
 }
 @BeforeEach void queues(){admin.initialize();admin.purgeQueue(QUEUE,false);admin.purgeQueue(DEAD_QUEUE,false);}
 @AfterEach void cleanupProcesses()throws Exception{for(var p:children){if(p.isAlive()){p.destroyForcibly();}assertThat(p.waitFor(20,TimeUnit.SECONDS)).isTrue();}children.clear();consumers.stop();consumers.setMessageListener(originalListener);admin.initialize();admin.purgeQueue(QUEUE,false);admin.purgeQueue(DEAD_QUEUE,false);}
 @AfterAll void save()throws Exception{Files.writeString(Path.of("target/messaging-faults-evidence.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("folder",folder.toString(),"cases",evidence)),StandardCharsets.UTF_8);}
 private String event(String type)throws Exception{var c=setup(type);String order=beforeNotify(c);data(notify(c,order,login(c.f().owner())));return jdbc.queryForObject("SELECT message_id FROM message_outbox WHERE aggregate_id=?",String.class,order);}
 private void publish(String id){var row=publishing.claim();assertThat(row.messageId()).isEqualTo(id);assertThat(transport.publish(row)).isEqualTo(OutboxTransactions.Outcome.ACK);publishing.finish(row,OutboxTransactions.Outcome.ACK);}
 private int count(String table,String id){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE "+(table.equals("notification")?"source_message_id":"message_id")+"=?",Integer.class,id);}
 private void await(java.util.concurrent.Callable<Boolean> check,int seconds)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);while(System.nanoTime()<end){if(check.call()){return;}Thread.sleep(150);}throw new AssertionError("Fault recovery timed out; child logs: "+folder);}
 private Process child(String mode,String id,Path marker)throws Exception{
  String cp=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"-cp",cp,MessagingCrashChild.class.getName());builder.environment().put("CRASH_MODE",mode);builder.environment().put("CRASH_MESSAGE",id);builder.environment().put("CRASH_MARKER",marker.toAbsolutePath().toString());builder.redirectErrorStream(true).redirectOutput(folder.resolve(mode+"-"+id+".log").toFile());var p=builder.start();children.add(p);return p;
 }
 @ParameterizedTest @ValueSource(strings={"BEFORE_COMMIT","BEFORE_ACK"})
 void killedConsumerRecoversAcrossRealJvmRestart(String mode)throws Exception{
  String id=event("BOOKING");publish(id);Path marker=folder.resolve(mode+".ready");var process=child(mode,id,marker);await(()->Files.exists(marker)||!process.isAlive(),60);assertThat(process.isAlive()).as("child must reach crash seam; inspect logs").isTrue();assertThat(Files.exists(marker)).isTrue();
  int expected=mode.equals("BEFORE_ACK")?1:0;assertThat(count("notification",id)).isEqualTo(expected);assertThat(count("message_consumption",id)).isEqualTo(expected);await(()->queueStat("messages_unacknowledged")>=1,20);long start=System.nanoTime();process.destroyForcibly();assertThat(process.waitFor(20,TimeUnit.SECONDS)).isTrue();
  Path restartMarker=folder.resolve("unused-"+id);var restart=child("NORMAL",id,restartMarker);await(()->Files.exists(Path.of(restartMarker+".started"))||!restart.isAlive(),60);assertThat(Files.exists(Path.of(restartMarker+".started"))).isTrue();await(()->count("notification",id)==1&&queueStat("messages")==0,60);Thread.sleep(400);assertThat(count("notification",id)).isEqualTo(1);assertThat(count("message_consumption",id)).isEqualTo(1);assertThat(restart.isAlive()).isTrue();
  evidence.add(Map.of("case",mode,"messageId",id,"beforeKillVisibleRows",expected,"killedPid",process.pid(),"restartPid",restart.pid(),"recoveryMs",(System.nanoTime()-start)/1000000));
 }
 @Test void killedPublisherAfterConfirmRecoversLeaseWithoutChangingIdentity()throws Exception{
  String id=event("PRINT");Path marker=folder.resolve("confirm.ready");var process=child("AFTER_CONFIRM",id,marker);await(()->Files.exists(marker)||!process.isAlive(),60);assertThat(process.isAlive()).isTrue();assertThat(Files.exists(marker)).isTrue();var claimed=outbox.find(id);assertThat(claimed.status()).isEqualTo("PUBLISHING");process.destroyForcibly();assertThat(process.waitFor(20,TimeUnit.SECONDS)).isTrue();fixed(claimed.leaseUntil().toInstant(ZoneOffset.UTC));var retry=publishing.claim();assertThat(retry.messageId()).isEqualTo(id);assertThat(retry.attempts()).isEqualTo(2);assertThat(transport.publish(retry)).isEqualTo(OutboxTransactions.Outcome.ACK);publishing.finish(retry,OutboxTransactions.Outcome.ACK);
  consumers.start();await(()->count("notification",id)==1&&queueStat("messages_unacknowledged")==0,30);Thread.sleep(400);assertThat(count("notification",id)).isEqualTo(1);evidence.add(Map.of("case","AFTER_CONFIRM","messageId",id,"killedPid",process.pid(),"attempts",outbox.find(id).attempts()));
 }
 @Test void unavailableDeadLetterTargetRetainsThenTransfersOriginalMessage()throws Exception{
  String id=event("PRINT");admin.deleteQueue(DEAD_QUEUE);var rejected=new CountDownLatch(1);consumers.setMessageListener((org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener)(message,channel)->{originalListener.onMessage(message,channel);rejected.countDown();});var props=new MessageProperties();props.setMessageId(id);props.setContentType("application/json");props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);rabbit.send(EXCHANGE,ROUTING,new Message("{}".getBytes(StandardCharsets.UTF_8),props));consumers.start();assertThat(rejected.await(15,TimeUnit.SECONDS)).isTrue();Thread.sleep(2000);assertThat(admin.getQueueInfo(DEAD_QUEUE)).isNull();assertThat(count("message_consumption",id)).isZero();long retained=queueStat("messages");admin.initialize();long restored=System.nanoTime();org.springframework.amqp.core.Message dead=null;long deadline=restored+TimeUnit.SECONDS.toNanos(240);while(dead==null&&System.nanoTime()<deadline){dead=rabbit.receive(DEAD_QUEUE,2000);}assertThat(dead).isNotNull();assertThat(dead.getMessageProperties().getMessageId()).isEqualTo(id);await(()->queueStat("messages")==0,30);evidence.add(Map.of("case","DLQ_TARGET_UNAVAILABLE","messageId",id,"retainedAtSource",retained,"recovered",true,"restoreToTransferMs",(System.nanoTime()-restored)/1000000));
 }
 @Test void boundedBatchRecordsEndToEndCostWithoutCapacityClaim()throws Exception{
  var times=new ArrayList<Long>();for(int n=0;n<8;n++){String id=event(n%2==0?"BOOKING":"PRINT");long start=System.nanoTime();publish(id);consumers.start();await(()->count("notification",id)==1,20);times.add((System.nanoTime()-start)/1000000);consumers.stop();}evidence.add(Map.of("case","SMALL_BATCH","samples",times,"sampleCount",8,"producerConcurrency",1,"consumerConcurrency",2,"prefetch",10));
 }
 private long queueStat(String key)throws Exception{
  String auth=Base64.getEncoder().encodeToString((System.getenv("MESSAGING_USERNAME")+":"+System.getenv("MESSAGING_PASSWORD")).getBytes(StandardCharsets.UTF_8));String uri="http://127.0.0.1:"+System.getenv("CAMPUSLIFE_MQ_MANAGEMENT_PORT")+"/api/queues/"+System.getenv("MESSAGING_VHOST")+"/"+QUEUE;var req=HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(3)).header("Authorization","Basic "+auth).build();var response=HttpClient.newHttpClient().send(req,HttpResponse.BodyHandlers.ofString());assertThat(response.statusCode()).isEqualTo(200);return json.readTree(response.body()).path(key).asLong();
 }
}
