package com.campuslife;
import com.campuslife.messaging.service.*;
import com.campuslife.coupon.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.core.*;
import static org.assertj.core.api.Assertions.*;
import static com.campuslife.messaging.config.RabbitMessagingConfiguration.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"campuslife.messaging.enabled=true","campuslife.messaging.consumer-enabled=false","campuslife.messaging.scheduling-enabled=false"})
@ActiveProfiles("mysql")
class CouponAsyncRabbitIT extends CouponAsyncFixture {
 @Autowired @Qualifier("couponIssueContainer") SimpleMessageListenerContainer container;
 @Autowired RabbitAdmin admin;@Autowired RabbitTemplate rabbit;@Autowired RabbitOutboxTransport transport;@Autowired OutboxTransactions publishing;
 @BeforeAll void requireOwned(){assertThat(System.getenv("CAMPUSLIFE_MQ_IT")).isEqualTo("owned-isolated-fixture");assertThat(System.getenv("MESSAGING_VHOST")).isEqualTo("campuslife_"+System.getenv("CAMPUSLIFE_MQ_IDENTITY"));}
 @BeforeEach void queues(){admin.initialize();admin.purgeQueue(COUPON_QUEUE,false);admin.purgeQueue(COUPON_DEAD_QUEUE,false);admin.purgeQueue(QUEUE,false);}
 @AfterEach void stop(){container.stop();admin.purgeQueue(COUPON_QUEUE,false);admin.purgeQueue(COUPON_DEAD_QUEUE,false);admin.purgeQueue(QUEUE,false);}
 private void waitReceipt()throws Exception{long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20);while(System.nanoTime()<end){if(receiptCount()==1){return;}Thread.sleep(50);}throw new AssertionError("Coupon command was not committed");}
 @Test void realRouteDuplicatesAndRestartProduceOneCouponWithoutNotification()throws Exception{
  var c=campaign(1);var r=pending(c,account("STUDENT"));var row=publishing.claim();assertThat(row.messageId()).isEqualTo(message(r));assertThat(transport.publish(row)).isEqualTo(OutboxTransactions.Outcome.ACK);publishing.finish(row,OutboxTransactions.Outcome.ACK);
  assertThat(rabbit.receive(QUEUE,200)).isNull();container.start();waitReceipt();container.stop();
  assertThat(transport.publish(row)).isEqualTo(OutboxTransactions.Outcome.ACK);container.start();org.mockito.Mockito.verify(requests,org.mockito.Mockito.timeout(5000).atLeast(2)).lock(r.getId());container.stop();assertThat(issued(c)).isEqualTo(1);assertThat(receiptCount()).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification",Integer.class)).isZero();
 }
 @Test void lateExpiredCommandIsAcknowledgedWithoutIssuance()throws Exception{
  var c=campaign(1);var r=pending(c,account("STUDENT"));at(base.plusSeconds(300));assertThat(issuance.expire(r.getId())).isTrue();var row=publishing.claim();assertThat(transport.publish(row)).isEqualTo(OutboxTransactions.Outcome.ACK);publishing.finish(row,OutboxTransactions.Outcome.ACK);container.start();waitReceipt();assertThat(issued(c)).isZero();assertThat(state(r).getFailureCode()).isEqualTo("PROCESSING_EXPIRED");
 }
 @Test void tamperedMessageGoesOnlyToCouponDeadLetterQueue()throws Exception{
  var c=campaign(1);var r=pending(c,account("STUDENT"));var props=new MessageProperties();props.setMessageId(message(r));props.setContentType("application/json");props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
  rabbit.send(EXCHANGE,COUPON_ROUTING,new Message("{}".getBytes(),props));container.start();var dead=rabbit.receive(COUPON_DEAD_QUEUE,10000);assertThat(dead).isNotNull();assertThat(dead.getMessageProperties().getMessageId()).isEqualTo(message(r));assertThat(issued(c)).isZero();assertThat(receiptCount()).isZero();assertThat(rabbit.receive(DEAD_QUEUE,200)).isNull();
 }
 @Test void notificationConsumerRejectsCouponCommandRatherThanCreatingNotice(){
  var c=campaign(1);var r=pending(c,account("STUDENT"));String id=message(r);
  var delivery=new NotificationDelivery(outbox,sql.getMapper(com.campuslife.messaging.mapper.NotificationMapper.class),new tools.jackson.databind.ObjectMapper(),clock);
  assertThatThrownBy(()->delivery.consume(id,outbox.find(id).payload().getBytes(java.nio.charset.StandardCharsets.UTF_8))).isInstanceOf(NotificationDelivery.InvalidMessage.class);assertThat(issued(c)).isZero();assertThat(receiptCount()).isZero();
 }
}
