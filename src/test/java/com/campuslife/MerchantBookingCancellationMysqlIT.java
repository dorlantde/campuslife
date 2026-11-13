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
class MerchantBookingCancellationMysqlIT {
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
    private String paid(Fixture f,String token)throws Exception {enable();var id=data(create(f,token,"order")).path("id").asString();data(call("POST","/api/v1/orders/"+id+"/payments/mock",null,token,null));return id;}
    private String accepted(Fixture f,String student,String merchant)throws Exception {var id=paid(f,student);data(call("POST","/api/v1/merchant/orders/"+id+"/accept",null,merchant,null));return id;}
    private HttpResponse<String> cancel(String id,String token,String reason)throws Exception {return call("POST","/api/v1/merchant/orders/"+id+"/cancel",Map.of("reason",reason),token,null);}
    private HttpResponse<String> studentCancel(String id,String token)throws Exception {return call("POST","/api/v1/orders/"+id+"/cancel",null,token,null);}
    private JsonNode detail(String id,String token)throws Exception {return data(call("GET","/api/v1/merchant/orders/"+id,null,token,null));}
    @Test void cancelsAtomicallyPreservesPaymentAndAcceptanceAndStoresResponsibility()throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var merchant=login(f.owner());var id=accepted(f,student,merchant);var before=detail(id,merchant);fixed(NOW.plusSeconds(60));
        var closed=data(cancel(id,merchant,"  人员无法到岗  "));assertThat(closed.path("status").asString()).isEqualTo("CLOSED");assertThat(closed.path("paymentStatus").asString()).isEqualTo("REFUNDED");assertThat(closed.path("booking").path("occupancyStatus").asString()).isEqualTo("RELEASED");assertThat(closed.path("closeReason").asString()).isEqualTo("MERCHANT_UNAVAILABLE");assertThat(closed.path("closeNote").asString()).isEqualTo("人员无法到岗");assertThat(closed.path("closedAt").asString()).isEqualTo(NOW.plusSeconds(60).toString());assertThat(closed.path("version").asString()).isEqualTo("3");
        assertThat(closed.path("payment")).isEqualTo(before.path("payment"));assertThat(closed.path("acceptedAt")).isEqualTo(before.path("acceptedAt"));assertThat(closed.path("acceptanceDeadline")).isEqualTo(before.path("acceptanceDeadline"));assertThat(closed.path("refund").path("amount").asLong()).isEqualTo(3000);assertThat(closed.path("payableAmount").asLong()).isEqualTo(3000);
        assertThat(jdbc.queryForObject("SELECT reason_note FROM refund_record WHERE order_id=?",String.class,id)).isEqualTo("人员无法到岗");assertThat(jdbc.queryForObject("SELECT reason FROM refund_record WHERE order_id=?",String.class,id)).isEqualTo("MERCHANT_UNAVAILABLE");
        var event=jdbc.queryForMap("SELECT action,from_status,to_status,actor_type,actor_id,reason FROM order_event WHERE order_id=? AND event_seq=3",id);assertThat(event.get("action")).isEqualTo("MERCHANT_UNAVAILABLE");assertThat(event.get("from_status")).isEqualTo("ACCEPTED");assertThat(event.get("to_status")).isEqualTo("CLOSED");assertThat(event.get("actor_type")).isEqualTo("MERCHANT");assertThat(((Number)event.get("actor_id")).longValue()).isEqualTo(f.owner().id());assertThat(event.get("reason")).isEqualTo("人员无法到岗");
        assertThat(data(call("GET","/api/v1/orders/"+id,null,student,null))).isEqualTo(closed);assertThat(starts(f)).contains(START);assertThat(count("payment_record")).isEqualTo(1);assertThat(count("refund_record")).isEqualTo(1);assertThat(count("order_event")).isEqualTo(4);assertThat(jdbc.queryForObject("SELECT version FROM shop WHERE id=?",Long.class,f.shop())).isZero();assertThat(jdbc.queryForObject("SELECT version FROM barber WHERE id=?",Long.class,f.barber())).isZero();
    }
    @Test void sameReasonReplaySurvivesWindowExpiryAndDisabledMoneyButDifferentReasonCannotOverwrite()throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var merchant=login(f.owner());var id=accepted(f,student,merchant);var first=data(cancel(id,merchant,"人员原因"));fixed(START.plusSeconds(60));merchant=login(f.owner());doReturn(false).when(mockPayment).enabled();
        assertThat(data(cancel(id,merchant,"  人员原因 "))).isEqualTo(first);expect(cancel(id,merchant,"其他原因"),409,"ORDER_STATE_CONFLICT");assertThat(count("refund_record")).isEqualTo(1);assertThat(count("order_event")).isEqualTo(4);
        expect(call("POST","/api/v1/merchant/orders/"+id+"/accept",null,merchant,null),409,"ORDER_STATE_CONFLICT");expect(call("POST","/api/v1/merchant/orders/"+id+"/reject",Map.of("reason","人员原因"),merchant,null),409,"ORDER_STATE_CONFLICT");expect(studentCancel(id,login(user("STUDENT"))),404,"RESOURCE_NOT_FOUND");
    }
    @ParameterizedTest @ValueSource(longs={-1,0,1})
    void startBoundaryIsStrictAndExpiredWindowHasNoSideEffects(long offset)throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var merchant=login(f.owner());var id=accepted(f,student,merchant);fixed(START.plusMillis(offset));merchant=login(f.owner());
        if(offset<0){assertThat(cancel(id,merchant,"临时无法履约").statusCode()).isEqualTo(200);assertThat(count("refund_record")).isEqualTo(1);}
        else {expect(cancel(id,merchant,"临时无法履约"),409,"CANCEL_WINDOW_CLOSED");var unchanged=detail(id,merchant);assertThat(unchanged.path("status").asString()).isEqualTo("ACCEPTED");assertThat(unchanged.path("version").asString()).isEqualTo("2");assertThat(unchanged.path("closedAt").isNull()).isTrue();assertThat(count("refund_record")).isZero();assertThat(count("order_event")).isEqualTo(3);}
    }
    @Test void merchantCanCancelInsideStudentOneHourRestrictionAndAfterOldAcceptanceDeadline()throws Exception {
        var f=fixture();var studentUser=user("STUDENT");var student=login(studentUser);var merchant=login(f.owner());var id=accepted(f,student,merchant);fixed(START.minusSeconds(30));merchant=login(f.owner());student=login(studentUser);
        expect(studentCancel(id,student),409,"CANCEL_WINDOW_CLOSED");assertThat(expiry.scan()).isZero();assertThat(cancel(id,merchant,"无法按约服务").statusCode()).isEqualTo(200);assertThat(count("refund_record")).isEqualTo(1);
    }
    @Test void authenticationRoleOwnershipAndInactiveAccountsAreEnforced()throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var merchant=login(f.owner());var id=accepted(f,student,merchant);var other=fixture();
        expect(cancel(id,null,"原因"),401,"AUTH_REQUIRED");expect(cancel(id,student,"原因"),403,"ROLE_FORBIDDEN");expect(cancel(id,login(user("ADMIN")),"原因"),403,"ROLE_FORBIDDEN");expect(cancel(id,login(other.owner()),"原因"),404,"RESOURCE_NOT_FOUND");expect(cancel("01",merchant,"原因"),400,"VALIDATION_FAILED");expect(cancel("9223372036854775807",merchant,"原因"),404,"RESOURCE_NOT_FOUND");
        jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?",f.owner().id());expect(cancel(id,merchant,"原因"),403,"ACCOUNT_DISABLED");assertThat(count("refund_record")).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"PENDING","DISABLED"})
    void inactiveOwnerCannotCancelEvenAnExistingRefundReplay(String state)throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var merchant=login(f.owner());var id=accepted(f,student,merchant);data(cancel(id,merchant,"原因"));jdbc.update("UPDATE shop_member SET status=? WHERE shop_id=?",state,f.shop());expect(cancel(id,merchant,"原因"),403,"ROLE_FORBIDDEN");assertThat(count("refund_record")).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"SUSPENDED","PENDING","REJECTED"})
    void missingApprovalDoesNotPreventOldOrderResponsibility(String state)throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var merchant=login(f.owner());var id=accepted(f,student,merchant);jdbc.update("UPDATE shop SET approval_status=? WHERE id=?",state,f.shop());assertThat(cancel(id,merchant,"原因").statusCode()).isEqualTo(200);
    }
    @Test void manualClosureAndChangedConfigurationCannotRepriceRefund()throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var merchant=login(f.owner());var id=accepted(f,student,merchant);jdbc.update("UPDATE shop SET business_enabled=FALSE WHERE id=?",f.shop());jdbc.update("UPDATE service_item SET enabled=FALSE,price_amount=9000,duration_minutes=60 WHERE id=?",f.service());jdbc.update("UPDATE shop_category SET enabled=FALSE WHERE code='HAIRCUT'");
        try {var d=data(cancel(id,merchant,"原因"));assertThat(d.path("refund").path("amount").asLong()).isEqualTo(3000);assertThat(d.path("booking").path("durationMinutes").asInt()).isEqualTo(30);}
        finally {jdbc.update("UPDATE shop_category SET enabled=TRUE WHERE code='HAIRCUT'");}
    }
    @ParameterizedTest @ValueSource(strings={"missing","null","blank","number","array","unknown","long","unicode"})
    void reasonUsesStrictUnicodeValidation(String mode)throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var merchant=login(f.owner());var id=accepted(f,student,merchant);
        Object body=switch(mode){case "missing"->Map.of();case "null"->Collections.singletonMap("reason",null);case "blank"->Map.of("reason"," \t ");case "number"->Map.of("reason",1);case "array"->List.of("原因");case "unknown"->Map.of("reason","原因","amount",1);case "long"->Map.of("reason","字".repeat(301));default->Map.of("reason","😀".repeat(300));};var result=call("POST","/api/v1/merchant/orders/"+id+"/cancel",body,merchant,null);
        if(mode.equals("unicode")){assertThat(result.statusCode()).as(result.body()).isEqualTo(200);}else{expect(result,400,"VALIDATION_FAILED");assertThat(count("refund_record")).isZero();assertThat(count("order_event")).isEqualTo(3);}
    }
    @ParameterizedTest @ValueSource(strings={"PENDING_PAYMENT","PENDING_ACCEPTANCE","IN_SERVICE","COMPLETED","CLOSED","PRINT"})
    void invalidStatesOrTypesAreRejectedBeforeMoneyGate(String state)throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var merchant=login(f.owner());var id=accepted(f,student,merchant);if(state.equals("PRINT")){jdbc.update("UPDATE service_order SET order_type='PRINT' WHERE id=?",id);}else{jdbc.update("UPDATE service_order SET status=? WHERE id=?",state,id);}doReturn(false).when(mockPayment).enabled();expect(cancel(id,merchant,"原因"),state.equals("PRINT")?404:409,state.equals("PRINT")?"RESOURCE_NOT_FOUND":"ORDER_STATE_CONFLICT");assertThat(count("refund_record")).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"student","reject","timeout"})
    void otherClosureReasonsCannotBeRewritten(String reason)throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var merchant=login(f.owner());var id=paid(f,student);
        switch(reason){case "student"->data(studentCancel(id,student));case "reject"->data(call("POST","/api/v1/merchant/orders/"+id+"/reject",Map.of("reason","原原因"),merchant,null));default->{fixed(NOW.plusSeconds(1800));assertThat(expiry.scan()).isEqualTo(1);}}
        var original=detail(id,merchant);expect(cancel(id,merchant,"新原因"),409,"ORDER_STATE_CONFLICT");assertThat(detail(id,merchant)).isEqualTo(original);assertThat(count("refund_record")).isEqualTo(1);assertThat(count("order_event")).isEqualTo(3);
    }
    @Test void moneyDisabledBlocksNewRefundWithoutReleasingAndDoesNotMaskExpiredWindow()throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var merchant=login(f.owner());var id=accepted(f,student,merchant);doReturn(false).when(mockPayment).enabled();expect(cancel(id,merchant,"原因"),503,"MOCK_PAYMENT_DISABLED");assertThat(detail(id,merchant).path("status").asString()).isEqualTo("ACCEPTED");assertThat(starts(f)).doesNotContain(START);assertThat(count("refund_record")).isZero();fixed(START);merchant=login(f.owner());expect(cancel(id,merchant,"原因"),409,"CANCEL_WINDOW_CLOSED");assertThat(count("order_event")).isEqualTo(3);
    }
    @Test void studentCannotChangeMerchantUnavailableReasonAfterSuccessfulMerchantCancellation()throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var merchant=login(f.owner());var id=accepted(f,student,merchant);var first=data(cancel(id,merchant,"商家原因"));expect(studentCancel(id,student),409,"ORDER_STATE_CONFLICT");assertThat(detail(id,merchant)).isEqualTo(first);assertThat(count("refund_record")).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void concurrentSameOrDifferentReasonsHaveOneRefundAndImmutableClosure(boolean different)throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var tokens=List.of(login(f.owner()),login(f.owner()));var id=accepted(f,student,tokens.getFirst());var gate=new CountDownLatch(1);var results=new ArrayList<Future<HttpResponse<String>>>();
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){for(int i=0;i<10;i++){int n=i;results.add(pool.submit(()->{gate.await();return cancel(id,tokens.get(n%2),different&&n%2==1?"原因乙":"原因甲");}));}gate.countDown();int ok=0,conflict=0;JsonNode fact=null;for(var item:results){var response=item.get(20,TimeUnit.SECONDS);if(response.statusCode()==200){ok++;var d=data(response);if(fact==null){fact=d;}else{assertThat(d).isEqualTo(fact);}}else{expect(response,409,"ORDER_STATE_CONFLICT");conflict++;}}assertThat(ok).isEqualTo(different?5:10);assertThat(conflict).isEqualTo(different?5:0);}
        assertThat(count("refund_record")).isEqualTo(1);assertThat(count("order_event")).isEqualTo(4);assertThat(detail(id,tokens.getFirst()).path("version").asString()).isEqualTo("3");
    }
    @Test void concurrentStudentAndMerchantCancellationConvergeOnOneResponsibility()throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var merchant=login(f.owner());var id=accepted(f,student,merchant);var gate=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){var a=pool.submit(()->{gate.await();return cancel(id,merchant,"商家原因");});var b=pool.submit(()->{gate.await();return studentCancel(id,student);});gate.countDown();var x=a.get(20,TimeUnit.SECONDS);var y=b.get(20,TimeUnit.SECONDS);assertThat(List.of(x.statusCode(),y.statusCode())).containsExactlyInAnyOrder(200,409);var d=detail(id,merchant);assertThat(d.path("closeReason").asString()).isEqualTo(x.statusCode()==200?"MERCHANT_UNAVAILABLE":"USER_CANCEL");}
        assertThat(count("refund_record")).isEqualTo(1);assertThat(count("order_event")).isEqualTo(4);
    }
    @Test void cancellationRechecksFreshOrderAfterWaitingForConcurrentStudentClosure()throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var merchant=login(f.owner());var id=accepted(f,student,merchant);var reached=new CountDownLatch(1);var release=new CountDownLatch(1);var once=new java.util.concurrent.atomic.AtomicBoolean(true);var real=sqlSession.getMapper(OrderMapper.class);
        doAnswer(i->{var shop=real.shareShop(i.getArgument(0));if(once.compareAndSet(true,false)){reached.countDown();await(release);}return shop;}).when(orders).shareShop(f.shop());
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){var cancelling=pool.submit(()->cancel(id,merchant,"商家原因"));assertThat(reached.await(10,TimeUnit.SECONDS)).isTrue();data(studentCancel(id,student));release.countDown();expect(cancelling.get(10,TimeUnit.SECONDS),409,"ORDER_STATE_CONFLICT");assertThat(detail(id,merchant).path("closeReason").asString()).isEqualTo("USER_CANCEL");assertThat(count("refund_record")).isEqualTo(1);}
        finally {release.countDown();}
    }
    @Test void newOverlappingCreationWaitsForCommittedRefundRelease()throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var other=login(user("STUDENT"));var merchant=login(f.owner());var id=accepted(f,student,merchant);expect(create(f,other,"before"),409,"BOOKING_CONFLICT");var reached=new CountDownLatch(1);var release=new CountDownLatch(1);var real=sqlSession.getMapper(OrderMapper.class);
        doAnswer(i->{int n=real.refundCloseFrom(i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(3),i.getArgument(4),i.getArgument(5));reached.countDown();await(release);return n;}).when(orders).refundCloseFrom(anyLong(),anyLong(),eq("ACCEPTED"),eq("MERCHANT_UNAVAILABLE"),anyString(),any());
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){var cancelling=pool.submit(()->cancel(id,merchant,"原因"));assertThat(reached.await(10,TimeUnit.SECONDS)).isTrue();assertThat(starts(f)).doesNotContain(START);var creating=pool.submit(()->create(f,other,"after"));Thread.sleep(200);assertThat(creating.isDone()).isFalse();release.countDown();assertThat(cancelling.get(10,TimeUnit.SECONDS).statusCode()).isEqualTo(200);assertThat(creating.get(10,TimeUnit.SECONDS).statusCode()).isEqualTo(201);assertThat(count("refund_record")).isEqualTo(1);}
        finally {release.countDown();}
    }
    @Test void realDateLockWaitAcrossStartBoundaryDoesNotRefund()throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var merchant=login(f.owner());var id=accepted(f,student,merchant);fixed(START.minusMillis(1));merchant=login(f.owner());var token=merchant;var reached=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){var holder=pool.submit(()->new TransactionTemplate(manager).executeWithoutResult(tx->{schedules.lockGuard(f.barber(),DATE);reached.countDown();await(release);}));assertThat(reached.await(10,TimeUnit.SECONDS)).isTrue();var cancelling=pool.submit(()->cancel(id,token,"原因"));Thread.sleep(200);assertThat(cancelling.isDone()).isFalse();fixed(START);release.countDown();holder.get(10,TimeUnit.SECONDS);expect(cancelling.get(10,TimeUnit.SECONDS),409,"CANCEL_WINDOW_CLOSED");assertThat(count("refund_record")).isZero();assertThat(detail(id,token).path("version").asString()).isEqualTo("2");}
        finally {release.countDown();}
    }
    @Test void ownerIsRecheckedAfterWaitingForShopLock()throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var merchant=login(f.owner());var id=accepted(f,student,merchant);var reached=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){var holder=pool.submit(()->new TransactionTemplate(manager).executeWithoutResult(tx->{shops.lock(f.shop());jdbc.update("UPDATE shop_member SET status='DISABLED' WHERE shop_id=?",f.shop());reached.countDown();await(release);}));assertThat(reached.await(10,TimeUnit.SECONDS)).isTrue();var cancelling=pool.submit(()->cancel(id,merchant,"原因"));Thread.sleep(200);assertThat(cancelling.isDone()).isFalse();release.countDown();holder.get(10,TimeUnit.SECONDS);expect(cancelling.get(10,TimeUnit.SECONDS),403,"ROLE_FORBIDDEN");assertThat(count("refund_record")).isZero();}
        finally {release.countDown();}
    }
    @ParameterizedTest @ValueSource(strings={"refund","close","release","event"})
    void anyPartialFailureRollsBackMoneyClosureAndOccupancy(String stage)throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var merchant=login(f.owner());var id=accepted(f,student,merchant);
        switch(stage){case "refund"->doThrow(new IllegalStateException("injected")).when(money).refundWithNote(anyLong(),anyLong(),anyString(),anyLong(),anyString(),anyString(),any());case "close"->doThrow(new IllegalStateException("injected")).when(orders).refundCloseFrom(anyLong(),anyLong(),anyString(),anyString(),anyString(),any());case "release"->doThrow(new IllegalStateException("injected")).when(bookings).releaseActive(anyLong());default->doThrow(new IllegalStateException("injected")).when(events).append(anyLong(),eq(3L),eq("MERCHANT_UNAVAILABLE"),anyString(),anyString(),anyString(),anyLong(),anyString(),any());}
        expect(cancel(id,merchant,"原因"),500,"INTERNAL_ERROR");var d=detail(id,merchant);assertThat(d.path("status").asString()).isEqualTo("ACCEPTED");assertThat(d.path("version").asString()).isEqualTo("2");assertThat(d.path("booking").path("occupancyStatus").asString()).isEqualTo("ACTIVE");assertThat(count("refund_record")).isZero();assertThat(count("order_event")).isEqualTo(3);reset(money,orders,bookings,events);assertThat(cancel(id,merchant,"原因").statusCode()).isEqualTo(200);
    }
    @Test void finalSessionExpirationRollsBackActualRefundAndOrderWrite()throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var merchant=login(f.owner());var id=accepted(f,student,merchant);var real=sqlSession.getMapper(OrderMapper.class);
        doAnswer(i->{int n=real.refundCloseFrom(i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(3),i.getArgument(4),i.getArgument(5));fixed(NOW.plusSeconds(7200));return n;}).when(orders).refundCloseFrom(anyLong(),anyLong(),anyString(),anyString(),anyString(),any());expect(cancel(id,merchant,"原因"),401,"SESSION_EXPIRED");assertThat(count("refund_record")).isZero();assertThat(jdbc.queryForObject("SELECT status FROM service_order WHERE id=?",String.class,id)).isEqualTo("ACCEPTED");assertThat(count("order_event")).isEqualTo(3);
    }
    @Test void versionExhaustionCannotInsertRefund()throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var merchant=login(f.owner());var id=accepted(f,student,merchant);jdbc.update("UPDATE service_order SET version=? WHERE id=?",Long.MAX_VALUE,id);expect(cancel(id,merchant,"原因"),409,"ORDER_STATE_CONFLICT");assertThat(count("refund_record")).isZero();
    }
    @Test void mysqlUniqueRefundAndEventConstraintsRemainAuthoritative()throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var merchant=login(f.owner());var id=accepted(f,student,merchant);data(cancel(id,merchant,"原因"));
        assertSqlFailure(()->jdbc.update("INSERT INTO refund_record(order_id,payment_id,refund_no,amount,reason,status,refunded_at) SELECT order_id,payment_id,'duplicate',amount,reason,status,refunded_at FROM refund_record WHERE order_id=?",id),"uk_refund_order",1062);
        assertSqlFailure(()->jdbc.update("INSERT INTO order_event(order_id,event_seq,action,to_status,actor_type,occurred_at) SELECT order_id,event_seq,action,to_status,'SYSTEM',occurred_at FROM order_event WHERE order_id=? AND event_seq=3",id),"uk_order_event_seq",1062);
    }
    @Test void realMysqlLockTimeoutReturns503AndRetryIsSafe()throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var merchant=login(f.owner());var id=accepted(f,student,merchant);var real=sqlSession.getMapper(OrderMapper.class);var reached=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(i->{jdbc.execute("SET SESSION innodb_lock_wait_timeout=1");try{return real.shareShop(i.getArgument(0));}finally{jdbc.execute("SET SESSION innodb_lock_wait_timeout=50");}}).when(orders).shareShop(f.shop());
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){var holder=pool.submit(()->new TransactionTemplate(manager).executeWithoutResult(tx->{shops.lock(f.shop());reached.countDown();await(release);}));assertThat(reached.await(10,TimeUnit.SECONDS)).isTrue();expect(cancel(id,merchant,"原因"),503,"SERVICE_TEMPORARILY_UNAVAILABLE");assertThat(count("refund_record")).isZero();release.countDown();holder.get(10,TimeUnit.SECONDS);reset(orders);assertThat(cancel(id,merchant,"原因").statusCode()).isEqualTo(200);}
        finally {release.countDown();}
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
