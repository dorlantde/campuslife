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
class MerchantBookingFulfillmentMysqlIT {
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
    private String accepted(Fixture f,String student,String merchant)throws Exception {enable();var id=data(create(f,student,"order")).path("id").asString();data(call("POST","/api/v1/orders/"+id+"/payments/mock",null,student,null));data(call("POST","/api/v1/merchant/orders/"+id+"/accept",null,merchant,null));return id;}
    private HttpResponse<String> action(String id,String token,boolean complete)throws Exception{return call("POST","/api/v1/merchant/orders/"+id+(complete?"/complete":"/start"),null,token,null);}
    private JsonNode detail(String id,String token)throws Exception{return data(call("GET","/api/v1/merchant/orders/"+id,null,token,null));}
    @Test void normalLifecycleKeepsMoneyAndSnapshotsAndWritesExactlyOneFactForEachAction()throws Exception {
        var f=fixture();var studentUser=user("STUDENT");var student=login(studentUser);var merchant=login(f.owner());var id=accepted(f,student,merchant);var before=detail(id,merchant);fixed(START);merchant=login(f.owner());doReturn(false).when(mockPayment).enabled();
        var started=data(action(id,merchant,false));assertThat(started.path("status").asString()).isEqualTo("IN_SERVICE");assertThat(started.path("startedAt").asString()).isEqualTo(START.toString());assertThat(started.path("version").asString()).isEqualTo("3");assertThat(started.path("booking").path("occupancyStatus").asString()).isEqualTo("ACTIVE");assertThat(data(action(id,merchant,false))).isEqualTo(started);
        fixed(START.plusSeconds(60));var complete=data(action(id,merchant,true));assertThat(complete.path("status").asString()).isEqualTo("COMPLETED");assertThat(complete.path("completedAt").asString()).isEqualTo(START.plusSeconds(60).toString());assertThat(complete.path("version").asString()).isEqualTo("4");assertThat(complete.path("booking").path("occupancyStatus").asString()).isEqualTo("CONSUMED");assertThat(complete.path("payment")).isEqualTo(before.path("payment"));assertThat(complete.path("paymentStatus").asString()).isEqualTo("PAID");assertThat(complete.path("refund").isNull()).isTrue();assertThat(complete.path("reviewed").asBoolean()).isFalse();assertThat(complete.path("booking").path("endAt")).isEqualTo(before.path("booking").path("endAt"));
        assertThat(data(action(id,merchant,true))).isEqualTo(complete);assertThat(data(action(id,merchant,false))).isEqualTo(complete);assertThat(data(call("GET","/api/v1/orders/"+id,null,login(studentUser),null))).isEqualTo(complete);assertThat(count("order_event")).isEqualTo(5);assertThat(count("payment_record")).isEqualTo(1);assertThat(count("refund_record")).isZero();
        assertThat(jdbc.queryForList("SELECT action FROM order_event WHERE order_id=? ORDER BY event_seq",String.class,id)).containsExactly("CREATE","PAYMENT","ACCEPT","START","COMPLETE");assertThat(jdbc.queryForObject("SELECT actor_id FROM order_event WHERE order_id=? AND event_seq=4",Long.class,id)).isEqualTo(f.owner().id());assertThat(jdbc.queryForObject("SELECT version FROM shop WHERE id=?",Long.class,f.shop())).isZero();assertThat(jdbc.queryForObject("SELECT version FROM barber WHERE id=?",Long.class,f.barber())).isZero();
    }
    @ParameterizedTest @ValueSource(longs={-1,0,1799999,1800000,1800001})
    void startUsesInclusiveStartAndExclusiveEnd(long offset)throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var merchant=login(f.owner());var id=accepted(f,student,merchant);fixed(START.plusMillis(offset));merchant=login(f.owner());var response=action(id,merchant,false);
        if(offset>=0&&offset<1800000){assertThat(data(response).path("startedAt").asString()).isEqualTo(START.plusMillis(offset).toString());assertThat(count("order_event")).isEqualTo(4);}
        else{expect(response,409,"BOOKING_TIME_INVALID");assertThat(detail(id,merchant).path("status").asString()).isEqualTo("ACCEPTED");assertThat(count("order_event")).isEqualTo(3);assertThat(count("refund_record")).isZero();}
    }
    @ParameterizedTest @ValueSource(longs={-1,0,60000,1800000,7200000})
    void completionCanBeImmediateEarlyOrLateButNeverBeforeActualStart(long elapsed)throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var merchant=login(f.owner());var id=accepted(f,student,merchant);fixed(START);merchant=login(f.owner());data(action(id,merchant,false));fixed(START.plusMillis(elapsed));merchant=login(f.owner());
        if(elapsed<0){expect(action(id,merchant,true),409,"BOOKING_TIME_INVALID");assertThat(detail(id,merchant).path("status").asString()).isEqualTo("IN_SERVICE");assertThat(count("order_event")).isEqualTo(4);}
        else{var done=data(action(id,merchant,true));assertThat(done.path("completedAt").asString()).isEqualTo(START.plusMillis(elapsed).toString());assertThat(done.path("booking").path("occupancyStatus").asString()).isEqualTo("CONSUMED");assertThat(count("order_event")).isEqualTo(5);}
    }
    @Test void replayAfterWindowAndWithDisabledFundsNeverMovesOriginalTimes()throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var merchant=login(f.owner());var id=accepted(f,student,merchant);fixed(START);merchant=login(f.owner());var first=data(action(id,merchant,false));fixed(START.plusSeconds(3600));merchant=login(f.owner());doReturn(false).when(mockPayment).enabled();assertThat(data(action(id,merchant,false))).isEqualTo(first);var done=data(action(id,merchant,true));fixed(START.plusSeconds(86400));merchant=login(f.owner());assertThat(data(action(id,merchant,true))).isEqualTo(done);assertThat(data(action(id,merchant,false))).isEqualTo(done);assertThat(count("order_event")).isEqualTo(5);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void roleOwnershipTypeAndMemberChecksApplyToBothActions(boolean complete)throws Exception {
        var f=fixture();var studentUser=user("STUDENT");var student=login(studentUser);var merchant=login(f.owner());var id=accepted(f,student,merchant);fixed(START);merchant=login(f.owner());if(complete){data(action(id,merchant,false));}student=login(studentUser);var other=fixture();
        expect(action(id,null,complete),401,"AUTH_REQUIRED");expect(action(id,student,complete),403,"ROLE_FORBIDDEN");expect(action(id,login(user("ADMIN")),complete),403,"ROLE_FORBIDDEN");expect(action(id,login(other.owner()),complete),404,"RESOURCE_NOT_FOUND");expect(action("01",merchant,complete),400,"VALIDATION_FAILED");expect(action("9223372036854775807",merchant,complete),404,"RESOURCE_NOT_FOUND");jdbc.update("UPDATE shop_member SET status='DISABLED' WHERE shop_id=?",f.shop());expect(action(id,merchant,complete),403,"ROLE_FORBIDDEN");assertThat(count("refund_record")).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"SUSPENDED","REJECTED","PENDING"})
    void pausedOldOrdersCanStartAndCompleteWithoutMoneyGateOrRepricing(String status)throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var merchant=login(f.owner());var id=accepted(f,student,merchant);jdbc.update("UPDATE shop SET approval_status=?,business_enabled=FALSE WHERE id=?",status,f.shop());jdbc.update("UPDATE service_item SET enabled=FALSE,price_amount=8000,duration_minutes=60 WHERE id=?",f.service());jdbc.update("UPDATE shop_category SET enabled=FALSE WHERE code='HAIRCUT'");fixed(START);merchant=login(f.owner());doReturn(false).when(mockPayment).enabled();
        try{data(action(id,merchant,false));var done=data(action(id,merchant,true));assertThat(done.path("payableAmount").asLong()).isEqualTo(3000);assertThat(done.path("booking").path("durationMinutes").asInt()).isEqualTo(30);assertThat(count("payment_record")).isEqualTo(1);assertThat(count("refund_record")).isZero();}
        finally{jdbc.update("UPDATE shop_category SET enabled=TRUE WHERE code='HAIRCUT'");}
    }
    @ParameterizedTest @ValueSource(strings={"start:PENDING_PAYMENT","start:PENDING_ACCEPTANCE","start:CLOSED","start:PRINT","complete:ACCEPTED","complete:PENDING_PAYMENT","complete:PENDING_ACCEPTANCE","complete:CLOSED","complete:PRINT"})
    void invalidStatesCannotSkipLifecycle(String mode)throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var merchant=login(f.owner());var id=accepted(f,student,merchant);boolean complete=mode.startsWith("complete:");var state=mode.split(":")[1];if(state.equals("PRINT")){jdbc.update("UPDATE service_order SET order_type='PRINT' WHERE id=?",id);}else{jdbc.update("UPDATE service_order SET status=? WHERE id=?",state,id);}fixed(START);merchant=login(f.owner());expect(action(id,merchant,complete),state.equals("PRINT")?404:409,state.equals("PRINT")?"RESOURCE_NOT_FOUND":"ORDER_STATE_CONFLICT");assertThat(count("order_event")).isEqualTo(3);assertThat(count("refund_record")).isZero();
    }
    @ParameterizedTest @CsvSource({"false,{}","false,null","true,{}","true,null","false,{\"time\":1}","true,{\"status\":1}"})
    void bothEndpointsRejectBusinessBodies(boolean complete,String body)throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var merchant=login(f.owner());var id=accepted(f,student,merchant);fixed(START);merchant=login(f.owner());if(complete){data(action(id,merchant,false));}var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+env.getRequiredProperty("local.server.port")+"/api/v1/merchant/orders/"+id+(complete?"/complete":"/start"))).header("Authorization","Bearer "+merchant).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();expect(http.send(request,HttpResponse.BodyHandlers.ofString()),400,"VALIDATION_FAILED");assertThat(count("order_event")).isEqualTo(complete?4:3);
    }
    @Test void completedOrderCannotBeCancelledRejectedOrPaidAgainAndExistingPaymentCanReplay()throws Exception {
        var f=fixture();var u=user("STUDENT");var student=login(u);var merchant=login(f.owner());var id=accepted(f,student,merchant);fixed(START);merchant=login(f.owner());data(action(id,merchant,false));var done=data(action(id,merchant,true));student=login(u);expect(call("POST","/api/v1/orders/"+id+"/cancel",null,student,null),409,"ORDER_STATE_CONFLICT");expect(call("POST","/api/v1/merchant/orders/"+id+"/cancel",Map.of("reason","原因"),merchant,null),409,"ORDER_STATE_CONFLICT");expect(call("POST","/api/v1/merchant/orders/"+id+"/reject",Map.of("reason","原因"),merchant,null),409,"ORDER_STATE_CONFLICT");assertThat(data(call("POST","/api/v1/orders/"+id+"/payments/mock",null,student,null)).path("orderStatus").asString()).isEqualTo("COMPLETED");assertThat(data(call("POST","/api/v1/merchant/orders/"+id+"/accept",null,merchant,null))).isEqualTo(done);assertThat(count("order_event")).isEqualTo(5);assertThat(count("payment_record")).isEqualTo(1);assertThat(count("refund_record")).isZero();assertThat(expiry.scan()).isZero();
    }
    @Test void earlyCompletionPreservesFutureConsumedIntervalInCandidatesCreationAndScheduleProtection()throws Exception {
        var f=fixture();jdbc.update("UPDATE service_item SET duration_minutes=240 WHERE id=?",f.service());jdbc.update("UPDATE shop_business_hour SET end_time='17:00' WHERE shop_id=?",f.shop());jdbc.update("UPDATE barber_schedule SET end_at=? WHERE barber_id=?",LocalDateTime.of(2026,10,23,9,0),f.barber());var student=login(user("STUDENT"));var merchant=login(f.owner());var id=accepted(f,student,merchant);fixed(START);merchant=login(f.owner());data(action(id,merchant,false));data(action(id,merchant,true));jdbc.update("UPDATE service_item SET duration_minutes=15,version=1 WHERE id=?",f.service());
        var overlap=START.plusSeconds(7200);var adjacent=START.plusSeconds(14400);assertThat(starts(f)).doesNotContain(overlap,overlap.plusSeconds(900),adjacent.minusSeconds(900)).contains(adjacent);
        var other=login(user("STUDENT"));var b=body(f,overlap);b.put("expectedServiceVersion","1");expect(post(b,other,"consumed"),409,"BOOKING_CONFLICT");assertThat(expiry.scan()).isZero();assertThat(transactions.expire(Long.parseLong(id))).isFalse();
        expect(call("PUT","/api/v1/merchant/barbers/"+f.barber()+"/schedule",Map.of("date",DATE.toString(),"expectedBarberVersion","0","intervals",List.of()),merchant,null),409,"SCHEDULE_CONFLICT");
        var weekdays=List.of(Map.of("weekday",DATE.getDayOfWeek().getValue(),"startTime","09:00","endTime","17:00"));expect(call("PUT","/api/v1/merchant/shop/business-hours",Map.of("expectedVersion","0","intervals",List.of()),merchant,null),409,"SCHEDULE_CONFLICT");
        assertThat(call("PATCH","/api/v1/merchant/barbers/"+f.barber(),Map.of("expectedVersion","0","enabled",false),merchant,null).statusCode()).isEqualTo(200);var a=body(f,adjacent);a.put("expectedServiceVersion","1");jdbc.update("UPDATE barber SET enabled=TRUE WHERE id=?",f.barber());assertThat(post(a,other,"adjacent").statusCode()).isEqualTo(201);assertThat(count("refund_record")).isZero();
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void concurrentRepeatedActionsOnlyWriteOneTimeVersionAndEvent(boolean complete)throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var merchant=login(f.owner());var id=accepted(f,student,merchant);fixed(START);var tokens=List.of(login(f.owner()),login(f.owner()));if(complete){data(action(id,tokens.getFirst(),false));}var gate=new CountDownLatch(1);var results=new ArrayList<Future<HttpResponse<String>>>();
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){for(int i=0;i<10;i++){int n=i;results.add(pool.submit(()->{gate.await();return action(id,tokens.get(n%2),complete);}));}gate.countDown();JsonNode first=null;for(var item:results){var d=data(item.get(20,TimeUnit.SECONDS));if(first==null){first=d;}else{assertThat(d).isEqualTo(first);}}}assertThat(count("order_event")).isEqualTo(complete?5:4);assertThat(count("refund_record")).isZero();
    }
    @Test void concurrentStartAndCompleteCannotCompleteWithoutStart()throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var merchant=login(f.owner());var id=accepted(f,student,merchant);fixed(START);var token=login(f.owner());var gate=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){var a=pool.submit(()->{gate.await();return action(id,token,false);});var b=pool.submit(()->{gate.await();return action(id,token,true);});gate.countDown();assertThat(a.get(20,TimeUnit.SECONDS).statusCode()).isEqualTo(200);var result=b.get(20,TimeUnit.SECONDS);assertThat(result.statusCode()).isIn(200,409);if(result.statusCode()==409){expect(result,409,"ORDER_STATE_CONFLICT");data(action(id,token,true));}}assertThat(detail(id,token).path("status").asString()).isEqualTo("COMPLETED");assertThat(count("order_event")).isEqualTo(5);
    }
    @Test void cancellationAtStartCannotRaceAServiceIntoClosedState()throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var merchant=login(f.owner());var id=accepted(f,student,merchant);fixed(START);var token=login(f.owner());var gate=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){var a=pool.submit(()->{gate.await();return action(id,token,false);});var b=pool.submit(()->{gate.await();return call("POST","/api/v1/merchant/orders/"+id+"/cancel",Map.of("reason","原因"),token,null);});gate.countDown();assertThat(a.get(20,TimeUnit.SECONDS).statusCode()).isEqualTo(200);var result=b.get(20,TimeUnit.SECONDS);assertThat(result.statusCode()).isEqualTo(409);assertThat(json.readTree(result.body()).path("code").asString()).isIn("CANCEL_WINDOW_CLOSED","ORDER_STATE_CONFLICT");}assertThat(count("refund_record")).isZero();assertThat(count("order_event")).isEqualTo(4);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void startWaitsForDateLockAndRechecksBothWindowBoundaries(boolean end)throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var merchant=login(f.owner());var id=accepted(f,student,merchant);fixed(end?START.plusSeconds(1799):START.minusMillis(1));var token=login(f.owner());var reached=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){var holder=pool.submit(()->new TransactionTemplate(manager).executeWithoutResult(tx->{schedules.lockGuard(f.barber(),DATE);reached.countDown();await(release);}));assertThat(reached.await(10,TimeUnit.SECONDS)).isTrue();var starting=pool.submit(()->action(id,token,false));Thread.sleep(200);assertThat(starting.isDone()).isFalse();fixed(end?START.plusSeconds(1800):START);release.countDown();holder.get(10,TimeUnit.SECONDS);var response=starting.get(10,TimeUnit.SECONDS);if(end){expect(response,409,"BOOKING_TIME_INVALID");}else{assertThat(data(response).path("startedAt").asString()).isEqualTo(START.toString());}}
        finally{release.countDown();}
    }
    @ParameterizedTest @ValueSource(strings={"startOrder","startEvent","completeOrder","consume","completeEvent"})
    void partialWriteFailureRollsBackFulfillmentAndOccupancy(String stage)throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var merchant=login(f.owner());var id=accepted(f,student,merchant);fixed(START);merchant=login(f.owner());boolean complete=!stage.startsWith("start");if(complete){data(action(id,merchant,false));}
        switch(stage){case "startOrder"->doThrow(new IllegalStateException("injected")).when(orders).startService(anyLong(),anyLong(),any());case "completeOrder"->doThrow(new IllegalStateException("injected")).when(orders).completeService(anyLong(),anyLong(),any());case "consume"->doThrow(new IllegalStateException("injected")).when(bookings).consumeActive(anyLong());default->doThrow(new IllegalStateException("injected")).when(events).append(anyLong(),eq(complete?4L:3L),eq(complete?"COMPLETE":"START"),anyString(),anyString(),anyString(),anyLong(),isNull(),any());}
        expect(action(id,merchant,complete),500,"INTERNAL_ERROR");var d=detail(id,merchant);assertThat(d.path("status").asString()).isEqualTo(complete?"IN_SERVICE":"ACCEPTED");assertThat(d.path("booking").path("occupancyStatus").asString()).isEqualTo("ACTIVE");assertThat(d.path(complete?"completedAt":"startedAt").isNull()).isTrue();assertThat(count("order_event")).isEqualTo(complete?4:3);reset(orders,bookings,events);assertThat(action(id,merchant,complete).statusCode()).isEqualTo(200);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void finalSessionExpiryRollsBackActualOrderUpdate(boolean complete)throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var merchant=login(f.owner());var id=accepted(f,student,merchant);fixed(START);merchant=login(f.owner());if(complete){data(action(id,merchant,false));}var real=sqlSession.getMapper(OrderMapper.class);
        if(complete){doAnswer(i->{int n=real.completeService(i.getArgument(0),i.getArgument(1),i.getArgument(2));fixed(START.plusSeconds(7200));return n;}).when(orders).completeService(anyLong(),anyLong(),any());}
        else{doAnswer(i->{int n=real.startService(i.getArgument(0),i.getArgument(1),i.getArgument(2));fixed(START.plusSeconds(7200));return n;}).when(orders).startService(anyLong(),anyLong(),any());}
        expect(action(id,merchant,complete),401,"SESSION_EXPIRED");assertThat(jdbc.queryForObject("SELECT status FROM service_order WHERE id=?",String.class,id)).isEqualTo(complete?"IN_SERVICE":"ACCEPTED");assertThat(jdbc.queryForObject("SELECT occupancy_status FROM booking_detail WHERE order_id=?",String.class,id)).isEqualTo("ACTIVE");assertThat(count("order_event")).isEqualTo(complete?4:3);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void versionExhaustionLeavesNoNewTimeOrEvent(boolean complete)throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var merchant=login(f.owner());var id=accepted(f,student,merchant);fixed(START);merchant=login(f.owner());if(complete){data(action(id,merchant,false));}jdbc.update("UPDATE service_order SET version=? WHERE id=?",Long.MAX_VALUE,id);expect(action(id,merchant,complete),409,"ORDER_STATE_CONFLICT");assertThat(count("order_event")).isEqualTo(complete?4:3);
    }
    @Test void ownerDisabledWhileWaitingForShopCannotStart()throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var merchant=login(f.owner());var id=accepted(f,student,merchant);fixed(START);var token=login(f.owner());var reached=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){var holder=pool.submit(()->new TransactionTemplate(manager).executeWithoutResult(tx->{shops.lock(f.shop());jdbc.update("UPDATE shop_member SET status='DISABLED' WHERE shop_id=?",f.shop());reached.countDown();await(release);}));assertThat(reached.await(10,TimeUnit.SECONDS)).isTrue();var starting=pool.submit(()->action(id,token,false));Thread.sleep(200);assertThat(starting.isDone()).isFalse();release.countDown();holder.get(10,TimeUnit.SECONDS);expect(starting.get(10,TimeUnit.SECONDS),403,"ROLE_FORBIDDEN");}
        finally{release.countDown();}
    }
    @Test void realMysqlLockTimeoutRollsBackAndSameActionCanRetry()throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var merchant=login(f.owner());var id=accepted(f,student,merchant);fixed(START);var token=login(f.owner());var real=sqlSession.getMapper(OrderMapper.class);var reached=new CountDownLatch(1);var release=new CountDownLatch(1);doAnswer(i->{jdbc.execute("SET SESSION innodb_lock_wait_timeout=1");try{return real.shareShop(i.getArgument(0));}finally{jdbc.execute("SET SESSION innodb_lock_wait_timeout=50");}}).when(orders).shareShop(f.shop());
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){var holder=pool.submit(()->new TransactionTemplate(manager).executeWithoutResult(tx->{shops.lock(f.shop());reached.countDown();await(release);}));assertThat(reached.await(10,TimeUnit.SECONDS)).isTrue();expect(action(id,token,false),503,"SERVICE_TEMPORARILY_UNAVAILABLE");assertThat(count("order_event")).isEqualTo(3);release.countDown();holder.get(10,TimeUnit.SECONDS);reset(orders);assertThat(action(id,token,false).statusCode()).isEqualTo(200);}
        finally{release.countDown();}
    }
    @Test void completionCommitNeverOpensAGapForConcurrentCandidateReadOrNewCreation()throws Exception {
        var f=fixture();jdbc.update("UPDATE service_item SET duration_minutes=240 WHERE id=?",f.service());jdbc.update("UPDATE shop_business_hour SET end_time='17:00' WHERE shop_id=?",f.shop());jdbc.update("UPDATE barber_schedule SET end_at=? WHERE barber_id=?",LocalDateTime.of(2026,10,23,9,0),f.barber());var student=login(user("STUDENT"));var merchant=login(f.owner());var id=accepted(f,student,merchant);fixed(START);var token=login(f.owner());data(action(id,token,false));jdbc.update("UPDATE service_item SET duration_minutes=15,version=1 WHERE id=?",f.service());var other=login(user("STUDENT"));var overlap=START.plusSeconds(7200);var request=body(f,overlap);request.put("expectedServiceVersion","1");var reached=new CountDownLatch(1);var release=new CountDownLatch(1);var real=sqlSession.getMapper(BookingMapper.class);
        doAnswer(i->{int n=real.consumeActive(i.getArgument(0));reached.countDown();await(release);return n;}).when(bookings).consumeActive(Long.parseLong(id));
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){var completing=pool.submit(()->action(id,token,true));assertThat(reached.await(10,TimeUnit.SECONDS)).isTrue();assertThat(starts(f)).doesNotContain(overlap);var creating=pool.submit(()->post(request,other,"concurrent-consumed"));Thread.sleep(200);assertThat(creating.isDone()).isFalse();release.countDown();assertThat(completing.get(10,TimeUnit.SECONDS).statusCode()).isEqualTo(200);expect(creating.get(10,TimeUnit.SECONDS),409,"BOOKING_CONFLICT");assertThat(starts(f)).doesNotContain(overlap);assertThat(count("order_event")).isEqualTo(5);}
        finally{release.countDown();}
    }
    @Test void accountDisableAlsoPreventsReplayingExistingFulfillmentFacts()throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var merchant=login(f.owner());var id=accepted(f,student,merchant);fixed(START);merchant=login(f.owner());data(action(id,merchant,false));data(action(id,merchant,true));jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?",f.owner().id());expect(action(id,merchant,false),403,"ACCOUNT_DISABLED");expect(action(id,merchant,true),403,"ACCOUNT_DISABLED");assertThat(count("order_event")).isEqualTo(5);
    }
    @Test void startDecisionTimeStaysFixedWhenActualWriteCrossesTheEnd()throws Exception {
        var f=fixture();var student=login(user("STUDENT"));var merchant=login(f.owner());var id=accepted(f,student,merchant);fixed(START.plusMillis(1799999));merchant=login(f.owner());var real=sqlSession.getMapper(OrderMapper.class);
        doAnswer(i->{int n=real.startService(i.getArgument(0),i.getArgument(1),i.getArgument(2));fixed(START.plusSeconds(1801));return n;}).when(orders).startService(anyLong(),anyLong(),any());var d=data(action(id,merchant,false));assertThat(d.path("startedAt").asString()).isEqualTo(START.plusMillis(1799999).toString());assertThat(d.path("status").asString()).isEqualTo("IN_SERVICE");assertThat(count("order_event")).isEqualTo(4);
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
