package com.campuslife;

import com.campuslife.auth.security.*;
import com.campuslife.coupon.dto.CouponClaim;
import com.campuslife.coupon.entity.ClaimRequestEntity;
import com.campuslife.coupon.redis.CouponRedisStore;
import com.campuslife.coupon.service.*;
import com.campuslife.messaging.service.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.campuslife.messaging.config.RabbitMessagingConfiguration.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"campuslife.messaging.enabled=true","campuslife.messaging.scheduling-enabled=false","campuslife.messaging.consumer-enabled=true","campuslife.coupon-redis.scheduling-enabled=false","campuslife.coupon.expiry-enabled=false"})
@ActiveProfiles("mysql")
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="CAMPUSLIFE_COUPON_REDIS_IT",matches="owned-isolated-fixture")
class CouponOutboxBatchIT extends CouponAsyncFixture {
    @Autowired CouponAdmissionService admission;@Autowired CouponMaintenance maintenance;@Autowired CouponRedisRecovery recovery;
    @Autowired CouponRedisStore store;@Autowired ObjectMapper json;@Autowired OutboxTransactions publishing;
    @Autowired org.springframework.amqp.rabbit.core.RabbitAdmin admin;
    @MockitoSpyBean RabbitOutboxTransport sender;
    final List<OutboxPublisher> publishers=new ArrayList<>();
    @BeforeAll void owned(){assertThat(System.getenv("CAMPUSLIFE_MQ_IT")).isEqualTo("owned-isolated-fixture");assertThat(System.getenv("DB_NAME")).isEqualTo("campuslife_it");}
    @AfterEach void stop(){for(var p:publishers){p.stop();}publishers.clear();reset(sender);}
    Campaign opened(int stock){var c=campaign(stock);action(c,"Pause");action(c,"Async");action(c,"Resume");return c;}
    void action(Campaign c,String action){maintenance.run(action,c.row().getId(),maintenance.run("Inspect",c.row().getId(),null,false).controlVersion(),true);}
    ClaimRequestEntity admit(Campaign c,String key){var p=principal(account("STUDENT"));var body=new CouponClaim(json.valueToTree(c.row().getId().toString()));var r=admission.claim(p,key,body);assertThat(r.result().status()).isEqualTo("PENDING");return requests.selectById(Long.parseLong(r.result().id()));}
    OutboxPublisher publisher(){var f=new StaticListableBeanFactory();f.addBean("sender",sender);var p=new OutboxPublisher(publishing,f.getBeanProvider(RabbitOutboxTransport.class),true,true,32);publishers.add(p);return p;}
    void waitFor(Callable<Boolean> condition)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(System.nanoTime()<until){if(condition.call()){return;}Thread.sleep(10);}throw new AssertionError("Coupon batch did not finish");}
    void idle(OutboxPublisher p)throws Exception{waitFor(()->!((java.util.concurrent.atomic.AtomicBoolean)ReflectionTestUtils.getField(p,"roundRunning")).get());}
    boolean finished(List<ClaimRequestEntity> rs){return rs.stream().allMatch(r->state(r).getStatus().equals("SUCCEEDED")&&outbox.find(message(r)).status().equals("SENT"));}
    void assertFacts(Campaign c,List<ClaimRequestEntity> rs)throws Exception{
        waitFor(()->finished(rs));assertThat(issued(c)).isEqualTo(rs.size());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_coupon WHERE campaign_id=?",Integer.class,c.row().getId())).isEqualTo(rs.size());
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT student_id) FROM user_coupon WHERE campaign_id=?",Integer.class,c.row().getId())).isEqualTo(rs.size());
        assertThat(receiptCount()).isEqualTo(rs.size());
        for(var r:rs){recovery.settle(r.getId());assertThat(state(r).getDeadline()).isEqualTo(r.getDeadline());}
        var g=gates.find(c.row().getId());assertThat(store.inspect(c.row().getId(),g.generation(),g.redisRunId())).isEqualTo(new CouponRedisStore.Stats(rs.size(),0,0,rs.size()));
        assertThat(admin.getQueueInfo(COUPON_DEAD_QUEUE).getMessageCount()).isZero();
    }

    @Test void boundedPublisherFeedsRealCouponConsumerAndSettlement()throws Exception{
        var c=opened(20);var rs=new ArrayList<ClaimRequestEntity>();for(int n=0;n<20;n++){rs.add(admit(c,"batch_"+n));}
        var p=publisher();waitFor(()->{if(finished(rs)){return true;}p.scheduled();return finished(rs);});idle(p);assertFacts(c,rs);
        for(var r:rs){assertThat(outbox.find(message(r)).attempts()).isEqualTo(1);}
    }

    @Test void twoPublisherInstancesClaimSeparatelyAndConsumersApplyEachMessageOnce()throws Exception{
        var c=opened(20);var rs=new ArrayList<ClaimRequestEntity>();for(int n=0;n<20;n++){rs.add(admit(c,"two_"+n));}
        var a=publisher();var b=publisher();waitFor(()->{if(finished(rs)){return true;}a.scheduled();b.scheduled();return finished(rs);});idle(a);idle(b);assertFacts(c,rs);
        for(var r:rs){assertThat(outbox.find(message(r)).attempts()).isEqualTo(1);}
    }

    @Test void confirmedButUnknownReplyRetainsLeaseAndRedeliveryCannotIssueAgain()throws Exception{
        var c=opened(1);var r=admit(c,"unknown");String id=message(r);var p=publisher();
        doAnswer(i->{assertThat(i.callRealMethod()).isEqualTo(OutboxTransactions.Outcome.ACK);throw new IllegalStateException("injected lost result after actual confirm");}).when(sender).publish(any());
        p.scheduled();idle(p);waitFor(()->state(r).getStatus().equals("SUCCEEDED"));
        assertThat(outbox.find(id).status()).isEqualTo("PUBLISHING");assertThat(outbox.find(id).attempts()).isEqualTo(1);
        reset(sender);at(base.plusSeconds(30));waitFor(()->{if(outbox.find(id).status().equals("SENT")){return true;}p.scheduled();return outbox.find(id).status().equals("SENT");});idle(p);
        assertThat(message(r)).isEqualTo(id);assertThat(outbox.find(id).attempts()).isEqualTo(2);assertFacts(c,List.of(r));
    }
}
