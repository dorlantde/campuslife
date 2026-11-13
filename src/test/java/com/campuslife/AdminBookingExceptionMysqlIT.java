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
class AdminBookingExceptionMysqlIT {
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





    private void enable(){doReturn(true).when(mockPayment).enabled();}
    private String paid(Fixture f,String student)throws Exception {
        enable();var id=data(create(f,student,UUID.randomUUID().toString())).path("id").asString();
        data(call("POST","/api/v1/orders/"+id+"/payments/mock",null,student,null));return id;
    }
    private String prepared(Fixture f,String state)throws Exception {
        var id=paid(f,login(user("STUDENT")));
        if(!state.equals("PENDING_ACCEPTANCE")){data(call("POST","/api/v1/merchant/orders/"+id+"/accept",null,login(f.owner()),null));}
        if(state.equals("IN_SERVICE")||state.equals("COMPLETED")){fixed(START);data(action(id,login(f.owner()),false));}
        if(state.equals("COMPLETED")){data(action(id,login(f.owner()),true));}return id;
    }
    private HttpResponse<String> action(String id,String token,boolean complete)throws Exception{return call("POST","/api/v1/merchant/orders/"+id+(complete?"/complete":"/start"),null,token,null);}
    private JsonNode detail(String id,String token)throws Exception{return data(call("GET","/api/v1/admin/orders/"+id,null,token,null));}
    private HttpResponse<String> close(String id,String token,String reason,String version)throws Exception{return call("POST","/api/v1/admin/orders/"+id+"/close-exception",Map.of("reason",reason,"expectedVersion",version),token,null);}
    private void oneRefund(String id,String state,long version,long admin) {
        assertThat(count("payment_record")).isEqualTo(1);assertThat(count("refund_record")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT amount FROM refund_record WHERE order_id=?",Long.class,id)).isEqualTo(3000);
        assertThat(jdbc.queryForObject("SELECT occupancy_status FROM booking_detail WHERE order_id=?",String.class,id)).isEqualTo(state);
        assertThat(jdbc.queryForObject("SELECT version FROM service_order WHERE id=?",Long.class,id)).isEqualTo(version);
        var row=jdbc.queryForMap("SELECT actor_type,actor_id,action,reason FROM order_event WHERE order_id=? AND event_seq=?",id,version);
        assertThat(row.get("actor_type")).isEqualTo("ADMIN");assertThat(row.get("actor_id")).isEqualTo(admin);
        assertThat(row.get("action")).isEqualTo("ADMIN_EXCEPTION");assertThat(row.get("reason")).isEqualTo("核实原因");
    }
    @ParameterizedTest @ValueSource(strings={"PENDING_ACCEPTANCE","ACCEPTED","IN_SERVICE"})
    void closesPaidStatesAtomicallyAndReplaysExactRequestOnly(String state)throws Exception {
        var f=fixture();var id=prepared(f,state);var admin=user("ADMIN");var token=login(admin);var before=detail(id,token);var v=before.path("version").asString();
        var result=data(close(id,token,"  核实原因  ",v));assertThat(result.path("status").asString()).isEqualTo("CLOSED");assertThat(result.path("paymentStatus").asString()).isEqualTo("REFUNDED");
        assertThat(result.path("closeReason").asString()).isEqualTo("ADMIN_EXCEPTION");assertThat(result.path("closeNote").asString()).isEqualTo("核实原因");
        assertThat(result.path("payment")).isEqualTo(before.path("payment"));assertThat(result.path("refund").path("amount").asLong()).isEqualTo(3000);
        assertThat(result.path("startedAt")).isEqualTo(before.path("startedAt"));assertThat(result.path("booking").path("startAt")).isEqualTo(before.path("booking").path("startAt"));assertThat(result.path("booking").path("endAt")).isEqualTo(before.path("booking").path("endAt"));
        doReturn(false).when(mockPayment).enabled();assertThat(data(close(id,token,"核实原因",v))).isEqualTo(result);
        expect(close(id,token,"改原因",v),409,"ORDER_STATE_CONFLICT");expect(close(id,token,"核实原因",result.path("version").asString()),409,"ORDER_STATE_CONFLICT");expect(close(id,login(user("ADMIN")),"核实原因",v),409,"ORDER_STATE_CONFLICT");
        oneRefund(id,state.equals("IN_SERVICE")?"CONSUMED":"RELEASED",Long.parseLong(v)+1,admin.id());
        assertThat(count("order_event")).isEqualTo(Long.parseLong(v)+2);
        assertThat(jdbc.queryForObject("SELECT reason_note FROM refund_record WHERE order_id=?",String.class,id)).isEqualTo("核实原因");
    }
    @ParameterizedTest @ValueSource(strings={"PENDING_PAYMENT","COMPLETED","USER_CANCEL","ACCEPTANCE_TIMEOUT"})
    void rejectsTerminalUnpaidAndOtherClosures(String state)throws Exception {
        var f=fixture();String id;var student=login(user("STUDENT"));
        if(state.equals("PENDING_PAYMENT")){id=data(create(f,student,"unpaid")).path("id").asString();}
        else if(state.equals("USER_CANCEL")){id=paid(f,student);data(call("POST","/api/v1/orders/"+id+"/cancel",null,student,null));}
        else if(state.equals("ACCEPTANCE_TIMEOUT")){id=paid(f,student);fixed(NOW.plusSeconds(1800));assertThat(transactions.expire(Long.parseLong(id))).isTrue();}
        else{id=prepared(f,state);}
        var token=login(user("ADMIN"));var before=detail(id,token);int refunds=count("refund_record"),eventsBefore=count("order_event");
        expect(close(id,token,"核实原因",before.path("version").asString()),409,"ORDER_STATE_CONFLICT");assertThat(detail(id,token)).isEqualTo(before);assertThat(count("refund_record")).isEqualTo(refunds);assertThat(count("order_event")).isEqualTo(eventsBefore);
    }
    @Test void staleVersionRejectsThenFreshVerificationCanCloseAfterMerchantStarted()throws Exception {
        var f=fixture();var id=prepared(f,"ACCEPTED");fixed(START);var token=login(user("ADMIN"));var old=detail(id,token);data(action(id,login(f.owner()),false));doReturn(false).when(mockPayment).enabled();
        expect(close(id,token,"核实原因",old.path("version").asString()),409,"CONFIG_VERSION_CONFLICT");expect(close(id,token,"核实原因","3"),503,"MOCK_PAYMENT_DISABLED");assertThat(count("refund_record")).isZero();enable();data(close(id,token,"核实原因","3"));
    }
    @ParameterizedTest @ValueSource(strings={"PENDING_ACCEPTANCE","ACCEPTED","IN_SERVICE"})
    void newRefundRequiresFlagAndLeavesEveryFactUnchanged(String state)throws Exception {
        var f=fixture();var id=prepared(f,state);var token=login(user("ADMIN"));var before=detail(id,token);doReturn(false).when(mockPayment).enabled();
        expect(close(id,token,"核实原因",before.path("version").asString()),503,"MOCK_PAYMENT_DISABLED");assertThat(detail(id,token)).isEqualTo(before);assertThat(count("refund_record")).isZero();
    }
    @Test void targetDisabledAccountsOwnerAndShopDoNotPreventAdministratorResolution()throws Exception {
        var f=fixture();var student=user("STUDENT");var id=paid(f,login(student));jdbc.update("UPDATE account SET status='DISABLED' WHERE id IN (?,?)",student.id(),f.owner().id());jdbc.update("UPDATE shop_member SET status='DISABLED' WHERE shop_id=?",f.shop());jdbc.update("UPDATE shop SET approval_status='SUSPENDED',business_enabled=FALSE WHERE id=?",f.shop());
        var token=login(user("ADMIN"));assertThat(detail(id,token).path("status").asString()).isEqualTo("PENDING_ACCEPTANCE");assertThat(data(close(id,token,"核实原因","1")).path("status").asString()).isEqualTo("CLOSED");
    }
    @ParameterizedTest @ValueSource(strings={"STUDENT","MERCHANT","ANONYMOUS","DISABLED","REVOKED","EXPIRED"})
    void bothEndpointsRequireActiveAdministratorSession(String kind)throws Exception {
        var f=fixture();var id=prepared(f,"PENDING_ACCEPTANCE");String token=null;int status=403;String code="ROLE_FORBIDDEN";
        if(kind.equals("ANONYMOUS")){status=401;code="AUTH_REQUIRED";}
        else{var a=user(Set.of("STUDENT","MERCHANT").contains(kind)?kind:"ADMIN");token=login(a);
            if(kind.equals("DISABLED")){jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?",a.id());code="ACCOUNT_DISABLED";}
            if(kind.equals("REVOKED")){jdbc.update("UPDATE auth_session SET revoked_at=? WHERE account_id=?",LocalDateTime.ofInstant(NOW,ZoneOffset.UTC),a.id());status=401;code="SESSION_REVOKED";}
            if(kind.equals("EXPIRED")){fixed(NOW.plusSeconds(7200));status=401;code="SESSION_EXPIRED";}}
        expect(call("GET","/api/v1/admin/orders/"+id,null,token,null),status,code);expect(close(id,token,"核实原因","1"),status,code);assertThat(count("refund_record")).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"0","01","-1","9223372036854775808","1x"})
    void malformedIdsRejected(String id)throws Exception{var token=login(user("ADMIN"));expect(call("GET","/api/v1/admin/orders/"+id,null,token,null),400,"VALIDATION_FAILED");expect(close(id,token,"核实原因","0"),400,"VALIDATION_FAILED");}
    @ParameterizedTest @ValueSource(strings={"{}","null","{\"reason\":\"x\"}","{\"reason\":\"x\",\"expectedVersion\":1}","{\"reason\":\"x\",\"expectedVersion\":\"01\"}","{\"reason\":\"x\",\"expectedVersion\":\"9223372036854775808\"}","{\"reason\":null,\"expectedVersion\":\"1\"}","{\"reason\":\"  \",\"expectedVersion\":\"1\"}","{\"reason\":3,\"expectedVersion\":\"1\"}","{\"reason\":\"x\",\"expectedVersion\":\"1\",\"refundAmount\":1}"})
    void malformedBodiesNeverRefund(String raw)throws Exception {
        var f=fixture();var id=prepared(f,"PENDING_ACCEPTANCE");var token=login(user("ADMIN"));var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+env.getRequiredProperty("local.server.port")+"/api/v1/admin/orders/"+id+"/close-exception")).header("Authorization","Bearer "+token).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(raw)).build();expect(http.send(request,HttpResponse.BodyHandlers.ofString()),400,"VALIDATION_FAILED");assertThat(count("refund_record")).isZero();
    }
    @Test void reasonUnicodeBoundaryAndVersionCeiling()throws Exception {
        var f=fixture();var id=prepared(f,"PENDING_ACCEPTANCE");var token=login(user("ADMIN"));expect(close(id,token,"😀".repeat(301),"1"),400,"VALIDATION_FAILED");jdbc.update("UPDATE service_order SET version=? WHERE id=?",Long.MAX_VALUE,id);expect(close(id,token,"核实原因",Long.toString(Long.MAX_VALUE)),409,"ORDER_STATE_CONFLICT");assertThat(count("refund_record")).isZero();jdbc.update("UPDATE service_order SET version=1 WHERE id=?",id);assertThat(data(close(id,token,"😀".repeat(300),"1")).path("closeNote").asString()).isEqualTo("😀".repeat(300));
    }
    @Test void missingAndIncompletePrintResourcesAreHidden()throws Exception {
        var f=fixture();var id=prepared(f,"PENDING_ACCEPTANCE");var token=login(user("ADMIN"));jdbc.update("UPDATE service_order SET order_type='PRINT' WHERE id=?",id);
        for(String value:List.of(id,"9223372036854775807")){expect(call("GET","/api/v1/admin/orders/"+value,null,token,null),404,"RESOURCE_NOT_FOUND");expect(close(value,token,"核实原因","1"),404,"RESOURCE_NOT_FOUND");}assertThat(count("refund_record")).isZero();
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void administratorMayCloseBeforeOrdinaryWindowOrAfterMissedAppointment(boolean missed)throws Exception {
        var f=fixture();var id=prepared(f,"ACCEPTED");if(missed){fixed(START.plusSeconds(1800));}var token=login(user("ADMIN"));assertThat(data(close(id,token,"核实原因","2")).path("booking").path("occupancyStatus").asString()).isEqualTo("RELEASED");
    }
    @Test void readOnlyDetailDoesNotCloseOverdueOrderAndAdministratorCanWinBeforeScanner()throws Exception {
        var f=fixture();var id=prepared(f,"PENDING_ACCEPTANCE");fixed(NOW.plusSeconds(1800));var admin=user("ADMIN");var token=login(admin);assertThat(detail(id,token).path("status").asString()).isEqualTo("PENDING_ACCEPTANCE");assertThat(count("refund_record")).isZero();data(close(id,token,"核实原因","1"));assertThat(transactions.expire(Long.parseLong(id))).isFalse();oneRefund(id,"RELEASED",2,admin.id());
    }
    @Test void multiSessionExactRetriesProduceOneRefundAndEvent()throws Exception {
        var f=fixture();var id=prepared(f,"PENDING_ACCEPTANCE");var admin=user("ADMIN");var tokens=List.of(login(admin),login(admin));var gate=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){var list=new ArrayList<Future<HttpResponse<String>>>();for(int i=0;i<8;i++){int n=i;list.add(pool.submit(()->{gate.await();return close(id,tokens.get(n%2),"核实原因","1");}));}gate.countDown();JsonNode first=null;for(var future:list){var result=data(future.get(20,TimeUnit.SECONDS));if(first==null){first=result;}else{assertThat(result).isEqualTo(first);}}}oneRefund(id,"RELEASED",2,admin.id());assertThat(count("order_event")).isEqualTo(3);
    }
    @Test void competingAdministratorsCannotOverwriteFirstAudit()throws Exception {
        var f=fixture();var id=prepared(f,"PENDING_ACCEPTANCE");var a=user("ADMIN");var b=user("ADMIN");var ta=login(a);var tb=login(b);var gate=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){var one=pool.submit(()->{gate.await();return close(id,ta,"核实原因","1");});var two=pool.submit(()->{gate.await();return close(id,tb,"核实原因","1");});gate.countDown();var x=one.get(20,TimeUnit.SECONDS);var y=two.get(20,TimeUnit.SECONDS);assertThat(List.of(x.statusCode(),y.statusCode())).containsExactlyInAnyOrder(200,409);oneRefund(id,"RELEASED",2,x.statusCode()==200?a.id():b.id());}assertThat(count("order_event")).isEqualTo(3);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void stateChangeWhileWaitingRequiresFreshVersion(boolean complete)throws Exception {
        var f=fixture();var id=prepared(f,complete?"IN_SERVICE":"ACCEPTED");fixed(START);var admin=login(user("ADMIN"));var version=detail(id,admin).path("version").asString();var merchant=login(f.owner());var gate=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var holder=pool.submit(()->{new TransactionTemplate(manager).executeWithoutResult(s->{schedules.lockGuard(f.barber(),DATE);gate.countDown();await(release);if(complete){orders.completeService(Long.parseLong(id),3,LocalDateTime.ofInstant(START,ZoneOffset.UTC));bookings.consumeActive(Long.parseLong(id));events.append(Long.parseLong(id),4,"COMPLETE","IN_SERVICE","COMPLETED","MERCHANT",f.owner().id(),null,LocalDateTime.ofInstant(START,ZoneOffset.UTC));}else{orders.startService(Long.parseLong(id),2,LocalDateTime.ofInstant(START,ZoneOffset.UTC));events.append(Long.parseLong(id),3,"START","ACCEPTED","IN_SERVICE","MERCHANT",f.owner().id(),null,LocalDateTime.ofInstant(START,ZoneOffset.UTC));}});});
            await(gate);var reached=new CountDownLatch(1);var real=sqlSession.getMapper(BarberScheduleMapper.class);doAnswer(i->{reached.countDown();return real.lockGuard(i.getArgument(0),i.getArgument(1));}).when(schedules).lockGuard(anyLong(),any());var request=pool.submit(()->close(id,admin,"核实原因",version));try{await(reached);}finally{release.countDown();}holder.get(15,TimeUnit.SECONDS);expect(request.get(15,TimeUnit.SECONDS),409,"CONFIG_VERSION_CONFLICT");
        }finally{release.countDown();}assertThat(count("refund_record")).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"refund","close","release","consume","event","session"})
    void failureAfterActualSqlRollsBackEveryFact(String step)throws Exception {
        var f=fixture();var id=prepared(f,step.equals("consume")?"IN_SERVICE":"ACCEPTED");var admin=user("ADMIN");var token=login(admin);var before=detail(id,token);var realMoney=sqlSession.getMapper(MoneyMapper.class);var realOrders=sqlSession.getMapper(OrderMapper.class);var realBookings=sqlSession.getMapper(BookingMapper.class);var realEvents=sqlSession.getMapper(OrderEventMapper.class);
        if(step.equals("refund")){doAnswer(i->{realMoney.refundWithNote(i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(3),i.getArgument(4),i.getArgument(5),i.getArgument(6));throw new IllegalStateException("injected after refund");}).when(money).refundWithNote(anyLong(),anyLong(),anyString(),anyLong(),anyString(),anyString(),any());}
        if(step.equals("close")){doAnswer(i->{realOrders.adminRefundClose(i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(3),i.getArgument(4));throw new IllegalStateException("injected after close");}).when(orders).adminRefundClose(anyLong(),anyLong(),anyString(),anyString(),any());}
        if(step.equals("release")){doAnswer(i->{realBookings.releaseActive(i.getArgument(0));throw new IllegalStateException("injected after release");}).when(bookings).releaseActive(anyLong());}
        if(step.equals("consume")){doAnswer(i->{realBookings.consumeActive(i.getArgument(0));throw new IllegalStateException("injected after consume");}).when(bookings).consumeActive(anyLong());}
        if(step.equals("event")||step.equals("session")){doAnswer(i->{int n=realEvents.append(i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(3),i.getArgument(4),i.getArgument(5),i.getArgument(6),i.getArgument(7),i.getArgument(8));if(step.equals("session")){fixed(clock.instant().plusSeconds(7200));return n;}throw new IllegalStateException("injected after event");}).when(events).append(anyLong(),anyLong(),anyString(),any(),anyString(),anyString(),any(),any(),any());}
        expect(close(id,token,"核实原因",before.path("version").asString()),step.equals("session")?401:500,step.equals("session")?"SESSION_EXPIRED":"INTERNAL_ERROR");reset(orders,bookings,money,events);assertThat(count("refund_record")).isZero();var after=detail(id,login(admin));assertThat(after).isEqualTo(before);assertThat(count("order_event")).isEqualTo(Long.parseLong(before.path("version").asString())+1);
    }
    @Test void startedExceptionPreservesFutureIntervalAndConfigurationProtection()throws Exception {
        var f=fixture();jdbc.update("UPDATE service_item SET duration_minutes=240 WHERE id=?",f.service());jdbc.update("UPDATE shop_business_hour SET end_time='17:00' WHERE shop_id=?",f.shop());jdbc.update("UPDATE barber_schedule SET end_at=? WHERE barber_id=?",LocalDateTime.of(2026,10,23,9,0),f.barber());var id=prepared(f,"IN_SERVICE");var admin=login(user("ADMIN"));var merchant=login(f.owner());data(close(id,admin,"核实原因","3"));jdbc.update("UPDATE service_item SET duration_minutes=15,version=1 WHERE id=?",f.service());var overlap=START.plusSeconds(7200);var adjacent=START.plusSeconds(14400);
        assertThat(starts(f)).doesNotContain(overlap,adjacent.minusSeconds(900)).contains(adjacent);var request=body(f,overlap);request.put("expectedServiceVersion","1");expect(post(request,login(user("STUDENT")),"overlap"),409,"BOOKING_CONFLICT");
        expect(call("PUT","/api/v1/merchant/barbers/"+f.barber()+"/schedule",Map.of("date",DATE.toString(),"expectedBarberVersion","0","intervals",List.of()),merchant,null),409,"SCHEDULE_CONFLICT");expect(call("PUT","/api/v1/merchant/shop/business-hours",Map.of("expectedVersion","0","intervals",List.of()),merchant,null),409,"SCHEDULE_CONFLICT");assertThat(call("PATCH","/api/v1/merchant/barbers/"+f.barber(),Map.of("expectedVersion","0","enabled",false),merchant,null).statusCode()).isEqualTo(200);jdbc.update("UPDATE barber SET enabled=TRUE WHERE id=?",f.barber());request=body(f,adjacent);request.put("expectedServiceVersion","1");assertThat(post(request,login(user("STUDENT")),"adjacent").statusCode()).isEqualTo(201);
        expect(action(id,merchant,false),409,"ORDER_STATE_CONFLICT");expect(action(id,merchant,true),409,"ORDER_STATE_CONFLICT");assertThat(transactions.expire(Long.parseLong(id))).isFalse();assertThat(count("refund_record")).isEqualTo(1);
    }
    @Test void unstartedCloseReleasesOnlyAfterCommitAndCreatorThenSucceeds()throws Exception {
        var f=fixture();var id=prepared(f,"ACCEPTED");var token=login(user("ADMIN"));var other=login(user("STUDENT"));var reached=new CountDownLatch(1);var release=new CountDownLatch(1);var real=sqlSession.getMapper(BookingMapper.class);
        doAnswer(i->{int n=real.releaseActive(i.getArgument(0));reached.countDown();await(release);return n;}).when(bookings).releaseActive(anyLong());
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){var closing=pool.submit(()->close(id,token,"核实原因","2"));await(reached);assertThat(starts(f)).doesNotContain(START);var creating=pool.submit(()->create(f,other,"released"));release.countDown();data(closing.get(20,TimeUnit.SECONDS));assertThat(creating.get(20,TimeUnit.SECONDS).statusCode()).isEqualTo(201);}finally{release.countDown();}assertThat(count("refund_record")).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void realHttpFulfillmentAndAdministratorCompeteOnSameVersion(boolean complete)throws Exception {
        var f=fixture();var id=prepared(f,complete?"IN_SERVICE":"ACCEPTED");fixed(START);
        var admin=login(user("ADMIN"));var merchant=login(f.owner());var version=detail(id,admin).path("version").asString();var gate=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var closing=pool.submit(()->{gate.await();return close(id,admin,"核实原因",version);});
            var fulfilling=pool.submit(()->{gate.await();return action(id,merchant,complete);});gate.countDown();
            var a=closing.get(20,TimeUnit.SECONDS);var b=fulfilling.get(20,TimeUnit.SECONDS);
            if(a.statusCode()==200){expect(b,409,"ORDER_STATE_CONFLICT");assertThat(count("refund_record")).isEqualTo(1);assertThat(detail(id,admin).path("booking").path("occupancyStatus").asString()).isEqualTo(complete?"CONSUMED":"RELEASED");}
            else{expect(a,409,"CONFIG_VERSION_CONFLICT");assertThat(b.statusCode()).isEqualTo(200);assertThat(count("refund_record")).isZero();}
            assertThat(count("order_event")).isEqualTo(Long.parseLong(version)+2);
        }
    }
    @Test void studentCancellationAndAdministratorKeepOneRefundAndWinningReason()throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var id=paid(f,student);var admin=login(user("ADMIN"));var gate=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var one=pool.submit(()->{gate.await();return close(id,admin,"核实原因","1");});var two=pool.submit(()->{gate.await();return call("POST","/api/v1/orders/"+id+"/cancel",null,student,null);});gate.countDown();
            var a=one.get(20,TimeUnit.SECONDS);var b=two.get(20,TimeUnit.SECONDS);assertThat(List.of(a.statusCode(),b.statusCode())).containsExactlyInAnyOrder(200,409);
            assertThat(detail(id,admin).path("closeReason").asString()).isEqualTo(a.statusCode()==200?"ADMIN_EXCEPTION":"USER_CANCEL");assertThat(count("refund_record")).isEqualTo(1);assertThat(count("order_event")).isEqualTo(3);
        }
    }
    @Test void realExpiryAndAdministratorRacePreservesSingleClosingCause()throws Exception {
        var f=fixture();var id=prepared(f,"PENDING_ACCEPTANCE");fixed(NOW.plusSeconds(1800));var admin=login(user("ADMIN"));var gate=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var one=pool.submit(()->{gate.await();return close(id,admin,"核实原因","1");});var two=pool.submit(()->{gate.await();return transactions.expire(Long.parseLong(id));});gate.countDown();
            var a=one.get(20,TimeUnit.SECONDS);boolean expired=two.get(20,TimeUnit.SECONDS);
            if(expired){expect(a,409,"ORDER_STATE_CONFLICT");}else{assertThat(a.statusCode()).isEqualTo(200);}
            assertThat(detail(id,admin).path("closeReason").asString()).isEqualTo(expired?"ACCEPTANCE_TIMEOUT":"ADMIN_EXCEPTION");assertThat(count("refund_record")).isEqualTo(1);assertThat(count("order_event")).isEqualTo(3);
        }
    }
    @Test void administratorDisablingPreventsReplayingOwnRefund()throws Exception {
        var f=fixture();var id=prepared(f,"PENDING_ACCEPTANCE");var admin=user("ADMIN");var token=login(admin);data(close(id,token,"核实原因","1"));jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?",admin.id());expect(close(id,token,"核实原因","1"),403,"ACCOUNT_DISABLED");assertThat(count("refund_record")).isEqualTo(1);assertThat(count("order_event")).isEqualTo(3);
    }
    @Test void administratorRealMysqlLockTimeoutRollsBackAndOriginalRequestCanRetry()throws Exception {
        var f=fixture();var id=prepared(f,"PENDING_ACCEPTANCE");var token=login(user("ADMIN"));var real=sqlSession.getMapper(OrderMapper.class);var reached=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(i->{jdbc.execute("SET SESSION innodb_lock_wait_timeout=1");try{return real.shareShop(i.getArgument(0));}finally{jdbc.execute("SET SESSION innodb_lock_wait_timeout=50");}}).when(orders).shareShop(f.shop());
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var holder=pool.submit(()->new TransactionTemplate(manager).executeWithoutResult(tx->{shops.lock(f.shop());reached.countDown();await(release);}));await(reached);
            expect(close(id,token,"核实原因","1"),503,"SERVICE_TEMPORARILY_UNAVAILABLE");assertThat(count("refund_record")).isZero();assertThat(count("order_event")).isEqualTo(2);release.countDown();holder.get(10,TimeUnit.SECONDS);reset(orders);assertThat(close(id,token,"核实原因","1").statusCode()).isEqualTo(200);
        }finally{release.countDown();}
    }
    @Test void consumingRefundCommitKeepsCandidatesAndConcurrentCreationBlocked()throws Exception {
        var f=fixture();jdbc.update("UPDATE service_item SET duration_minutes=240 WHERE id=?",f.service());jdbc.update("UPDATE shop_business_hour SET end_time='17:00' WHERE shop_id=?",f.shop());jdbc.update("UPDATE barber_schedule SET end_at=? WHERE barber_id=?",LocalDateTime.of(2026,10,23,9,0),f.barber());var id=prepared(f,"IN_SERVICE");jdbc.update("UPDATE service_item SET duration_minutes=15,version=1 WHERE id=?",f.service());
        var admin=login(user("ADMIN"));var other=login(user("STUDENT"));var overlap=START.plusSeconds(7200);var request=body(f,overlap);request.put("expectedServiceVersion","1");var reached=new CountDownLatch(1);var release=new CountDownLatch(1);var real=sqlSession.getMapper(BookingMapper.class);
        doAnswer(i->{int n=real.consumeActive(i.getArgument(0));reached.countDown();await(release);return n;}).when(bookings).consumeActive(Long.parseLong(id));
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var closing=pool.submit(()->close(id,admin,"核实原因","3"));await(reached);assertThat(starts(f)).doesNotContain(overlap);var creating=pool.submit(()->post(request,other,"consume-race"));Thread.sleep(200);assertThat(creating.isDone()).isFalse();release.countDown();data(closing.get(15,TimeUnit.SECONDS));expect(creating.get(15,TimeUnit.SECONDS),409,"BOOKING_CONFLICT");assertThat(starts(f)).doesNotContain(overlap);assertThat(count("refund_record")).isEqualTo(1);
        }finally{release.countDown();}
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
