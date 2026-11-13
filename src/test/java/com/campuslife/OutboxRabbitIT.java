package com.campuslife;
import com.campuslife.messaging.config.RabbitMessagingConfiguration;
import com.campuslife.messaging.service.*;
import com.campuslife.messaging.model.OutboxMessage;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.campuslife.messaging.config.RabbitMessagingConfiguration.*;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"campuslife.booking.expiry-enabled=false","campuslife.print.expiry-enabled=false","campuslife.files.cleanup-enabled=false","campuslife.messaging.enabled=true","campuslife.messaging.scheduling-enabled=false"})
class OutboxRabbitIT extends OutboxOrderFixture {
 @Autowired RabbitAdmin admin;@Autowired RabbitTemplate rabbit;@Autowired RabbitOutboxTransport transport;@Autowired CachingConnectionFactory connection;
 private String container,vhost;private final HttpClient management=HttpClient.newHttpClient();private String managementBase,authorization;
 @BeforeAll void verifyFixture()throws Exception{
  assertThat(System.getenv("CAMPUSLIFE_MQ_IT")).isEqualTo("owned-isolated-fixture");container=System.getenv("CAMPUSLIFE_MQ_CONTAINER");String identity=System.getenv("CAMPUSLIFE_MQ_IDENTITY");assertThat(container).matches("[a-f0-9]{64}");assertThat(identity).matches("[a-f0-9]{32}");
  var labels=json.readTree(docker("inspect","--format","{{json .Config.Labels}}",container));assertThat(labels.path("campuslife.rabbit.identity").asString()).isEqualTo(identity);assertThat(labels.path("campuslife.rabbit.kind").asString()).isEqualTo("test");
  vhost=System.getenv("MESSAGING_VHOST");assertThat(vhost).isEqualTo("campuslife_"+identity);managementBase="http://127.0.0.1:"+System.getenv("CAMPUSLIFE_MQ_MANAGEMENT_PORT");authorization="Basic "+Base64.getEncoder().encodeToString((System.getenv("MESSAGING_USERNAME")+":"+System.getenv("MESSAGING_PASSWORD")).getBytes(StandardCharsets.UTF_8));
 }
 @BeforeEach void queue(){admin.initialize();admin.purgeQueue(QUEUE,false);admin.purgeQueue(DEAD_QUEUE,false);}
 @AfterEach void restore()throws Exception{docker("start",container);waitBroker();connection.resetConnection();admin.initialize();policy("DELETE",null);admin.purgeQueue(QUEUE,false);admin.purgeQueue(DEAD_QUEUE,false);}
 private String event(){String id=UUID.randomUUID().toString().replace("-","");messageIds.add(id);var body=new OutboxWriter.Envelope(id,"BOOKING_ACCEPTED",1,"BOOKING","991","2","999",NOW,new OutboxWriter.Payload("test-order","虚构消息店"));String text=json.writeValueAsString(body);String hash;try{hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception ex){throw new IllegalStateException(ex);}outbox.append(id,"BOOKING_ACCEPTED","BOOKING",System.nanoTime(),2,999,text,hash,LocalDateTime.ofInstant(NOW,ZoneOffset.UTC));return id;}
 @Test void publisherConnectionWorksWhenAllFourConsumerChannelsAreOccupied()throws Exception{
  assertThat(rabbit.isUsePublisherConnection()).isTrue();assertThat(admin.getRabbitTemplate()).isSameAs(rabbit);String id=event();var row=publishing.claim();var held=new ArrayList<com.rabbitmq.client.Channel>();
  try{
   for(int n=0;n<4;n++){held.add(connection.createConnection().createChannel(false));}
   // Reproduce the old configuration's bounded checkout starvation, then prove the fix with the same row.
   rabbit.setUsePublisherConnection(false);try{assertThat(transport.publish(row)).isEqualTo(OutboxTransactions.Outcome.CONNECTION_FAILED);}finally{rabbit.setUsePublisherConnection(true);}
   admin.initialize();assertThat(transport.publish(row)).isEqualTo(OutboxTransactions.Outcome.ACK);assertThat(publishing.finish(row,OutboxTransactions.Outcome.ACK)).isTrue();
  }finally{rabbit.setUsePublisherConnection(true);for(var channel:held){channel.close();}}
  var received=rabbit.receive(QUEUE,2000);assertThat(received).isNotNull();assertThat(received.getMessageProperties().getMessageId()).isEqualTo(id);assertThat(outbox.find(id).status()).isEqualTo("SENT");
 }
 @Test void confirmsRouteAndPersistentUtf8EnvelopeWithSameIdentity(){String id=event();assertThat(disabledPublisher.publishOne()).isTrue();assertThat(outbox.find(id).status()).isEqualTo("SENT");var received=rabbit.receive(QUEUE,2000);assertThat(received).isNotNull();assertThat(received.getMessageProperties().getMessageId()).isEqualTo(id);assertThat(received.getMessageProperties().getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);assertThat(new String(received.getBody(),StandardCharsets.UTF_8)).isEqualTo(outbox.find(id).payload()).contains("虚构消息店");}
 @Test void noBindingReturnIsNotMarkedSent(){admin.removeBinding(new Binding(QUEUE,Binding.DestinationType.QUEUE,EXCHANGE,ROUTING,null));String id=event();assertThat(disabledPublisher.publishOne()).isTrue();var row=outbox.find(id);assertThat(row.status()).isEqualTo("READY");assertThat(row.lastErrorCode()).isEqualTo("RETURNED");assertThat(row.publishedAt()).isNull();}
 @Test void missingExchangeIsNotMarkedSent(){admin.deleteExchange(EXCHANGE);String id=event();disabledPublisher.publishOne();var row=outbox.find(id);assertThat(row.status()).isEqualTo("READY");assertThat(row.lastErrorCode()).isIn("NACK","TIMEOUT","CONNECTION_FAILED");}
 @Test void brokerNackForOverflowRemainsRecoverable()throws Exception{
  policy("PUT",Map.of("pattern","^campuslife.notification.v1$","definition",Map.of("max-length",1),"priority",100,"apply-to","queues"));
  waitFor(()->api("GET","/api/queues/"+vhost+"/"+QUEUE,null).path("effective_policy_definition").path("max-length").asInt()==1);
  String first=event();disabledPublisher.publishOne();assertThat(outbox.find(first).status()).isEqualTo("SENT");boolean rejected=false;for(int attempt=0;attempt<100;attempt++){String next=event();disabledPublisher.publishOne();var row=outbox.find(next);if("NACK".equals(row.lastErrorCode())){assertThat(row.status()).isEqualTo("READY");rejected=true;break;}assertThat(row.status()).isEqualTo("SENT");}assertThat(rejected).as("quorum length-limit enforcement may overshoot briefly before nacking").isTrue();
 }
 @Test void brokerDownDoesNotRollBackBusinessAndRecoveryPublishesOriginalIdentity()throws Exception{
  var c=setup("BOOKING");String id=beforeNotify(c),mt=login(c.f().owner());docker("stop","--time","5",container);var accepted=data(notify(c,id,mt));assertThat(accepted.path("status").asString()).isEqualTo("ACCEPTED");var row=jdbc.queryForMap("SELECT message_id,payload FROM message_outbox WHERE aggregate_id=?",id);String message=row.get("message_id").toString();
  long start=System.nanoTime();disabledPublisher.publishOne();assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(9));assertThat(outbox.find(message).status()).isEqualTo("READY");
  docker("start",container);waitBroker();connection.resetConnection();admin.initialize();fixed(NOW.plusSeconds(60));disabledPublisher.publishOne();assertThat(outbox.find(message).status()).isEqualTo("SENT");assertThat(new String(rabbit.receive(QUEUE,3000).getBody(),StandardCharsets.UTF_8)).isEqualTo(row.get("payload"));
 }
 @Test void brokerRestartKeepsConfirmedMessageAndLeaseRecoveryResendsSameIdentity()throws Exception{
  String id=event();var first=publishing.claim();assertThat(transport.publish(first)).isEqualTo(OutboxTransactions.Outcome.ACK); // Simulates confirmed publish followed by process exit before DB finish.
  docker("restart","--time","5",container);waitBroker();connection.resetConnection();admin.initialize();fixed(NOW.plusSeconds(30));var next=publishing.claim();assertThat(next.messageId()).isEqualTo(id);assertThat(transport.publish(next)).isEqualTo(OutboxTransactions.Outcome.ACK);assertThat(publishing.finish(next,OutboxTransactions.Outcome.ACK)).isTrue();assertThat(publishing.finish(first,OutboxTransactions.Outcome.ACK)).isFalse();
  var a=rabbit.receive(QUEUE,3000);var b=rabbit.receive(QUEUE,3000);assertThat(a).isNotNull();assertThat(b).isNotNull();assertThat(a.getBody()).isEqualTo(b.getBody());assertThat(a.getMessageProperties().getMessageId()).isEqualTo(id);assertThat(b.getMessageProperties().getMessageId()).isEqualTo(id);
 }
 @Test void brokerIoCannotBeCalledFromDatabaseTransaction(){String id=event();var row=publishing.claim();assertThatThrownBy(()->new TransactionTemplate(manager).execute(tx->transport.publish(row))).isInstanceOf(IllegalStateException.class).hasMessageContaining("outside");assertThat(rabbit.receive(QUEUE,200)).isNull();}
 @Test void quorumDeadLetterArgumentsAreActuallyApplied()throws Exception{var queue=api("GET","/api/queues/"+vhost+"/"+QUEUE,null);assertThat(queue.path("type").asString()).isEqualTo("quorum");assertThat(queue.path("arguments").path("x-dead-letter-strategy").asString()).isEqualTo("at-least-once");assertThat(queue.path("arguments").path("x-overflow").asString()).isEqualTo("reject-publish");assertThat(queue.path("arguments").path("x-delivery-limit").asInt()).isEqualTo(5);assertThat(api("GET","/api/queues/"+vhost+"/"+DEAD_QUEUE,null).path("type").asString()).isEqualTo("quorum");}
 private void policy(String method,Object body)throws Exception{api(method,"/api/policies/"+vhost+"/outbox-test",body);}
 private tools.jackson.databind.JsonNode api(String method,String path,Object body)throws Exception{var req=HttpRequest.newBuilder(URI.create(managementBase+path)).timeout(Duration.ofSeconds(5)).header("Authorization",authorization);if(body!=null){req.header("Content-Type","application/json");}var res=management.send(req.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));assertThat(res.statusCode()).as(path).isIn(200,201,204,404);return res.body().isBlank()?json.createObjectNode():json.readTree(res.body());}
 private String docker(String...args)throws Exception{var command=new ArrayList<String>();command.add("docker");command.addAll(List.of(args));var p=new ProcessBuilder(command).redirectErrorStream(true).start();assertThat(p.waitFor(45,TimeUnit.SECONDS)).isTrue();String text=new String(p.getInputStream().readAllBytes(),StandardCharsets.UTF_8).strip();assertThat(p.exitValue()).as("owned Docker operation").isZero();return text;}
 private void waitBroker()throws Exception{waitFor(()->{try{return api("GET","/api/overview",null).has("rabbitmq_version");}catch(Exception|AssertionError ex){return false;}});}
 private void waitFor(java.util.concurrent.Callable<Boolean> check)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);while(System.nanoTime()<end){if(check.call()){return;}Thread.sleep(200);}throw new AssertionError("Owned broker condition timed out");}
}
