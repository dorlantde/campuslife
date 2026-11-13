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
class MerchantBookingConfirmationMysqlIT {
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
    private String paid(Fixture f,String token) throws Exception { enable();var id=data(create(f,token,"order")).path("id").asString();data(call("POST","/api/v1/orders/"+id+"/payments/mock",null,token,null));return id; }
    private HttpResponse<String> accept(String id,String token) throws Exception { return call("POST","/api/v1/merchant/orders/"+id+"/accept",null,token,null); }
    private HttpResponse<String> reject(String id,String token,String reason) throws Exception { return call("POST","/api/v1/merchant/orders/"+id+"/reject",Map.of("reason",reason),token,null); }
    private HttpResponse<String> cancel(String id,String token) throws Exception { return call("POST","/api/v1/orders/"+id+"/cancel",null,token,null); }
    private JsonNode detail(String id,String token) throws Exception { return data(call("GET","/api/v1/merchant/orders/"+id,null,token,null)); }
    @Test void acceptsExactlyOncePreservesFundsAndSnapshotsAndIsVisibleToBothParties() throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var merchant=login(f.owner());var id=paid(f,student);
        var before=detail(id,merchant);assertThat(before.has("studentId")).isFalse();assertThat(before.has("username")).isFalse();
        var first=data(accept(id,merchant));assertThat(first.path("status").asString()).isEqualTo("ACCEPTED");assertThat(first.path("paymentStatus").asString()).isEqualTo("PAID");assertThat(first.path("booking").path("occupancyStatus").asString()).isEqualTo("ACTIVE");assertThat(first.path("acceptedAt").asString()).isEqualTo(NOW.toString());assertThat(first.path("version").asString()).isEqualTo("2");
        assertThat(first.path("payment")).isEqualTo(before.path("payment"));assertThat(first.path("acceptanceDeadline")).isEqualTo(before.path("acceptanceDeadline"));fixed(NOW.plusSeconds(30));assertThat(data(accept(id,merchant))).isEqualTo(first);
        assertThat(data(call("GET","/api/v1/orders/"+id,null,student,null))).isEqualTo(first);assertThat(count("payment_record")).isEqualTo(1);assertThat(count("refund_record")).isZero();assertThat(count("order_event")).isEqualTo(3);assertThat(starts(f)).doesNotContain(START);
        assertThat(jdbc.queryForObject("SELECT actor_type FROM order_event WHERE order_id=? AND event_seq=2",String.class,id)).isEqualTo("MERCHANT");assertThat(jdbc.queryForObject("SELECT actor_id FROM order_event WHERE order_id=? AND event_seq=2",Long.class,id)).isEqualTo(f.owner().id());
        assertThat(jdbc.queryForObject("SELECT version FROM shop WHERE id=?",Long.class,f.shop())).isZero();
    }
    @Test void rejectionNormalizesReasonAndReplaysOnlyTheSameReasonEvenWithGateOff() throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var merchant=login(f.owner());var id=paid(f,student);
        var first=data(reject(id,merchant,"  人员临时无法服务  "));assertThat(first.path("closeReason").asString()).isEqualTo("MERCHANT_REJECT");assertThat(first.path("closeNote").asString()).isEqualTo("人员临时无法服务");assertThat(first.path("paymentStatus").asString()).isEqualTo("REFUNDED");assertThat(first.path("booking").path("occupancyStatus").asString()).isEqualTo("RELEASED");assertThat(first.path("version").asString()).isEqualTo("2");
        assertThat(jdbc.queryForObject("SELECT reason_note FROM refund_record WHERE order_id=?",String.class,id)).isEqualTo("人员临时无法服务");assertThat(jdbc.queryForObject("SELECT reason FROM order_event WHERE order_id=? AND event_seq=2",String.class,id)).isEqualTo("人员临时无法服务");
        doReturn(false).when(mockPayment).enabled();assertThat(data(reject(id,merchant,"人员临时无法服务"))).isEqualTo(first);expect(reject(id,merchant,"不同原因"),409,"ORDER_STATE_CONFLICT");expect(accept(id,merchant),409,"ORDER_STATE_CONFLICT");expect(cancel(id,student),409,"ORDER_STATE_CONFLICT");assertThat(count("refund_record")).isEqualTo(1);assertThat(count("order_event")).isEqualTo(3);assertThat(starts(f)).contains(START);
    }
    @ParameterizedTest @ValueSource(strings={"detail","accept","reject"})
    void roleOwnershipAndSessionBoundariesAreEnforced(String action) throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var id=paid(f,student);var other=fixture();String path="/api/v1/merchant/orders/"+id+(action.equals("detail")?"":"/"+action);Object body=action.equals("reject")?Map.of("reason","测试原因"):null;var method=action.equals("detail")?"GET":"POST";
        expect(call(method,path,body,null,null),401,"AUTH_REQUIRED");expect(call(method,path,body,student,null),403,"ROLE_FORBIDDEN");expect(call(method,path,body,login(user("ADMIN")),null),403,"ROLE_FORBIDDEN");expect(call(method,path,body,login(other.owner()),null),404,"RESOURCE_NOT_FOUND");
        var merchant=login(f.owner());expect(call(method,path.replace(id,"01"),body,merchant,null),400,"VALIDATION_FAILED");expect(call(method,path.replace(id,"9223372036854775807"),body,merchant,null),404,"RESOURCE_NOT_FOUND");
        jdbc.update("UPDATE shop_member SET status='DISABLED' WHERE shop_id=?",f.shop());expect(call(method,path,body,merchant,null),403,"ROLE_FORBIDDEN");assertThat(count("refund_record")).isZero();assertThat(count("order_event")).isEqualTo(2);
    }
    @ParameterizedTest @ValueSource(strings={"SUSPENDED","REJECTED","PENDING"})
    void lostApprovalStillAllowsHistoricalReadAndRefundButNeverAccept(String approval) throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var merchant=login(f.owner());var id=paid(f,student);jdbc.update("UPDATE shop SET approval_status=? WHERE id=?",approval,f.shop());
        assertThat(detail(id,merchant).path("status").asString()).isEqualTo("PENDING_ACCEPTANCE");expect(accept(id,merchant),403,"SHOP_NOT_APPROVED");assertThat(reject(id,merchant,"无法履约").statusCode()).isEqualTo(200);assertThat(detail(id,merchant).path("paymentStatus").asString()).isEqualTo("REFUNDED");
    }
    @Test void disabledMerchantCannotReadOrWriteButSystemCanRefundExistingOrder() throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var merchant=login(f.owner());var id=paid(f,student);jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?",f.owner().id());
        expect(call("GET","/api/v1/merchant/orders/"+id,null,merchant,null),403,"ACCOUNT_DISABLED");expect(accept(id,merchant),403,"ACCOUNT_DISABLED");expect(reject(id,merchant,"原因"),403,"ACCOUNT_DISABLED");fixed(NOW.plusSeconds(1800));assertThat(expiry.scan()).isEqualTo(1);assertThat(count("refund_record")).isEqualTo(1);
    }
    @Test void stoppingBusinessAndChangingServiceDoNotAlterExistingBookingAcceptance() throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var merchant=login(f.owner());var id=paid(f,student);jdbc.update("UPDATE shop SET business_enabled=FALSE,name='新名称' WHERE id=?",f.shop());jdbc.update("UPDATE service_item SET enabled=FALSE,price_amount=8000,duration_minutes=60 WHERE id=?",f.service());jdbc.update("UPDATE shop_category SET enabled=FALSE WHERE code='HAIRCUT'");
        try {var result=data(accept(id,merchant));assertThat(result.path("payableAmount").asLong()).isEqualTo(3000);assertThat(result.path("booking").path("durationMinutes").asInt()).isEqualTo(30);assertThat(result.path("shopName").asString()).isEqualTo("模拟预约店");}
        finally {jdbc.update("UPDATE shop_category SET enabled=TRUE WHERE code='HAIRCUT'");}
    }
    @ParameterizedTest @ValueSource(longs={1799999,1800000,1800001})
    void acceptanceDeadlineCommitsTimeoutBeforeReturningConflict(long elapsed) throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var merchant=login(f.owner());var id=paid(f,student);fixed(NOW.plusMillis(elapsed));
        if(elapsed<1800000){assertThat(accept(id,merchant).statusCode()).isEqualTo(200);assertThat(expiry.scan()).isZero();assertThat(count("refund_record")).isZero();}
        else {expect(accept(id,merchant),409,"ACCEPTANCE_DEADLINE_PASSED");expect(accept(id,merchant),409,"ACCEPTANCE_DEADLINE_PASSED");assertThat(detail(id,merchant).path("closeReason").asString()).isEqualTo("ACCEPTANCE_TIMEOUT");assertThat(count("refund_record")).isEqualTo(1);assertThat(starts(f)).contains(START);}
    }
    @ParameterizedTest @ValueSource(longs={1799999,1800000,1800001})
    void rejectionAtDeadlineUsesSystemTimeoutInsteadOfMerchantReason(long elapsed) throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var merchant=login(f.owner());var id=paid(f,student);fixed(NOW.plusMillis(elapsed));
        if(elapsed<1800000){assertThat(reject(id,merchant,"原因").statusCode()).isEqualTo(200);assertThat(detail(id,merchant).path("closeNote").asString()).isEqualTo("原因");}
        else {expect(reject(id,merchant,"原因"),409,"ACCEPTANCE_DEADLINE_PASSED");assertThat(detail(id,merchant).path("closeReason").asString()).isEqualTo("ACCEPTANCE_TIMEOUT");assertThat(detail(id,merchant).path("closeNote").isNull()).isTrue();assertThat(jdbc.queryForObject("SELECT actor_type FROM order_event WHERE order_id=? AND event_seq=2",String.class,id)).isEqualTo("SYSTEM");}
        assertThat(count("refund_record")).isEqualTo(1);assertThat(count("order_event")).isEqualTo(3);
    }
    @ParameterizedTest @ValueSource(longs={3599999,3600000,3600001})
    void acceptedStudentCancellationAllowsExactlyOneHourAndReplaysAfterWindow(long remaining) throws Exception {
        var f=fixture();var user=user("STUDENT");var student=login(user);var merchant=login(f.owner());var id=paid(f,student);data(accept(id,merchant));fixed(START.minusMillis(remaining));student=login(user);
        if(remaining<3600000){expect(cancel(id,student),409,"CANCEL_WINDOW_CLOSED");assertThat(count("refund_record")).isZero();assertThat(jdbc.queryForObject("SELECT status FROM service_order WHERE id=?",String.class,id)).isEqualTo("ACCEPTED");}
        else {var first=data(cancel(id,student));assertThat(first.path("version").asString()).isEqualTo("3");assertThat(first.path("paymentStatus").asString()).isEqualTo("REFUNDED");assertThat(jdbc.queryForObject("SELECT from_status FROM order_event WHERE order_id=? AND event_seq=3",String.class,id)).isEqualTo("ACCEPTED");fixed(START.minusSeconds(30));assertThat(data(cancel(id,student))).isEqualTo(first);assertThat(count("refund_record")).isEqualTo(1);}
    }
    @Test void moneyGateAllowsAcceptanceButBlocksEveryNewRefundAndAllowsExistingRejectReplay() throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var merchant=login(f.owner());var id=paid(f,student);doReturn(false).when(mockPayment).enabled();
        expect(reject(id,merchant,"原因"),503,"MOCK_PAYMENT_DISABLED");assertThat(accept(id,merchant).statusCode()).isEqualTo(200);expect(cancel(id,student),503,"MOCK_PAYMENT_DISABLED");assertThat(count("refund_record")).isZero();assertThat(detail(id,merchant).path("status").asString()).isEqualTo("ACCEPTED");
        var other=addBarber(f);enable();var second=data(create(other,student,"second")).path("id").asString();data(call("POST","/api/v1/orders/"+second+"/payments/mock",null,student,null));fixed(NOW.plusSeconds(1800));doReturn(false).when(mockPayment).enabled();expect(accept(second,merchant),503,"MOCK_PAYMENT_DISABLED");expect(reject(second,merchant,"原因"),503,"MOCK_PAYMENT_DISABLED");assertThat(detail(second,merchant).path("status").asString()).isEqualTo("PENDING_ACCEPTANCE");assertThat(starts(other)).doesNotContain(START);enable();assertThat(expiry.scan()).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"missing","null","blank","number","long","unknown","array","unicode"})
    void rejectReasonIsStrictAndCountsUnicodeCodePoints(String mode) throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var merchant=login(f.owner());var id=paid(f,student);
        Object request=switch(mode){case "missing"->Map.of();case "null"->Collections.singletonMap("reason",null);case "blank"->Map.of("reason","  \t ");case "number"->Map.of("reason",1);case "long"->Map.of("reason","字".repeat(301));case "unknown"->Map.of("reason","原因","extra",true);case "array"->List.of("原因");default->Map.of("reason","😀".repeat(300));};
        var result=call("POST","/api/v1/merchant/orders/"+id+"/reject",request,merchant,null);
        if(mode.equals("unicode")){assertThat(result.statusCode()).as(result.body()).isEqualTo(200);}else{expect(result,400,"VALIDATION_FAILED");assertThat(count("refund_record")).isZero();}
    }
    @ParameterizedTest @ValueSource(strings={"{}","null","{\"expectedVersion\":\"1\"}"})
    void acceptRejectsAnyBusinessRequestBody(String body) throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var merchant=login(f.owner());var id=paid(f,student);
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+env.getRequiredProperty("local.server.port")+"/api/v1/merchant/orders/"+id+"/accept")).header("Authorization","Bearer "+merchant).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();expect(http.send(request,HttpResponse.BodyHandlers.ofString()),400,"VALIDATION_FAILED");assertThat(detail(id,merchant).path("status").asString()).isEqualTo("PENDING_ACCEPTANCE");
    }
    @Test void unpaidClosedAndWrongTypeOrdersCannotBeAcceptedOrRejected() throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var merchant=login(f.owner());var id=data(create(f,student,"unpaid")).path("id").asString();assertThat(detail(id,merchant).path("status").asString()).isEqualTo("PENDING_PAYMENT");expect(accept(id,merchant),409,"ORDER_STATE_CONFLICT");expect(reject(id,merchant,"原因"),409,"ORDER_STATE_CONFLICT");data(cancel(id,student));expect(accept(id,merchant),409,"ORDER_STATE_CONFLICT");expect(reject(id,merchant,"原因"),409,"ORDER_STATE_CONFLICT");jdbc.update("UPDATE service_order SET order_type='PRINT' WHERE id=?",id);expect(accept(id,merchant),404,"RESOURCE_NOT_FOUND");expect(call("GET","/api/v1/merchant/orders/"+id,null,merchant,null),404,"RESOURCE_NOT_FOUND");
    }
    @ParameterizedTest @ValueSource(strings={"IN_SERVICE","COMPLETED"})
    void laterStatesOnlyReplayAcceptanceAndDoNotAllowNewRejectOrStudentCancel(String state) throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var merchant=login(f.owner());var id=paid(f,student);var first=data(accept(id,merchant));jdbc.update("UPDATE service_order SET status=? WHERE id=?",state,id);assertThat(data(accept(id,merchant)).path("acceptedAt")).isEqualTo(first.path("acceptedAt"));expect(reject(id,merchant,"原因"),409,"ORDER_STATE_CONFLICT");expect(cancel(id,student),409,"ORDER_STATE_CONFLICT");assertThat(count("order_event")).isEqualTo(3);
    }
    @ParameterizedTest @ValueSource(strings={"accept","reject","cancel"})
    void versionExhaustionLeavesNoPartialChanges(String action) throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var merchant=login(f.owner());var id=paid(f,student);if(action.equals("cancel")){data(accept(id,merchant));}jdbc.update("UPDATE service_order SET version=? WHERE id=?",Long.MAX_VALUE,id);
        expect(action.equals("accept")?accept(id,merchant):action.equals("reject")?reject(id,merchant,"原因"):cancel(id,student),409,"ORDER_STATE_CONFLICT");assertThat(count("refund_record")).isZero();
    }
    @Test void concurrentRepeatedAcceptanceCreatesOneEventAndFixedAcceptanceFact() throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var tokens=List.of(login(f.owner()),login(f.owner()));var id=paid(f,student);var gate=new CountDownLatch(1);var results=new ArrayList<Future<HttpResponse<String>>>();
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {for(int i=0;i<10;i++){int n=i;results.add(pool.submit(()->{gate.await();return accept(id,tokens.get(n%2));}));}gate.countDown();JsonNode fact=null;for(var result:results){var d=data(result.get(20,TimeUnit.SECONDS));if(fact==null){fact=d;}else{assertThat(d).isEqualTo(fact);}}}assertThat(count("order_event")).isEqualTo(3);assertThat(count("refund_record")).isZero();
    }
    @Test void concurrentRejectCancelAndScanHaveOnlyOneClosureAndRefund() throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var merchant=login(f.owner());var id=paid(f,student);var gate=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {var a=pool.submit(()->{gate.await();return reject(id,merchant,"原因");});var b=pool.submit(()->{gate.await();return cancel(id,student);});var e=pool.submit(()->{gate.await();return expiry.scan();});gate.countDown();var x=a.get(20,TimeUnit.SECONDS);var y=b.get(20,TimeUnit.SECONDS);assertThat(x.statusCode()).isIn(200,409);assertThat(y.statusCode()).isIn(200,409);assertThat(x.statusCode()==200||y.statusCode()==200).isTrue();assertThat(e.get(20,TimeUnit.SECONDS)).isZero();}assertThat(count("refund_record")).isEqualTo(1);assertThat(count("order_event")).isEqualTo(3);assertThat(detail(id,merchant).path("version").asString()).isEqualTo("2");
    }
    @Test void acceptVersusStudentCancelCannotResurrectClosedOrderOrDoubleRefund() throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var merchant=login(f.owner());var id=paid(f,student);var gate=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {var a=pool.submit(()->{gate.await();return accept(id,merchant);});var b=pool.submit(()->{gate.await();return cancel(id,student);});gate.countDown();assertThat(a.get(20,TimeUnit.SECONDS).statusCode()).isIn(200,409);assertThat(b.get(20,TimeUnit.SECONDS).statusCode()).isEqualTo(200);}var result=detail(id,merchant);assertThat(result.path("status").asString()).isEqualTo("CLOSED");assertThat(count("refund_record")).isEqualTo(1);assertThat(result.path("booking").path("occupancyStatus").asString()).isEqualTo("RELEASED");
    }
    @Test void concurrentDeadlineAcceptRejectScannerAndCreationRefundExactlyOnce() throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var other=login(user("STUDENT"));var merchant=login(f.owner());var id=paid(f,student);fixed(NOW.plusSeconds(1800));var gate=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {var a=pool.submit(()->{gate.await();return accept(id,merchant);});var b=pool.submit(()->{gate.await();return reject(id,merchant,"原因");});var e=pool.submit(()->{gate.await();return expiry.scan();});var n=pool.submit(()->{gate.await();return create(f,other,"new");});gate.countDown();expect(a.get(20,TimeUnit.SECONDS),409,"ACCEPTANCE_DEADLINE_PASSED");expect(b.get(20,TimeUnit.SECONDS),409,"ACCEPTANCE_DEADLINE_PASSED");assertThat(e.get(20,TimeUnit.SECONDS)).isIn(0,1);assertThat(n.get(20,TimeUnit.SECONDS).statusCode()).isEqualTo(201);}assertThat(count("refund_record")).isEqualTo(1);assertThat(count("order_event")).isEqualTo(4);
    }
    @ParameterizedTest @ValueSource(strings={"accept","reject","cancel"})
    void actualDateLockWaitUsesFreshDecisionTime(String action) throws Exception {
        var f=fixture();var user=user("STUDENT");var student=login(user);var merchant=login(f.owner());var id=paid(f,student);if(action.equals("cancel")){data(accept(id,merchant));fixed(START.minusSeconds(3601));student=login(user);}var ownToken=student;var reached=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var holder=pool.submit(()->new TransactionTemplate(manager).executeWithoutResult(tx->{schedules.lockGuard(f.barber(),DATE);reached.countDown();await(release);}));assertThat(reached.await(10,TimeUnit.SECONDS)).isTrue();var result=pool.submit(()->action.equals("cancel")?cancel(id,ownToken):action.equals("accept")?accept(id,merchant):reject(id,merchant,"原因"));Thread.sleep(200);assertThat(result.isDone()).isFalse();fixed(action.equals("cancel")?START.minusMillis(3599999):NOW.plusSeconds(1800));release.countDown();holder.get(10,TimeUnit.SECONDS);expect(result.get(10,TimeUnit.SECONDS),409,action.equals("cancel")?"CANCEL_WINDOW_CLOSED":"ACCEPTANCE_DEADLINE_PASSED");
        } finally {release.countDown();}
    }
    @ParameterizedTest @ValueSource(strings={"acceptOrder","acceptEvent","refund","close","release","rejectEvent","acceptedClose"})
    void injectedWriteFailuresRollbackStateMoneyAndOccupancy(String step) throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var merchant=login(f.owner());var id=paid(f,student);boolean accepted=step.equals("acceptedClose");if(accepted){data(accept(id,merchant));}
        switch(step){case "acceptOrder"->doThrow(new IllegalStateException("injected")).when(orders).accept(anyLong(),anyLong(),any());case "acceptEvent"->doThrow(new IllegalStateException("injected")).when(events).append(anyLong(),eq(2L),eq("ACCEPT"),anyString(),anyString(),anyString(),anyLong(),isNull(),any());case "refund"->doThrow(new IllegalStateException("injected")).when(money).refundWithNote(anyLong(),anyLong(),anyString(),anyLong(),anyString(),anyString(),any());case "close","acceptedClose"->doThrow(new IllegalStateException("injected")).when(orders).refundCloseFrom(anyLong(),anyLong(),anyString(),anyString(),nullable(String.class),any());case "release"->doThrow(new IllegalStateException("injected")).when(bookings).releaseActive(anyLong());default->doThrow(new IllegalStateException("injected")).when(events).append(anyLong(),eq(2L),eq("MERCHANT_REJECT"),anyString(),anyString(),anyString(),anyLong(),anyString(),any());}
        expect(accepted?cancel(id,student):step.startsWith("accept")?accept(id,merchant):reject(id,merchant,"原因"),500,"INTERNAL_ERROR");var d=detail(id,merchant);assertThat(d.path("status").asString()).isEqualTo(accepted?"ACCEPTED":"PENDING_ACCEPTANCE");assertThat(d.path("booking").path("occupancyStatus").asString()).isEqualTo("ACTIVE");assertThat(count("refund_record")).isZero();assertThat(count("order_event")).isEqualTo(accepted?3:2);
    }
    @ParameterizedTest @ValueSource(strings={"accept","reject"})
    void sessionExpiringAfterActualWriteRollsBackEverything(String action) throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var merchant=login(f.owner());var id=paid(f,student);var real=sqlSession.getMapper(OrderMapper.class);
        if(action.equals("accept")){doAnswer(i->{int n=real.accept(i.getArgument(0),i.getArgument(1),i.getArgument(2));fixed(NOW.plusSeconds(7200));return n;}).when(orders).accept(anyLong(),anyLong(),any());}
        else {doAnswer(i->{int n=real.refundCloseFrom(i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(3),i.getArgument(4),i.getArgument(5));fixed(NOW.plusSeconds(7200));return n;}).when(orders).refundCloseFrom(anyLong(),anyLong(),anyString(),anyString(),anyString(),any());}
        expect(action.equals("accept")?accept(id,merchant):reject(id,merchant,"原因"),401,"SESSION_EXPIRED");assertThat(jdbc.queryForObject("SELECT status FROM service_order WHERE id=?",String.class,id)).isEqualTo("PENDING_ACCEPTANCE");assertThat(count("refund_record")).isZero();assertThat(count("order_event")).isEqualTo(2);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void approvalAndOwnerAreRecheckedAfterWaitingForShopLock(boolean member) throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var merchant=login(f.owner());var id=paid(f,student);var reached=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {var holder=pool.submit(()->new TransactionTemplate(manager).executeWithoutResult(tx->{shops.lock(f.shop());if(member){jdbc.update("UPDATE shop_member SET status='DISABLED' WHERE shop_id=?",f.shop());}else{jdbc.update("UPDATE shop SET approval_status='SUSPENDED' WHERE id=?",f.shop());}reached.countDown();await(release);}));assertThat(reached.await(10,TimeUnit.SECONDS)).isTrue();var result=pool.submit(()->accept(id,merchant));Thread.sleep(200);assertThat(result.isDone()).isFalse();release.countDown();holder.get(10,TimeUnit.SECONDS);expect(result.get(10,TimeUnit.SECONDS),403,member?"ROLE_FORBIDDEN":"SHOP_NOT_APPROVED");assertThat(jdbc.queryForObject("SELECT status FROM service_order WHERE id=?",String.class,id)).isEqualTo("PENDING_ACCEPTANCE");}
        finally {release.countDown();}
    }
    @Test void actualMysqlLockTimeoutReturns503AndOriginalAcceptanceCanRetry() throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var merchant=login(f.owner());var id=paid(f,student);var real=sqlSession.getMapper(OrderMapper.class);var reached=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(i->{jdbc.execute("SET SESSION innodb_lock_wait_timeout=1");try{return real.shareShop(i.getArgument(0));}finally{jdbc.execute("SET SESSION innodb_lock_wait_timeout=50");}}).when(orders).shareShop(f.shop());
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {var holder=pool.submit(()->new TransactionTemplate(manager).executeWithoutResult(tx->{shops.lock(f.shop());reached.countDown();await(release);}));assertThat(reached.await(10,TimeUnit.SECONDS)).isTrue();expect(accept(id,merchant),503,"SERVICE_TEMPORARILY_UNAVAILABLE");assertThat(count("order_event")).isEqualTo(2);release.countDown();holder.get(10,TimeUnit.SECONDS);reset(orders);assertThat(accept(id,merchant).statusCode()).isEqualTo(200);}
        finally {release.countDown();}
    }
    @Test void merchantDetailUsesRepeatableReadAcrossConcurrentStudentRefund() throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var merchant=login(f.owner());var id=paid(f,student);var reached=new CountDownLatch(1);var release=new CountDownLatch(1);var once=new java.util.concurrent.atomic.AtomicBoolean(true);var real=sqlSession.getMapper(MoneyMapper.class);
        doAnswer(i->{var p=real.payment(i.getArgument(0));if(once.compareAndSet(true,false)){reached.countDown();await(release);}return p;}).when(money).payment(Long.parseLong(id));
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {var reading=pool.submit(()->detail(id,merchant));assertThat(reached.await(10,TimeUnit.SECONDS)).isTrue();data(cancel(id,student));release.countDown();var old=reading.get(10,TimeUnit.SECONDS);assertThat(old.path("status").asString()).isEqualTo("PENDING_ACCEPTANCE");assertThat(old.path("refund").isNull()).isTrue();assertThat(old.path("booking").path("occupancyStatus").asString()).isEqualTo("ACTIVE");assertThat(detail(id,merchant).path("paymentStatus").asString()).isEqualTo("REFUNDED");}
        finally {release.countDown();}
    }
    @Test void acceptedOccupancySurvivesOriginalAcceptanceDeadlineAndCannotBeCleanedByCreation() throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var merchant=login(f.owner());var id=paid(f,student);data(accept(id,merchant));fixed(NOW.plusSeconds(1800));
        assertThat(expiry.scan()).isZero();assertThat(transactions.expire(Long.parseLong(id))).isFalse();expect(create(f,login(user("STUDENT")),"occupied"),409,"BOOKING_CONFLICT");assertThat(count("refund_record")).isZero();assertThat(detail(id,merchant).path("status").asString()).isEqualTo("ACCEPTED");assertThat(starts(f)).doesNotContain(START);
    }
    @Test void acceptanceBeforeDeadlineWinsAgainstScannerWaitingUntilAfterDeadline() throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var merchant=login(f.owner());var id=paid(f,student);fixed(NOW.plusMillis(1799999));var reached=new CountDownLatch(1);var release=new CountDownLatch(1);var real=sqlSession.getMapper(OrderMapper.class);
        doAnswer(i->{int changed=real.accept(i.getArgument(0),i.getArgument(1),i.getArgument(2));reached.countDown();await(release);return changed;}).when(orders).accept(anyLong(),anyLong(),any());
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {var accepting=pool.submit(()->accept(id,merchant));assertThat(reached.await(10,TimeUnit.SECONDS)).isTrue();fixed(NOW.plusSeconds(1800));var scanner=pool.submit(()->transactions.expire(Long.parseLong(id)));Thread.sleep(200);assertThat(scanner.isDone()).isFalse();release.countDown();assertThat(data(accepting.get(10,TimeUnit.SECONDS)).path("acceptedAt").asString()).isEqualTo(NOW.plusMillis(1799999).toString());assertThat(scanner.get(10,TimeUnit.SECONDS)).isFalse();assertThat(count("refund_record")).isZero();assertThat(count("order_event")).isEqualTo(3);}
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
