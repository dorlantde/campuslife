package com.campuslife;

import com.campuslife.auth.entity.AccountEntity;
import com.campuslife.auth.mapper.AccountMapper;
import com.campuslife.merchant.entity.*;
import com.campuslife.merchant.mapper.*;
import com.campuslife.order.entity.*;
import com.campuslife.order.mapper.*;
import com.campuslife.order.service.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"campuslife.booking.expiry-enabled=false","campuslife.print.expiry-enabled=false","campuslife.files.cleanup-enabled=false","campuslife.messaging.enabled=false"})
class OutboxMysqlIT extends OutboxOrderFixture {
    @ParameterizedTest @ValueSource(strings={"BOOKING","PRINT"})
    void businessFirstSuccessWritesOneImmutableEnvelopeAndReplayDoesNotAppend(String type)throws Exception{
        var c=setup(type);String id=beforeNotify(c),mt=login(c.f().owner());assertThat(messages(id)).isZero();var first=data(notify(c,id,mt));assertThat(messages(id)).isEqualTo(1);var row=jdbc.queryForMap("SELECT * FROM message_outbox WHERE aggregate_id=?",id);
        assertThat(row.get("status")).isEqualTo("READY");assertThat(row.get("event_type")).isEqualTo(type.equals("BOOKING")?"BOOKING_ACCEPTED":"PRINT_READY_FOR_PICKUP");assertThat(row.get("event_seq").toString()).isEqualTo(first.path("version").asString());
        var body=json.readTree(row.get("payload").toString());assertThat(body.path("recipientId").asString()).isEqualTo(Long.toString(c.student().id()));assertThat(body.path("payload").path("shopName").asString()).isEqualTo("模拟预约店");assertThat(row.get("payload").toString()).doesNotContain("password","token","reason","fileId");
        assertThat(data(notify(c,id,mt))).isEqualTo(first);assertThat(jdbc.queryForMap("SELECT * FROM message_outbox WHERE aggregate_id=?",id)).isEqualTo(row);assertThat(disabledPublisher.publishOne()).isFalse();
    }
    @ParameterizedTest @ValueSource(strings={"BOOKING","PRINT"})
    void outboxSqlFailureRollsBackOrderStageAndAuditEvent(String type)throws Exception{
        var c=setup(type);String id=beforeNotify(c),mt=login(c.f().owner());var before=jdbc.queryForMap("SELECT * FROM service_order WHERE id=?",id);int count=jdbc.queryForObject("SELECT COUNT(*) FROM order_event WHERE order_id=?",Integer.class,id);
        doThrow(new IllegalStateException("injected outbox write failure")).when(outbox).append(anyString(),anyString(),anyString(),anyLong(),anyLong(),anyLong(),anyString(),anyString(),any());
        expect(notify(c,id,mt),500,"INTERNAL_ERROR");assertThat(jdbc.queryForMap("SELECT * FROM service_order WHERE id=?",id)).isEqualTo(before);assertThat(messages(id)).isZero();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_event WHERE order_id=?",Integer.class,id)).isEqualTo(count);
        if(type.equals("PRINT")){assertThat(jdbc.queryForObject("SELECT progress FROM print_detail WHERE order_id=?",String.class,id)).isEqualTo("PRINTING");}
    }
    @ParameterizedTest @ValueSource(strings={"BOOKING","PRINT"})
    void finalSessionExpiryRollsBackNewOutboxAndOrder(String type)throws Exception{
        var c=setup(type);String id=beforeNotify(c),mt=login(c.f().owner());var before=jdbc.queryForMap("SELECT * FROM service_order WHERE id=?",id);var real=sqlSession.getMapper(com.campuslife.messaging.mapper.OutboxMapper.class);
        doAnswer(i->{int n=real.append(i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(3),i.getArgument(4),i.getArgument(5),i.getArgument(6),i.getArgument(7),i.getArgument(8));fixed(NOW.plusSeconds(7200));return n;}).when(outbox).append(anyString(),anyString(),anyString(),anyLong(),anyLong(),anyLong(),anyString(),anyString(),any());
        expect(notify(c,id,mt),401,"SESSION_EXPIRED");assertThat(messages(id)).isZero();assertThat(jdbc.queryForMap("SELECT * FROM service_order WHERE id=?",id)).isEqualTo(before);
    }
    @Test void leaseExpiryReclaimsSameIdentityAndRejectsLateCallback(){
        String id=enqueue();var first=publishing.claim();assertThat(first.messageId()).isEqualTo(id);assertThat(first.attempts()).isEqualTo(1);assertThat(publishing.claim()).isNull();fixed(NOW.plusSeconds(30));var second=publishing.claim();assertThat(second.messageId()).isEqualTo(id);assertThat(second.leaseToken()).isNotEqualTo(first.leaseToken());assertThat(second.payload()).isEqualTo(first.payload());assertThat(publishing.finish(first,com.campuslife.messaging.service.OutboxTransactions.Outcome.ACK)).isFalse();assertThat(publishing.finish(second,com.campuslife.messaging.service.OutboxTransactions.Outcome.ACK)).isTrue();assertThat(outbox.find(id).status()).isEqualTo("SENT");
    }
    @Test void persistedRetryBudgetEndsInDeadAndRestartDoesNotResetIt(){
        String id=enqueue();var time=NOW;for(int n=1;n<=20;n++){var row=publishing.claim();assertThat(row.attempts()).isEqualTo(n);assertThat(publishing.finish(row,com.campuslife.messaging.service.OutboxTransactions.Outcome.TIMEOUT)).isTrue();var saved=outbox.find(id);assertThat(saved.status()).isEqualTo(n==20?"DEAD":"READY");if(n<20){assertThat(publishing.claim()).isNull();time=time.plusSeconds(com.campuslife.messaging.service.OutboxTransactions.backoff(n));fixed(time);}}
        assertThat(publishing.claim()).isNull();assertThat(outbox.find(id).lastErrorCode()).isEqualTo("TIMEOUT");
    }
    @Test void crashedTwentiethLeaseEndsDeadWithoutTwentyFirstSend(){String id=enqueue();jdbc.update("UPDATE message_outbox SET attempts=19,last_attempt_at=created_at WHERE message_id=?",id);var row=publishing.claim();assertThat(row.attempts()).isEqualTo(20);fixed(NOW.plusSeconds(30));assertThat(publishing.claim()).isNull();assertThat(outbox.find(id).status()).isEqualTo("DEAD");assertThat(outbox.find(id).lastErrorCode()).isEqualTo("LEASE_EXHAUSTED");}
    @Test void skipLockedAllowsAnotherWorkerToClaimDifferentRow()throws Exception{
        String a=enqueue(),b=enqueue();var ready=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var holder=pool.submit(()->new TransactionTemplate(manager).executeWithoutResult(tx->{jdbc.queryForList("SELECT * FROM message_outbox WHERE message_id=? FOR UPDATE",a);ready.countDown();await(release);}));assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();var next=pool.submit(publishing::claim).get(3,TimeUnit.SECONDS);assertThat(next.messageId()).isEqualTo(b);release.countDown();holder.get(5,TimeUnit.SECONDS);
        }finally{release.countDown();}
    }
}
