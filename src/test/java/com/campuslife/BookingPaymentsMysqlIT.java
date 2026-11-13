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

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="campuslife.booking.expiry-enabled=false")
@ActiveProfiles("mysql")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BookingPaymentsMysqlIT {
    private static final Instant NOW=Instant.parse("2026-10-22T01:00:00Z");
    private static final LocalDate DATE=LocalDate.of(2026,10,23);
    private static final Instant START=Instant.parse("2026-10-23T01:30:00Z");
    private final HttpClient http=HttpClient.newHttpClient();
    private final List<Long> accountIds=new ArrayList<>(),shopIds=new ArrayList<>();
    private String hash;
    @Autowired AccountMapper accounts;
    @MockitoSpyBean ShopMapper shops;
    @Autowired ServiceItemMapper services;
    @Autowired BarberMapper barbers;
    @Autowired BarberServiceMapper bindings;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired Environment env;
    @Autowired PasswordEncoder passwords;
    @Autowired BookingExpiryJob expiry;
    @Autowired BookingOrderTransactions transactions;
    @Autowired PlatformTransactionManager manager;
    @Autowired org.mybatis.spring.SqlSessionTemplate sqlSession;
    @MockitoSpyBean Clock clock;
    @MockitoSpyBean OrderMapper orders;
    @MockitoSpyBean BookingMapper bookings;
    @MockitoSpyBean OrderEventMapper events;
    @MockitoSpyBean IdempotencyMapper keys;
    @MockitoSpyBean BarberScheduleMapper schedules;
    @MockitoSpyBean MoneyMapper money;
    @MockitoSpyBean MockPaymentPolicy mockPayment;
    @BeforeAll void prepare() { hash=passwords.encode("Booking_Test123!"); }
    @BeforeEach void freeze() { fixed(NOW); }
    @AfterEach void cleanup() {
        reset(clock,orders,bookings,events,keys,schedules,shops,money,mockPayment);
        for (long shop:shopIds) {
            jdbc.update("DELETE r FROM refund_record r JOIN service_order o ON o.id=r.order_id WHERE o.shop_id=?",shop);
            jdbc.update("DELETE p FROM payment_record p JOIN service_order o ON o.id=p.order_id WHERE o.shop_id=?",shop);
            jdbc.update("DELETE k FROM idempotency_record k JOIN service_order o ON o.id=k.order_id WHERE o.shop_id=?",shop);
            jdbc.update("DELETE e FROM order_event e JOIN service_order o ON o.id=e.order_id WHERE o.shop_id=?",shop);
            jdbc.update("DELETE b FROM booking_detail b JOIN service_order o ON o.id=b.order_id WHERE o.shop_id=?",shop);
            jdbc.update("DELETE FROM service_order WHERE shop_id=?",shop);
            jdbc.update("DELETE s FROM barber_schedule s JOIN barber b ON b.id=s.barber_id WHERE b.shop_id=?",shop);
            jdbc.update("DELETE g FROM barber_day_guard g JOIN barber b ON b.id=g.barber_id WHERE b.shop_id=?",shop);
            jdbc.update("DELETE x FROM barber_service x JOIN barber b ON b.id=x.barber_id WHERE b.shop_id=?",shop);
            jdbc.update("DELETE FROM barber WHERE shop_id=?",shop);jdbc.update("DELETE FROM service_item WHERE shop_id=?",shop);
            jdbc.update("DELETE FROM shop_business_hour WHERE shop_id=?",shop);jdbc.update("DELETE FROM shop_member WHERE shop_id=?",shop);jdbc.update("DELETE FROM shop WHERE id=?",shop);
        }
        for (long id:accountIds) { jdbc.update("DELETE FROM auth_session WHERE account_id=?",id);jdbc.update("DELETE FROM account WHERE id=?",id); }
        accountIds.clear();shopIds.clear();
    }

    private void enable() { doReturn(true).when(mockPayment).enabled(); }
    private String newOrder(Fixture f,String token) throws Exception { return data(create(f,token,"pay-first")).path("id").asString(); }
    private HttpResponse<String> pay(String id,String token) throws Exception { return call("POST","/api/v1/orders/"+id+"/payments/mock",null,token,null); }
    private HttpResponse<String> cancel(String id,String token) throws Exception { return call("POST","/api/v1/orders/"+id+"/cancel",null,token,null); }
    private JsonNode detail(String id,String token) throws Exception { return data(call("GET","/api/v1/orders/"+id,null,token,null)); }
    @Test void defaultDisabledBlocksAllNewMoneyButAllowsUnpaidRelease() throws Exception {
        assertThat(mockPayment.enabled()).isFalse();
        var f=fixture();var token=login(user("STUDENT"));var id=newOrder(f,token);
        expect(pay(id,token),503,"MOCK_PAYMENT_DISABLED");assertThat(count("payment_record")).isZero();
        assertThat(detail(id,token).path("version").asString()).isEqualTo("0");
        assertThat(cancel(id,token).statusCode()).isEqualTo(200);assertThat(count("refund_record")).isZero();
    }
    @Test void successfulPaymentReplayAndRefundPreserveOriginalFactsAndRelease() throws Exception {
        enable();var f=fixture();var token=login(user("STUDENT"));var id=newOrder(f,token);
        var first=data(pay(id,token));assertThat(first.path("amount").asLong()).isEqualTo(3000);
        assertThat(first.path("paidAt").asString()).isEqualTo(NOW.toString());
        assertThat(first.path("acceptanceDeadline").asString()).isEqualTo(NOW.plusSeconds(1800).toString());
        fixed(NOW.plusSeconds(600));assertThat(data(pay(id,token))).isEqualTo(first);
        var view=detail(id,token);assertThat(view.path("booking").path("occupancyStatus").asString()).isEqualTo("ACTIVE");assertThat(view.path("version").asString()).isEqualTo("1");assertThat(starts(f)).doesNotContain(START);
        var closed=data(cancel(id,token));assertThat(closed.path("paymentStatus").asString()).isEqualTo("REFUNDED");
        assertThat(closed.path("payment").path("paymentNo").asString()).isEqualTo(first.path("paymentNo").asString());
        assertThat(closed.path("refund").path("amount").asLong()).isEqualTo(3000);assertThat(closed.path("version").asString()).isEqualTo("2");
        assertThat(closed.path("booking").path("occupancyStatus").asString()).isEqualTo("RELEASED");assertThat(starts(f)).contains(START);
        doReturn(false).when(mockPayment).enabled();assertThat(data(cancel(id,token))).isEqualTo(closed);expect(pay(id,token),409,"ORDER_STATE_CONFLICT");
        assertThat(count("payment_record")).isEqualTo(1);assertThat(count("refund_record")).isEqualTo(1);assertThat(count("order_event")).isEqualTo(3);
    }
    @ParameterizedTest @ValueSource(longs={899999,900000,900001})
    void paymentDeadlineIsStrictAndTimeoutCommitsBefore409(long elapsed) throws Exception {
        enable();var f=fixture();var token=login(user("STUDENT"));var id=newOrder(f,token);fixed(NOW.plusMillis(elapsed));
        if(elapsed<900000){assertThat(pay(id,token).statusCode()).isEqualTo(200);assertThat(count("payment_record")).isEqualTo(1);}
        else {expect(pay(id,token),409,"PAYMENT_DEADLINE_PASSED");assertThat(detail(id,token).path("closeReason").asString()).isEqualTo("PAYMENT_TIMEOUT");assertThat(count("payment_record")).isZero();assertThat(starts(f)).contains(START);expect(pay(id,token),409,"PAYMENT_DEADLINE_PASSED");}
    }
    @ParameterizedTest @ValueSource(longs={1799999,1800000,1800001})
    void acceptanceDeadlineIsStrictAndRefundCommitsBefore409(long elapsed) throws Exception {
        enable();var f=fixture();var token=login(user("STUDENT"));var id=newOrder(f,token);data(pay(id,token));fixed(NOW.plusMillis(elapsed));
        assertThat(data(pay(id,token)).path("acceptanceDeadline").asString()).isEqualTo(NOW.plusSeconds(1800).toString());
        if(elapsed<1800000){assertThat(cancel(id,token).statusCode()).isEqualTo(200);}
        else {expect(cancel(id,token),409,"ACCEPTANCE_DEADLINE_PASSED");expect(cancel(id,token),409,"ACCEPTANCE_DEADLINE_PASSED");}
        assertThat(count("refund_record")).isEqualTo(1);assertThat(detail(id,token).path("closeReason").asString()).isEqualTo(elapsed<1800000?"USER_CANCEL":"ACCEPTANCE_TIMEOUT");assertThat(count("order_event")).isEqualTo(3);
    }
    @Test void switchOffKeepsPaidOccupancyAndReplaysAndRestoresFromOriginalDeadline() throws Exception {
        enable();var f=fixture();var token=login(user("STUDENT"));var id=newOrder(f,token);var first=data(pay(id,token));
        doReturn(false).when(mockPayment).enabled();expect(cancel(id,token),503,"MOCK_PAYMENT_DISABLED");assertThat(data(pay(id,token))).isEqualTo(first);
        var other=addBarber(f);var otherToken=login(user("STUDENT"));create(other,otherToken,"unpaid");fixed(NOW.plusSeconds(1800));
        assertThat(expiry.scan()).isEqualTo(1);assertThat(count("refund_record")).isZero();assertThat(detail(id,token).path("status").asString()).isEqualTo("PENDING_ACCEPTANCE");assertThat(starts(f)).doesNotContain(START);
        expect(create(f,otherToken,"blocked"),409,"BOOKING_CONFLICT");assertThat(data(pay(id,token))).isEqualTo(first);
        enable();assertThat(new BookingExpiryJob(orders,transactions,clock,true).scan()).isEqualTo(1);assertThat(expiry.scan()).isZero();assertThat(detail(id,token).path("closeReason").asString()).isEqualTo("ACCEPTANCE_TIMEOUT");assertThat(starts(f)).contains(START);
    }
    @Test void creationCleansExpiredPaidOverlapAndRollsBackCleanupIfNewCreationFails() throws Exception {
        enable();var f=fixture();var token=login(user("STUDENT"));var id=newOrder(f,token);data(pay(id,token));fixed(NOW.plusSeconds(1800));var other=login(user("STUDENT"));
        doThrow(new IllegalStateException("injected new creation")).when(keys).save(anyLong(),eq("after-paid"),anyString(),anyLong(),any());
        expect(create(f,other,"after-paid"),500,"INTERNAL_ERROR");assertThat(count("refund_record")).isZero();assertThat(detail(id,token).path("status").asString()).isEqualTo("PENDING_ACCEPTANCE");
        reset(keys);assertThat(create(f,other,"after-paid").statusCode()).isEqualTo(201);assertThat(count("refund_record")).isEqualTo(1);assertThat(detail(id,token).path("closeReason").asString()).isEqualTo("ACCEPTANCE_TIMEOUT");
    }
    @ParameterizedTest @ValueSource(strings={"shop","owner","member"})
    void missingOperatingQualificationBlocksFirstPaymentButNotUnpaidCancel(String mode) throws Exception {
        enable();var f=fixture();var token=login(user("STUDENT"));var id=newOrder(f,token);
        switch(mode){case "shop"->jdbc.update("UPDATE shop SET approval_status='REJECTED' WHERE id=?",f.shop());case "owner"->jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?",f.owner().id());default->jdbc.update("UPDATE shop_member SET status='DISABLED' WHERE shop_id=?",f.shop());}
        expect(pay(id,token),409,"SHOP_UNAVAILABLE");assertThat(count("payment_record")).isZero();assertThat(cancel(id,token).statusCode()).isEqualTo(200);
    }
    @Test void frozenQuoteSurvivesManualClosureAndServiceChanges() throws Exception {
        enable();var f=fixture();var token=login(user("STUDENT"));var id=newOrder(f,token);
        jdbc.update("UPDATE shop SET business_enabled=FALSE WHERE id=?",f.shop());jdbc.update("UPDATE service_item SET enabled=FALSE,price_amount=8000,duration_minutes=60,version=1 WHERE id=?",f.service());
        assertThat(data(pay(id,token)).path("amount").asLong()).isEqualTo(3000);assertThat(detail(id,token).path("booking").path("durationMinutes").asInt()).isEqualTo(30);
    }
    @ParameterizedTest @ValueSource(strings={"amount","empty","null"})
    void businessBodyIsRejectedWithoutPayment(String mode) throws Exception {
        enable();var f=fixture();var token=login(user("STUDENT"));var id=newOrder(f,token);
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+env.getRequiredProperty("local.server.port")+"/api/v1/orders/"+id+"/payments/mock")).header("Authorization","Bearer "+token).header("Content-Type","application/json")
          .POST(HttpRequest.BodyPublishers.ofString(mode.equals("amount")?"{\"amount\":1}":mode.equals("empty")?"{}":"null")).build();
        expect(http.send(request,HttpResponse.BodyHandlers.ofString()),400,"VALIDATION_FAILED");assertThat(count("payment_record")).isZero();
    }
    @Test void authenticationRoleOwnershipAndIdsAreEnforced() throws Exception {
        enable();var f=fixture();var token=login(user("STUDENT"));var id=newOrder(f,token);
        expect(pay(id,null),401,"AUTH_REQUIRED");expect(pay(id,login(f.owner())),403,"ROLE_FORBIDDEN");expect(pay(id,login(user("ADMIN"))),403,"ROLE_FORBIDDEN");expect(pay(id,login(user("STUDENT"))),404,"RESOURCE_NOT_FOUND");expect(pay("9223372036854775807",token),404,"RESOURCE_NOT_FOUND");expect(pay("01",token),400,"VALIDATION_FAILED");assertThat(count("payment_record")).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"payment","order","occupancy","event"})
    void paymentWriteFailuresRollbackEverything(String step) throws Exception {
        enable();var f=fixture();var token=login(user("STUDENT"));var id=newOrder(f,token);
        switch(step){case "payment"->doThrow(new IllegalStateException("injected")).when(money).pay(anyLong(),anyString(),anyLong(),any());case "order"->doThrow(new IllegalStateException("injected")).when(orders).pay(anyLong(),anyLong(),any(),any());case "occupancy"->doThrow(new IllegalStateException("injected")).when(bookings).activate(anyLong());default->doThrow(new IllegalStateException("injected")).when(events).append(anyLong(),eq(1L),eq("PAYMENT"),anyString(),anyString(),anyString(),anyLong(),isNull(),any());}
        expect(pay(id,token),500,"INTERNAL_ERROR");assertThat(count("payment_record")).isZero();assertThat(count("order_event")).isEqualTo(1);var d=detail(id,token);assertThat(d.path("status").asString()).isEqualTo("PENDING_PAYMENT");assertThat(d.path("booking").path("occupancyStatus").asString()).isEqualTo("HELD");
    }
    @ParameterizedTest @ValueSource(strings={"refund","order","occupancy","event"})
    void refundWriteFailuresRollbackEverythingAndScannerRetries(String step) throws Exception {
        enable();var f=fixture();var token=login(user("STUDENT"));var id=newOrder(f,token);data(pay(id,token));fixed(NOW.plusSeconds(1800));
        switch(step){case "refund"->doThrow(new IllegalStateException("injected")).when(money).refund(anyLong(),anyLong(),anyString(),anyLong(),anyString(),any());case "order"->doThrow(new IllegalStateException("injected")).when(orders).refundClose(anyLong(),anyLong(),anyString(),any());case "occupancy"->doThrow(new IllegalStateException("injected")).when(bookings).releaseActive(anyLong());default->doThrow(new IllegalStateException("injected")).when(events).append(anyLong(),eq(2L),anyString(),anyString(),anyString(),anyString(),isNull(),anyString(),any());}
        assertThat(expiry.scan()).isZero();assertThat(count("refund_record")).isZero();assertThat(detail(id,token).path("booking").path("occupancyStatus").asString()).isEqualTo("ACTIVE");assertThat(count("order_event")).isEqualTo(2);
        reset(money,orders,bookings,events);assertThat(expiry.scan()).isEqualTo(1);assertThat(expiry.scan()).isZero();assertThat(count("refund_record")).isEqualTo(1);
    }
    @Test void concurrentRepeatedPaymentsProduceOneSuccessfulFact() throws Exception {
        enable();var f=fixture();var token=login(user("STUDENT"));var id=newOrder(f,token);var gate=new CountDownLatch(1);var results=new ArrayList<Future<HttpResponse<String>>>();
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) { for(int i=0;i<12;i++){results.add(pool.submit(()->{gate.await();return pay(id,token);}));}gate.countDown();JsonNode fact=null;for(var result:results){var d=data(result.get(20,TimeUnit.SECONDS));if(fact==null){fact=d;}else{assertThat(d).isEqualTo(fact);}} }
        assertThat(count("payment_record")).isEqualTo(1);assertThat(count("order_event")).isEqualTo(2);
    }
    @Test void concurrentPayCancelAndExpiryConvergeWithoutPartialFacts() throws Exception {
        enable();var f=fixture();var token=login(user("STUDENT"));var id=newOrder(f,token);var gate=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {var p=pool.submit(()->{gate.await();return pay(id,token);});var c=pool.submit(()->{gate.await();return cancel(id,token);});var e=pool.submit(()->{gate.await();return transactions.expire(Long.parseLong(id));});gate.countDown();assertThat(p.get(20,TimeUnit.SECONDS).statusCode()).isIn(200,409);assertThat(c.get(20,TimeUnit.SECONDS).statusCode()).isEqualTo(200);assertThat(e.get(20,TimeUnit.SECONDS)).isFalse();}
        assertThat(detail(id,token).path("status").asString()).isEqualTo("CLOSED");assertThat(count("refund_record")).isEqualTo(count("payment_record"));assertThat(count("order_event")).isEqualTo(count("payment_record")==1?3:2);
    }
    @Test void concurrentRefundCancelScanAndNewCreationCloseExactlyOnce() throws Exception {
        enable();var f=fixture();var token=login(user("STUDENT"));var other=login(user("STUDENT"));var id=newOrder(f,token);data(pay(id,token));fixed(NOW.plusSeconds(1800));var gate=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {var c=pool.submit(()->{gate.await();return cancel(id,token);});var e=pool.submit(()->{gate.await();return expiry.scan();});var n=pool.submit(()->{gate.await();return create(f,other,"new");});gate.countDown();expect(c.get(20,TimeUnit.SECONDS),409,"ACCEPTANCE_DEADLINE_PASSED");assertThat(e.get(20,TimeUnit.SECONDS)).isIn(0,1);assertThat(n.get(20,TimeUnit.SECONDS).statusCode()).isEqualTo(201);}
        assertThat(count("refund_record")).isEqualTo(1);assertThat(count("payment_record")).isEqualTo(1);assertThat(detail(id,token).path("version").asString()).isEqualTo("2");assertThat(count("order_event")).isEqualTo(4);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void waitingForDateLockUsesFreshStateAndTime(boolean paid) throws Exception {
        enable();var f=fixture();var token=login(user("STUDENT"));var id=newOrder(f,token);if(paid){data(pay(id,token));}
        var held=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var blocker=pool.submit(()->new TransactionTemplate(manager).executeWithoutResult(tx->{schedules.lockGuard(f.barber(),DATE);held.countDown();await(release);}));assertThat(held.await(10,TimeUnit.SECONDS)).isTrue();
            var waiting=pool.submit(()->paid?cancel(id,token):pay(id,token));Thread.sleep(250);assertThat(waiting.isDone()).isFalse();fixed(NOW.plusSeconds(paid?1800:900));release.countDown();blocker.get(10,TimeUnit.SECONDS);
            expect(waiting.get(10,TimeUnit.SECONDS),409,paid?"ACCEPTANCE_DEADLINE_PASSED":"PAYMENT_DEADLINE_PASSED");assertThat(detail(id,token).path("status").asString()).isEqualTo("CLOSED");assertThat(count("refund_record")).isEqualTo(paid?1:0);
        } finally {release.countDown();}
    }
    @Test void paymentTimeDoesNotMoveDuringWritesButExpiredSessionRollsBack() throws Exception {
        enable();var f=fixture();var token=login(user("STUDENT"));var id=newOrder(f,token);fixed(NOW.plusMillis(899999));var real=sqlSession.getMapper(MoneyMapper.class);
        doAnswer(i->{int changed=real.pay(i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(3));fixed(NOW.plusSeconds(901));return changed;}).when(money).pay(anyLong(),anyString(),anyLong(),any());
        assertThat(data(pay(id,token)).path("paidAt").asString()).isEqualTo(NOW.plusMillis(899999).toString());
        reset(money);var other=addBarber(f);var id2=data(create(other,token,"session")).path("id").asString();
        doAnswer(i->{int changed=real.pay(i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(3));fixed(NOW.plusSeconds(7200));return changed;}).when(money).pay(anyLong(),anyString(),anyLong(),any());
        expect(pay(id2,token),401,"SESSION_EXPIRED");assertThat(count("payment_record")).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT status FROM service_order WHERE id=?",String.class,id2)).isEqualTo("PENDING_PAYMENT");
    }
    @Test void mysqlUniqueAndPositiveAmountConstraintsProtectMoneyFacts() throws Exception {
        enable();var f=fixture();var token=login(user("STUDENT"));var id=newOrder(f,token);data(pay(id,token));
        assertSqlFailure(()->jdbc.update("INSERT INTO payment_record(order_id,payment_no,amount,channel,status,paid_at) SELECT order_id,'duplicate',amount,channel,status,paid_at FROM payment_record"),"uk_payment_order",1062);
        assertSqlFailure(()->jdbc.update("UPDATE payment_record SET amount=0"),"ck_payment_amount",3819);data(cancel(id,token));
        assertSqlFailure(()->jdbc.update("INSERT INTO refund_record(order_id,payment_id,refund_no,amount,reason,status,refunded_at) SELECT order_id,payment_id,'duplicate',amount,reason,status,refunded_at FROM refund_record"),"uk_refund_order",1062);
        assertSqlFailure(()->jdbc.update("UPDATE refund_record SET amount=0"),"ck_refund_amount",3819);
    }
    @Test void detailUsesOneRepeatableReadSnapshotWhileRefundCommits() throws Exception {
        enable();var f=fixture();var token=login(user("STUDENT"));var id=newOrder(f,token);data(pay(id,token));
        var reached=new CountDownLatch(1);var release=new CountDownLatch(1);var real=sqlSession.getMapper(MoneyMapper.class);var once=new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(i->{var p=real.payment(i.getArgument(0));if(once.compareAndSet(true,false)){reached.countDown();await(release);}return p;}).when(money).payment(Long.parseLong(id));
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var reading=pool.submit(()->detail(id,token));assertThat(reached.await(10,TimeUnit.SECONDS)).isTrue();data(cancel(id,token));release.countDown();var old=reading.get(10,TimeUnit.SECONDS);
            assertThat(old.path("status").asString()).isEqualTo("PENDING_ACCEPTANCE");assertThat(old.path("booking").path("occupancyStatus").asString()).isEqualTo("ACTIVE");assertThat(old.path("refund").isNull()).isTrue();
            assertThat(detail(id,token).path("paymentStatus").asString()).isEqualTo("REFUNDED");
        } finally {release.countDown();}
    }
    @Test void paymentReplayRechecksStateAfterConcurrentRefundWhileWaiting() throws Exception {
        enable();var f=fixture();var token=login(user("STUDENT"));var id=newOrder(f,token);data(pay(id,token));fixed(NOW.plusSeconds(1800));
        var reached=new CountDownLatch(1);var release=new CountDownLatch(1);var real=sqlSession.getMapper(OrderMapper.class);var once=new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(i->{var s=real.shareShop(i.getArgument(0));if(once.compareAndSet(true,false)){reached.countDown();await(release);}return s;}).when(orders).shareShop(f.shop());
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var paying=pool.submit(()->pay(id,token));assertThat(reached.await(10,TimeUnit.SECONDS)).isTrue();assertThat(transactions.expire(Long.parseLong(id))).isTrue();release.countDown();expect(paying.get(10,TimeUnit.SECONDS),409,"ORDER_STATE_CONFLICT");assertThat(count("refund_record")).isEqualTo(1);
        } finally {release.countDown();}
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void versionExhaustionCannotLeaveNewMoney(boolean paid) throws Exception {
        enable();var f=fixture();var token=login(user("STUDENT"));var id=newOrder(f,token);if(paid){data(pay(id,token));}
        jdbc.update("UPDATE service_order SET version=? WHERE id=?",Long.MAX_VALUE,id);
        expect(paid?cancel(id,token):pay(id,token),409,"ORDER_STATE_CONFLICT");assertThat(count("payment_record")).isEqualTo(paid?1:0);assertThat(count("refund_record")).isZero();
    }
    @Test void acceptedStateCanReplayPaymentButNewCancellationRefundStillNeedsMoneyGate() throws Exception {
        enable();var f=fixture();var token=login(user("STUDENT"));var id=newOrder(f,token);var first=data(pay(id,token));
        jdbc.update("UPDATE service_order SET status='ACCEPTED' WHERE id=?",id);doReturn(false).when(mockPayment).enabled();
        var replay=data(pay(id,token));assertThat(replay.path("orderStatus").asString()).isEqualTo("ACCEPTED");assertThat(replay.path("paymentNo")).isEqualTo(first.path("paymentNo"));expect(cancel(id,token),503,"MOCK_PAYMENT_DISABLED");assertThat(count("refund_record")).isZero();
    }
    @Test void realMysqlPaymentLockTimeoutReturns503AndCanRetrySafely() throws Exception {
        enable();var f=fixture();var token=login(user("STUDENT"));var id=newOrder(f,token);var reached=new CountDownLatch(1);var release=new CountDownLatch(1);var real=sqlSession.getMapper(OrderMapper.class);
        doAnswer(i->{jdbc.execute("SET SESSION innodb_lock_wait_timeout=1");try{return real.shareShop(i.getArgument(0));}finally{jdbc.execute("SET SESSION innodb_lock_wait_timeout=50");}}).when(orders).shareShop(f.shop());
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var holder=pool.submit(()->new TransactionTemplate(manager).executeWithoutResult(tx->{shops.lock(f.shop());reached.countDown();await(release);}));assertThat(reached.await(10,TimeUnit.SECONDS)).isTrue();
            expect(pay(id,token),503,"SERVICE_TEMPORARILY_UNAVAILABLE");assertThat(count("payment_record")).isZero();release.countDown();holder.get(10,TimeUnit.SECONDS);reset(orders);assertThat(pay(id,token).statusCode()).isEqualTo(200);
        } finally {release.countDown();}
    }
    @Test void simultaneousUnpaidExpiryAndPaymentAtDeadlineNeverGenerateMoney() throws Exception {
        enable();var f=fixture();var token=login(user("STUDENT"));var id=newOrder(f,token);fixed(NOW.plusSeconds(900));var gate=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {var p=pool.submit(()->{gate.await();return pay(id,token);});var e=pool.submit(()->{gate.await();return transactions.expire(Long.parseLong(id));});gate.countDown();expect(p.get(10,TimeUnit.SECONDS),409,"PAYMENT_DEADLINE_PASSED");e.get(10,TimeUnit.SECONDS);}
        assertThat(count("payment_record")).isZero();assertThat(count("order_event")).isEqualTo(2);assertThat(detail(id,token).path("closeReason").asString()).isEqualTo("PAYMENT_TIMEOUT");
    }
    private record User(long id,String name) {}
    private record Fixture(long shop,long service,long barber,User owner) {}
    private User user(String role){var a=new AccountEntity();a.setUsername("book_"+UUID.randomUUID().toString().replace("-","").substring(0,22));a.setNickname("模拟学生");a.setPasswordHash(hash);a.setRole(role);a.setStatus("ACTIVE");a.setVersion(0L);accounts.insert(a);accountIds.add(a.getId());return new User(a.getId(),a.getUsername());}
    private Fixture fixture(){var owner=user("MERCHANT");var s=new ShopEntity();s.setName("模拟预约店");s.setCategoryId(jdbc.queryForObject("SELECT id FROM shop_category WHERE code='HAIRCUT'",Long.class));s.setCampusCode("XINGHAI_MAIN");s.setLocationText("虚构校区");s.setDescription("");s.setApprovalStatus("APPROVED");s.setBusinessEnabled(true);s.setVersion(0L);shops.insert(s);shopIds.add(s.getId());jdbc.update("INSERT INTO shop_member(shop_id,account_id,status) VALUES (?,?,'ACTIVE')",s.getId(),owner.id());var service=new ServiceItemEntity();service.setShopId(s.getId());service.setServiceType("HAIRCUT");service.setName("模拟理发");service.setDescription("");service.setPriceAmount(3000L);service.setDurationMinutes(30);service.setEnabled(true);service.setVersion(0L);services.insert(service);jdbc.update("INSERT INTO shop_business_hour(shop_id,weekday,start_time,end_time) VALUES (?,?,?,?)",s.getId(),DATE.getDayOfWeek().getValue(),"09:00","12:00");return addBarber(new Fixture(s.getId(),service.getId(),0,owner));}
    private Fixture addBarber(Fixture f){var b=new BarberEntity();b.setShopId(f.shop());b.setDisplayName("模拟人员");b.setEnabled(true);b.setVersion(0L);barbers.insert(b);bindings.insert(b.getId(),f.service());schedules.insertGuard(b.getId(),DATE);schedules.insert(b.getId(),DATE,LocalDateTime.of(2026,10,23,1,0),LocalDateTime.of(2026,10,23,4,0));return new Fixture(f.shop(),f.service(),b.getId(),f.owner());}
    private ObjectNode body(Fixture f,Instant start){return json.valueToTree(Map.of("serviceId",Long.toString(f.service()),"barberId",Long.toString(f.barber()),"startAt",start.toString(),"expectedServiceVersion","0","expectedPayableAmount",3000));}
    private HttpResponse<String> create(Fixture f,String token,String key)throws Exception{return post(body(f,START),token,key);}
    private HttpResponse<String> post(Object body,String token,String key)throws Exception{return call("POST","/api/v1/orders/bookings",body,token,key);}
    private String login(User user)throws Exception{return data(call("POST","/api/v1/auth/login",Map.of("username",user.name(),"password","Booking_Test123!"),null,null)).path("accessToken").asString();}
    private List<Instant> starts(Fixture f)throws Exception{var result=data(call("GET","/api/v1/shops/"+f.shop()+"/booking-slots?serviceId="+f.service()+"&barberId="+f.barber()+"&date="+DATE,null,null,null));var list=new ArrayList<Instant>();for(var item:result.path("slots")){list.add(Instant.parse(item.path("startAt").asString()));}return list;}
    private int count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
    private void assertSqlFailure(Runnable statement,String constraint,int code) {
        var failure=catchThrowable(statement::run);assertThat(failure).isInstanceOf(org.springframework.dao.DataAccessException.class).hasMessageContaining(constraint);
        var cause=((org.springframework.dao.DataAccessException)failure).getMostSpecificCause();assertThat(cause).isInstanceOf(java.sql.SQLException.class);
        assertThat(((java.sql.SQLException)cause).getErrorCode()).isEqualTo(code);
    }
    private void fixed(Instant value){doReturn(value).when(clock).instant();}
    private JsonNode data(HttpResponse<String> response){assertThat(response.statusCode()).as(response.body()).isIn(200,201);return json.readTree(response.body()).path("data");}
    private void expect(HttpResponse<String> response,int status,String code){assertThat(response.statusCode()).as(response.body()).isEqualTo(status);assertThat(json.readTree(response.body()).path("code").asString()).isEqualTo(code);}
    private HttpResponse<String> call(String method,String path,Object body,String token,String key)throws Exception{var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+env.getRequiredProperty("local.server.port")+path)).timeout(Duration.ofSeconds(40));if(token!=null){builder.header("Authorization","Bearer "+token);}if(key!=null){builder.header("Idempotency-Key",key);}if(body!=null){builder.header("Content-Type","application/json");}builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));return http.send(builder.build(),HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));}
    private static void await(CountDownLatch latch){try{if(!latch.await(10,TimeUnit.SECONDS)){throw new IllegalStateException("gate timeout");}}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IllegalStateException(ex);}}
}
