package com.campuslife;

import com.campuslife.messaging.model.OutboxMessage;
import com.campuslife.messaging.service.*;
import com.campuslife.order.service.PrintExpiryJob;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.campuslife.messaging.config.RabbitMessagingConfiguration.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"campuslife.messaging.enabled=true","campuslife.messaging.scheduling-enabled=false","campuslife.messaging.consumer-enabled=false","campuslife.coupon.expiry-enabled=false","campuslife.booking.expiry-enabled=false","campuslife.print.expiry-enabled=false","campuslife.files.cleanup-enabled=false"})
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="CAMPUSLIFE_MQ_IT",matches="owned-isolated-fixture")
class OutboxBatchIT extends OutboxOrderFixture {
    @MockitoSpyBean RabbitOutboxTransport sender;
    @Autowired org.springframework.amqp.rabbit.core.RabbitAdmin admin;
    @Autowired TaskScheduler scheduler;
    @Autowired PrintExpiryJob printExpiry;
    final List<OutboxPublisher> owned = new ArrayList<>();

    @BeforeAll void ownedBroker() throws Exception {
        String id=System.getenv("CAMPUSLIFE_MQ_CONTAINER");assertThat(id).matches("[a-f0-9]{64}");
        var p=new ProcessBuilder("docker","inspect","--format","{{json .Config.Labels}}",id).redirectErrorStream(true).start();
        assertThat(p.waitFor(10,TimeUnit.SECONDS)).isTrue();assertThat(p.exitValue()).isZero();
        var labels=json.readTree(new String(p.getInputStream().readAllBytes(),StandardCharsets.UTF_8));
        assertThat(labels.path("campuslife.rabbit.identity").asString()).isEqualTo(System.getenv("CAMPUSLIFE_MQ_IDENTITY"));
        assertThat(labels.path("campuslife.rabbit.kind").asString()).isEqualTo("test");
    }
    @BeforeEach void queues(){admin.initialize();admin.purgeQueue(QUEUE,false);admin.purgeQueue(COUPON_QUEUE,false);}
    @AfterEach void stopPublishers(){for(var p:owned){p.stop();}owned.clear();reset(sender);admin.purgeQueue(QUEUE,false);admin.purgeQueue(COUPON_QUEUE,false);}
    OutboxPublisher publisher(int size){
        var beans=new StaticListableBeanFactory();beans.addBean("transport",sender);
        var p=new OutboxPublisher(publishing,beans.getBeanProvider(RabbitOutboxTransport.class),true,true,size);owned.add(p);return p;
    }
    void waitFor(Callable<Boolean> check)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);while(System.nanoTime()<end){if(check.call()){return;}Thread.sleep(10);}throw new AssertionError("Batch boundary timed out");}
    void idle(OutboxPublisher p)throws Exception{waitFor(()->!((java.util.concurrent.atomic.AtomicBoolean)ReflectionTestUtils.getField(p,"roundRunning")).get());}
    int count(List<String> ids,String predicate){return jdbc.queryForObject("SELECT COUNT(*) FROM message_outbox WHERE message_id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+") AND "+predicate,Integer.class,ids.toArray());}
    List<String> rows(int size){var ids=new ArrayList<String>();for(int n=0;n<size;n++){ids.add(enqueue());}return ids;}

    @Test void healthyRoundIsBoundedAndFollowingTicksDrainWithoutChangingMessageIdentity()throws Exception{
        var ids=rows(40);var p=publisher(32);p.scheduled();idle(p);
        assertThat(count(ids,"status='SENT'")).isBetween(1,32);
        assertThat(count(ids,"status='PUBLISHING'")).isZero();
        waitFor(()->{p.scheduled();return count(ids,"status='SENT'")==40;});idle(p);
        assertThat(count(ids,"status='SENT' AND attempts=1")).isEqualTo(40);
        assertThat(admin.getQueueInfo(QUEUE).getMessageCount()).isEqualTo(40);
    }

    @Test void notificationAndCouponRoutesShareTheBoundedWorker()throws Exception{
        var ids=new ArrayList<String>();String[] types={"BOOKING_ACCEPTED","PRINT_READY_FOR_PICKUP","COUPON_ISSUE_REQUESTED"};
        for(int n=0;n<6;n++){
            String id=UUID.randomUUID().toString().replace("-","");messageIds.add(id);ids.add(id);String type=types[n%3];
            String aggregate=type.startsWith("BOOKING")?"BOOKING":type.startsWith("PRINT")?"PRINT":"COUPON_CLAIM";
            outbox.append(id,type,aggregate,System.nanoTime(),type.startsWith("COUPON")?0:2,999,"{}","a".repeat(64),LocalDateTime.ofInstant(NOW,ZoneOffset.UTC));
        }
        var p=publisher(32);waitFor(()->{p.scheduled();return count(ids,"status='SENT'")==6;});idle(p);
        assertThat(admin.getQueueInfo(QUEUE).getMessageCount()).isEqualTo(4);
        assertThat(admin.getQueueInfo(COUPON_QUEUE).getMessageCount()).isEqualTo(2);
        assertThat(count(ids,"attempts=1")).isEqualTo(6);
    }

    @ParameterizedTest @ValueSource(strings={"RETURNED","NACK","TIMEOUT","CONNECTION_FAILED","SHUTDOWN"})
    void firstFailurePersistsOriginalBackoffAndLeavesOtherRowsUnclaimed(String type)throws Exception{
        var ids=rows(3);var p=publisher(32);doReturn(OutboxTransactions.Outcome.valueOf(type)).when(sender).publish(any());
        p.scheduled();idle(p);
        assertThat(count(ids,"attempts=1 AND status='READY'")).isEqualTo(1);
        assertThat(count(ids,"attempts=0 AND status='READY'")).isEqualTo(2);
        var failed=jdbc.queryForMap("SELECT next_attempt_at,last_error_code FROM message_outbox WHERE attempts=1");
        assertThat(failed.get("next_attempt_at").toString()).startsWith("2026-10-22");
        assertThat(failed.get("last_error_code")).isEqualTo(type);
        assertThat(jdbc.queryForObject("SELECT next_attempt_at=DATE_ADD(last_attempt_at,INTERVAL 1 SECOND) FROM message_outbox WHERE attempts=1",Boolean.class)).isTrue();
    }

    @Test void ackWithAReclaimedLeaseCannotFinishOldOwnerOrContinueWave()throws Exception{
        var ids=rows(3);var replacement=new AtomicReference<OutboxMessage>();var p=publisher(32);
        doAnswer(i->{var old=(OutboxMessage)i.getArgument(0);var ack=(OutboxTransactions.Outcome)i.callRealMethod();fixed(NOW.plusSeconds(30));var fresh=publishing.claim();assertThat(fresh.messageId()).isEqualTo(old.messageId());assertThat(fresh.leaseToken()).isNotEqualTo(old.leaseToken());replacement.set(fresh);return ack;}).when(sender).publish(any());
        p.scheduled();idle(p);
        assertThat(replacement.get()).isNotNull();assertThat(count(ids,"status='SENT'")).isZero();
        assertThat(count(ids,"attempts=0 AND status='READY'")).isEqualTo(2);
        assertThat(outbox.find(replacement.get().messageId()).leaseToken()).isEqualTo(replacement.get().leaseToken());
        assertThat(outbox.find(replacement.get().messageId()).attempts()).isEqualTo(2);
    }

    @Test void sqlFailureAfterAckRollsBackFinishAndDoesNotRecycleOrPrefetch()throws Exception{
        var ids=rows(3);var p=publisher(32);var real=sqlSession.getMapper(com.campuslife.messaging.mapper.OutboxMapper.class);
        doAnswer(i->{real.finish(i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(3),i.getArgument(4),i.getArgument(5));throw new IllegalStateException("injected finish rollback after SQL");}).when(outbox).finish(anyString(),anyString(),anyString(),nullable(LocalDateTime.class),nullable(LocalDateTime.class),nullable(String.class));
        p.scheduled();idle(p);
        assertThat(count(ids,"status='PUBLISHING' AND attempts=1")).isEqualTo(1);
        assertThat(count(ids,"status='READY' AND attempts=0")).isEqualTo(2);
        assertThat(admin.getQueueInfo(QUEUE).getMessageCount()).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings={"BOOKING","PRINT"})
    void blockedBrokerIoDoesNotReserveTheBatchOrBlockRealOrderExpiryOnSharedScheduler(String type)throws Exception{
        var c=setup(type);String order=id(create(c,"expiry-isolation"));var ids=rows(3);var p=publisher(32);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var expired=new CountDownLatch(1);var failure=new AtomicReference<Throwable>();
        assertThat(scheduler).isInstanceOf(ThreadPoolTaskScheduler.class);assertThat(((ThreadPoolTaskScheduler)scheduler).getPoolSize()).isEqualTo(1);
        doAnswer(i->{assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();entered.countDown();assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();return i.callRealMethod();}).when(sender).publish(any());
        try{
            scheduler.schedule(p::scheduled,Instant.now());assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
            for(int n=0;n<20;n++){scheduler.schedule(p::scheduled,Instant.now());}
            assertThat(count(ids,"status='PUBLISHING' AND attempts=1")).isEqualTo(1);assertThat(count(ids,"status='READY' AND attempts=0")).isEqualTo(2);
            fixed(NOW.plusSeconds(900));scheduler.schedule(()->{try{assertThat(type.equals("BOOKING")?expiry.scan():printExpiry.scan()).isEqualTo(1);}catch(Throwable ex){failure.set(ex);}finally{expired.countDown();}},Instant.now());
            assertThat(expired.await(4,TimeUnit.SECONDS)).as("Shared single scheduler must remain available during publisher IO").isTrue();assertThat(failure.get()).isNull();
            assertThat(jdbc.queryForObject("SELECT status FROM service_order WHERE id=?",String.class,order)).isEqualTo("CLOSED");
            assertThat(count(ids,"status='PUBLISHING' AND attempts=1")).isEqualTo(1);assertThat(count(ids,"attempts=0")).isEqualTo(2);
        }finally{release.countDown();}
        waitFor(()->{p.scheduled();return count(ids,"status='SENT'")==3;});idle(p);
        assertThat(count(ids,"attempts=1")).isEqualTo(3);
    }
}
