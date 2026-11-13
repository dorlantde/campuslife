package com.campuslife;
import com.campuslife.auth.entity.AccountEntity;
import com.campuslife.coupon.entity.ClaimRequestEntity;
import com.campuslife.coupon.mapper.*;
import com.campuslife.coupon.redis.CouponRedisStore;
import com.campuslife.coupon.service.*;
import com.campuslife.coupon.service.CouponRecoveryTransactions.*;
import com.campuslife.messaging.service.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.campuslife.messaging.config.RabbitMessagingConfiguration.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"campuslife.messaging.enabled=true","campuslife.messaging.consumer-enabled=false","campuslife.messaging.scheduling-enabled=false"})
@ActiveProfiles("mysql")
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="CAMPUSLIFE_COUPON_REDIS_IT",matches="owned-isolated-fixture")
class CouponRedisMessagingIT extends CouponAsyncFixture {
 @org.springframework.test.context.bean.override.mockito.MockitoSpyBean CouponIssuance delivered;
 @Autowired CouponRedisStore store;@Autowired CouponRedisRecovery recovery;@Autowired CouponRecoveryTransactions recoveryTx;
 @Autowired @Qualifier("couponIssueContainer") SimpleMessageListenerContainer container;
 @Autowired RabbitAdmin rabbitAdmin;@Autowired RabbitTemplate rabbit;@Autowired OutboxTransactions publishing;@Autowired RabbitOutboxTransport transport;
 @BeforeAll void owned(){assertThat(System.getenv("CAMPUSLIFE_MQ_IT")).isEqualTo("owned-isolated-fixture");}
 @BeforeEach void queues(){rabbitAdmin.initialize();rabbitAdmin.purgeQueue(COUPON_QUEUE,false);rabbitAdmin.purgeQueue(COUPON_DEAD_QUEUE,false);}
 @AfterEach void pause(){container.stop();rabbitAdmin.purgeQueue(COUPON_QUEUE,false);rabbitAdmin.purgeQueue(COUPON_DEAD_QUEUE,false);}
 private ClaimRequestEntity accepted(Campaign c,AccountEntity a,String key){
  jdbc.update("UPDATE coupon_dispatch_gate SET mode='ASYNC' WHERE campaign_id=?",c.row().getId());if(gates.find(c.row().getId()).redisRunId()==null){recovery.rebuild(c.row().getId());}
  var g=gates.find(c.row().getId());String rid=UUID.randomUUID().toString().replace("-","");assertThat(store.reserve(c.row().getId(),g.generation(),g.redisRunId(),a.getId(),key,rid,base).status()).isEqualTo("HELD");
  return tx().execute(s->{accounts.lockById(a.getId());shops.share(c.row().getShopId());var locked=gates.share(c.row().getId());assertThat(locked.generation()).isEqualTo(g.generation());assertThat(locked.phase()).isEqualTo("OPEN");
   var r=new ClaimRequestEntity();r.setStudentId(a.getId());r.setCampaignId(c.row().getId());r.setRequestKey(key);r.setStatus("PENDING");r.setSourceMode("ASYNC");r.setCreatedAt(time(base));r.setAcceptedAt(time(base));r.setDeadline(time(base.plusSeconds(300)));r.setReservationId(rid);r.setAcceptedGeneration(g.generation());r.setSourceCampaignVersion(1L);requests.insert(r);writer.append(r);return r;});
 }
 private void publishAll(){while(true){var row=publishing.claim();if(row==null){return;}assertThat(transport.publish(row)).isEqualTo(OutboxTransactions.Outcome.ACK);publishing.finish(row,OutboxTransactions.Outcome.ACK);}}
 private void waitTerminal(ClaimRequestEntity r)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);while(System.nanoTime()<end){if(!"PENDING".equals(state(r).getStatus())){return;}Thread.sleep(50);}throw new AssertionError("MQ issuance did not commit");}
 private CouponRedisStore.Stats stats(Campaign c){var g=gates.find(c.row().getId());return store.inspect(c.row().getId(),g.generation(),g.redisRunId());}
 @Test void actualReserveOutboxBrokerIssueAndSettlementSurviveDuplicateDelivery()throws Exception{
  var c=campaign(2);var r=accepted(c,account("STUDENT"),"mq");var sent=publishing.claim();assertThat(transport.publish(sent)).isEqualTo(OutboxTransactions.Outcome.ACK);publishing.finish(sent,OutboxTransactions.Outcome.ACK);container.start();waitTerminal(r);recovery.settle(r.getId());assertThat(stats(c)).isEqualTo(new CouponRedisStore.Stats(2,1,0,1));
  assertThat(transport.publish(sent)).isEqualTo(OutboxTransactions.Outcome.ACK);verify(delivered,timeout(5000).atLeast(2)).consume(eq(message(r)),any());container.stop();recovery.settle(r.getId());assertThat(issued(c)).isEqualTo(1);assertThat(receiptCount()).isEqualTo(1);assertThat(stats(c).spent()).isEqualTo(1);
 }
 @Test void timeoutBeforeRealBrokerDeliveryReleasesOnceAndLateDeliveryCannotIssue()throws Exception{
  var c=campaign(1);var r=accepted(c,account("STUDENT"),"expired");at(base.plusSeconds(300));assertThat(issuance.expire(r.getId())).isTrue();recovery.settle(r.getId());publishAll();container.start();long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(receiptCount()==0&&System.nanoTime()<end){Thread.sleep(50);}assertThat(receiptCount()).isEqualTo(1);assertThat(issued(c)).isZero();assertThat(stats(c)).isEqualTo(new CouponRedisStore.Stats(1,1,0,0));
 }
 @Test void rebuiltPendingReservationStillIssuesWhenOldGenerationCommandArrives()throws Exception{
  var c=campaign(1);var r=accepted(c,account("STUDENT"),"old_epoch");long original=gates.find(c.row().getId()).generation();recovery.rebuild(c.row().getId());assertThat(gates.find(c.row().getId()).generation()).isGreaterThan(original);publishAll();container.start();waitTerminal(r);recovery.settle(r.getId());assertThat(state(r).getStatus()).isEqualTo("SUCCEEDED");assertThat(issued(c)).isEqualTo(1);assertThat(stats(c)).isEqualTo(new CouponRedisStore.Stats(1,0,0,1));
 }
}
