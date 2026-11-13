package com.campuslife;
import com.campuslife.messaging.service.*;
import java.time.*;
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
class NotificationRabbitIT extends OutboxOrderFixture {
 @Autowired SimpleMessageListenerContainer container;@Autowired RabbitAdmin admin;@Autowired RabbitTemplate rabbit;@Autowired RabbitOutboxTransport transport;@Autowired NotificationDelivery delivery;
 @BeforeAll void verifyOwned(){assertThat(System.getenv("CAMPUSLIFE_MQ_IT")).isEqualTo("owned-isolated-fixture");assertThat(System.getenv("MESSAGING_VHOST")).isEqualTo("campuslife_"+System.getenv("CAMPUSLIFE_MQ_IDENTITY"));}
 @BeforeEach void queues(){admin.initialize();admin.purgeQueue(QUEUE,false);admin.purgeQueue(DEAD_QUEUE,false);}
 @AfterEach void pause(){container.stop();admin.purgeQueue(QUEUE,false);admin.purgeQueue(DEAD_QUEUE,false);}
 private String event(Case c)throws Exception{String id=beforeNotify(c);data(notify(c,id,login(c.f().owner())));return jdbc.queryForObject("SELECT message_id FROM message_outbox WHERE aggregate_id=?",String.class,id);}
 private void waitNotice(String id)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);while(System.nanoTime()<end){if(jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE source_message_id=?",Integer.class,id)==1){return;}Thread.sleep(100);}throw new AssertionError("Notification was not committed");}
 @ParameterizedTest @ValueSource(strings={"BOOKING","PRINT"}) void realDeliveryAndDuplicateAfterCommitMakeOneNotification(String type)throws Exception{
  var c=setup(type);String id=event(c);var row=publishing.claim();assertThat(row.messageId()).isEqualTo(id);assertThat(transport.publish(row)).isEqualTo(OutboxTransactions.Outcome.ACK);publishing.finish(row,OutboxTransactions.Outcome.ACK);container.start();waitNotice(id);
  assertThat(transport.publish(row)).isEqualTo(OutboxTransactions.Outcome.ACK);Thread.sleep(500);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE source_message_id=?",Integer.class,id)).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM message_consumption WHERE message_id=?",Integer.class,id)).isEqualTo(1);
  assertThat(data(call("GET","/api/v1/notifications",null,c.token(),null)).path("total").asInt()).isEqualTo(1);
 }
 @Test void committedConsumptionBeforeRedeliveryIsAcknowledgedWithoutAnotherEffect()throws Exception{
  var c=setup("BOOKING");String id=event(c);var row=publishing.claim();delivery.consume(id,row.payload().getBytes(java.nio.charset.StandardCharsets.UTF_8));transport.publish(row);publishing.finish(row,OutboxTransactions.Outcome.ACK);container.start();waitNotice(id);Thread.sleep(500);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE source_message_id=?",Integer.class,id)).isEqualTo(1);
 }
 @Test void tamperedPayloadIsDeadLetteredWithoutReceipt()throws Exception{
  var c=setup("PRINT");String id=event(c);var props=new MessageProperties();props.setMessageId(id);props.setContentType("application/json");props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);rabbit.send(EXCHANGE,ROUTING,new Message("{}".getBytes(),props));container.start();var dead=rabbit.receive(DEAD_QUEUE,10000);assertThat(dead).isNotNull();assertThat(dead.getMessageProperties().getMessageId()).isEqualTo(id);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM message_consumption WHERE message_id=?",Integer.class,id)).isZero();
 }
}
