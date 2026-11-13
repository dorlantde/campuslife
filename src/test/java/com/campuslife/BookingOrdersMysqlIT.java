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
class BookingOrdersMysqlIT {
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
    @BeforeAll void prepare() { hash=passwords.encode("Booking_Test123!"); }
    @BeforeEach void freeze() { fixed(NOW); }
    @AfterEach void cleanup() {
        reset(clock,orders,bookings,events,keys,schedules,shops);
        for (long shop:shopIds) {
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
    @Test void createsSnapshotsBlocksOverlappingCandidatesAndCancelsExactlyOnce() throws Exception {
        var f=fixture();var student=user("STUDENT");String token=login(student);var original=data(create(f,token,"first"));long id=original.path("id").asLong();
        assertThat(original.path("status").asString()).isEqualTo("PENDING_PAYMENT");assertThat(original.path("paymentStatus").asString()).isEqualTo("NOT_PAID");
        assertThat(original.path("booking").path("occupancyStatus").asString()).isEqualTo("HELD");assertThat(original.path("version").asString()).isEqualTo("0");
        assertThat(original.path("paymentDeadline").asString()).isEqualTo(NOW.plusSeconds(900).toString());
        assertThat(original.path("payment").isNull()).isTrue();assertThat(original.path("refund").isNull()).isTrue();assertThat(original.path("printing").isNull()).isTrue();assertThat(original.path("reviewed").asBoolean()).isFalse();
        assertThat(starts(f)).doesNotContain(START,START.minusSeconds(900),START.plusSeconds(900)).contains(START.minusSeconds(1800),START.plusSeconds(1800));
        var replay=create(f,token,"first");assertThat(replay.statusCode()).isEqualTo(200);assertThat(replay.headers().firstValue("Idempotency-Replayed")).contains("true");assertThat(data(replay)).isEqualTo(original);
        var cancelled=data(call("POST","/api/v1/orders/"+id+"/cancel",null,token,null));assertThat(cancelled.path("closeReason").asString()).isEqualTo("USER_CANCEL");assertThat(cancelled.path("booking").path("occupancyStatus").asString()).isEqualTo("RELEASED");
        assertThat(data(call("POST","/api/v1/orders/"+id+"/cancel",null,token,null))).isEqualTo(cancelled);assertThat(count("order_event")).isEqualTo(2);assertThat(starts(f)).contains(START);
        assertThat(data(create(f,token,"first"))).isEqualTo(cancelled);assertThat(create(f,token,"second").statusCode()).isEqualTo(201);
    }
    @Test void normalizedReplaySurvivesConfigurationChangesAndTimeWindowExpiry() throws Exception {
        var f=fixture();var viewer=user("STUDENT");String token=login(viewer);var body=body(f,START);body.put("note","  模拟预约  ");var first=data(post(body,token,"same"));
        jdbc.update("UPDATE service_item SET enabled=FALSE,price_amount=5000,duration_minutes=45,version=version+1 WHERE id=?",f.service());
        jdbc.update("UPDATE shop SET business_enabled=FALSE,name='新店名' WHERE id=?",f.shop());jdbc.update("UPDATE barber SET display_name='新名字' WHERE id=?",f.barber());
        body.put("note","模拟预约");body.put("startAt","2026-10-23T09:30:00+08:00");assertThat(data(post(body,token,"same"))).isEqualTo(first);
        body.put("expectedPayableAmount",5000);expect(post(body,token,"same"),409,"IDEMPOTENCY_KEY_CONFLICT");
        var id=first.path("id").asString();assertThat(data(call("GET","/api/v1/orders/"+id,null,token,null)).path("shopName").asString()).isEqualTo("模拟预约店");
        assertThat(call("POST","/api/v1/orders/"+id+"/cancel",null,token,null).statusCode()).isEqualTo(200);
        fixed(START.plusSeconds(86400));token=login(viewer);body.put("expectedPayableAmount",3000);
        assertThat(data(post(body,token,"same")).path("id").asString()).isEqualTo(id);
        token=login(user("STUDENT")); // a different student must not replay another account's key
        expect(post(body,token,"same"),409,"SHOP_UNAVAILABLE");
    }
    @ParameterizedTest @ValueSource(strings={"same","overlap"})
    void twentyStudentsCompetingForAnIntervalOnlyCreateOneOrder(String mode) throws Exception {
        var f=fixture();var tokens=new ArrayList<String>();for(int i=0;i<20;i++){tokens.add(login(user("STUDENT")));}
        var gate=new CountDownLatch(1);var futures=new ArrayList<Future<HttpResponse<String>>>();
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            for(int i=0;i<20;i++){int n=i;futures.add(pool.submit(()->{gate.await();return post(body(f,mode.equals("overlap")&&n%2==1?START.plusSeconds(900):START),tokens.get(n),"race-"+n);}));}
            gate.countDown();int success=0;for(var future:futures){var response=future.get(45,TimeUnit.SECONDS);if(response.statusCode()==201){success++;}else{expect(response,409,"BOOKING_CONFLICT");}}
            assertThat(success).isEqualTo(1);assertThat(count("service_order")).isEqualTo(1);assertThat(count("booking_detail")).isEqualTo(1);assertThat(count("order_event")).isEqualTo(1);assertThat(count("idempotency_record")).isEqualTo(1);
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void concurrentSameKeyConvergesOrRejectsChangedContent(boolean changed) throws Exception {
        var f=fixture();String token=login(user("STUDENT"));var gate=new CountDownLatch(1);var list=new ArrayList<Future<HttpResponse<String>>>();
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            for(int i=0;i<8;i++){int n=i;list.add(pool.submit(()->{gate.await();var b=body(f,START);if(changed&&n%2==1){b.put("note","不同内容");}return post(b,token,"same-key");}));}
            gate.countDown();int first=0;for(var future:list){var response=future.get(35,TimeUnit.SECONDS);if(response.statusCode()==201){first++;}else if(response.statusCode()==200){assertThat(response.headers().firstValue("Idempotency-Replayed")).contains("true");}else{expect(response,409,"IDEMPOTENCY_KEY_CONFLICT");}}
            assertThat(first).isEqualTo(1);assertThat(count("service_order")).isEqualTo(1);
        }
    }
    @Test void adjacentIntervalsAndDifferentBarbersDoNotConflict() throws Exception {
        var f=fixture();String a=login(user("STUDENT")),b=login(user("STUDENT"));assertThat(create(f,a,"a").statusCode()).isEqualTo(201);
        assertThat(post(body(f,START.plusSeconds(1800)),b,"b").statusCode()).isEqualTo(201);
        var other=addBarber(f);assertThat(create(other,b,"c").statusCode()).isEqualTo(201);assertThat(count("service_order")).isEqualTo(3);
    }
    @ParameterizedTest @ValueSource(longs={899999,900000,900001})
    void cancellationAtExactDeadlineCommitsTimeoutBeforeReturning409(long elapsed) throws Exception {
        var f=fixture();String token=login(user("STUDENT"));String id=data(create(f,token,"a")).path("id").asString();fixed(NOW.plusMillis(elapsed));
        var response=call("POST","/api/v1/orders/"+id+"/cancel",null,token,null);
        if(elapsed<900000){assertThat(response.statusCode()).isEqualTo(200);}else{expect(response,409,"PAYMENT_DEADLINE_PASSED");}
        var view=data(call("GET","/api/v1/orders/"+id,null,token,null));assertThat(view.path("status").asString()).isEqualTo("CLOSED");assertThat(view.path("closeReason").asString()).isEqualTo(elapsed<900000?"USER_CANCEL":"PAYMENT_TIMEOUT");
        assertThat(view.path("version").asString()).isEqualTo("1");assertThat(view.path("booking").path("occupancyStatus").asString()).isEqualTo("RELEASED");assertThat(count("order_event")).isEqualTo(2);
        if(elapsed>=900000){expect(call("POST","/api/v1/orders/"+id+"/cancel",null,token,null),409,"PAYMENT_DEADLINE_PASSED");}
    }
    @Test void overdueReadStaysReadOnlyAndRepeatedScansRecoverAndRelease() throws Exception {
        var f=fixture();String token=login(user("STUDENT"));String id=data(create(f,token,"a")).path("id").asString();fixed(NOW.plusSeconds(900));
        assertThat(data(call("GET","/api/v1/orders/"+id,null,token,null)).path("status").asString()).isEqualTo("PENDING_PAYMENT");assertThat(starts(f)).doesNotContain(START);
        assertThat(expiry.scan()).isEqualTo(1);assertThat(expiry.scan()).isZero();assertThat(count("order_event")).isEqualTo(2);assertThat(starts(f)).contains(START);
    }
    @Test void creationCanCloseExpiredOverlappingHoldWithoutWaitingForTheScanner() throws Exception {
        var f=fixture();String a=login(user("STUDENT")),b=login(user("STUDENT"));var old=data(create(f,a,"a"));fixed(NOW.plusSeconds(900));
        assertThat(create(f,b,"b").statusCode()).isEqualTo(201);assertThat(data(call("GET","/api/v1/orders/"+old.path("id").asString(),null,a,null)).path("closeReason").asString()).isEqualTo("PAYMENT_TIMEOUT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM booking_detail WHERE occupancy_status='HELD'",Integer.class)).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"disable","unbind","clearSchedule","clipSchedule","hours"})
    void configurationCannotInvalidateHeldAppointment(String mode) throws Exception {
        var f=fixture();String student=login(user("STUDENT")),merchant=login(f.owner());create(f,student,"a");
        String path,method;Object request;
        switch(mode){
            case "disable" -> {path="/api/v1/merchant/barbers/"+f.barber();method="PATCH";request=Map.of("expectedVersion","0","enabled",false);}
            case "unbind" -> {path="/api/v1/merchant/barbers/"+f.barber()+"/services";method="PUT";request=Map.of("expectedVersion","0","serviceIds",List.of());}
            case "hours" -> {path="/api/v1/merchant/shop/business-hours";method="PUT";request=Map.of("expectedVersion","0","intervals",List.of());}
            default -> {path="/api/v1/merchant/barbers/"+f.barber()+"/schedule";method="PUT";request=Map.of("expectedBarberVersion","0","date",DATE.toString(),"intervals",mode.equals("clearSchedule")?List.of():List.of(Map.of("startTime","10:00","endTime","12:00")));}
        }
        expect(call(method,path,request,merchant,null),409,mode.equals("disable")||mode.equals("unbind")?"BOOKING_CONFLICT":"SCHEDULE_CONFLICT");
        assertThat(jdbc.queryForObject("SELECT version FROM barber WHERE id=?",Long.class,f.barber())).isZero();
        assertThat(jdbc.queryForObject("SELECT version FROM shop WHERE id=?",Long.class,f.shop())).isZero();
        var id=jdbc.queryForObject("SELECT id FROM service_order",Long.class);call("POST","/api/v1/orders/"+id+"/cancel",null,student,null);
        if(!mode.equals("hours")){assertThat(call(method,path,request,merchant,null).statusCode()).isEqualTo(200);}
    }
    @Test void shopClosureServiceDownAndMetadataChangesPreserveSnapshotsAndCancellation() throws Exception {
        var f=fixture();String student=login(user("STUDENT")),merchant=login(f.owner());var initial=data(create(f,student,"a"));
        assertThat(call("PATCH","/api/v1/merchant/shop",Map.of("expectedVersion","0","businessEnabled",false,"name","修改名称"),merchant,null).statusCode()).isEqualTo(200);
        assertThat(call("PATCH","/api/v1/merchant/services/"+f.service(),Map.of("expectedVersion","0","enabled",false,"priceAmount",5000,"durationMinutes",45),merchant,null).statusCode()).isEqualTo(200);
        assertThat(call("PATCH","/api/v1/merchant/barbers/"+f.barber(),Map.of("expectedVersion","0","displayName","修改人员"),merchant,null).statusCode()).isEqualTo(200);
        assertThat(data(call("GET","/api/v1/orders/"+initial.path("id").asString(),null,student,null))).isEqualTo(initial);
        assertThat(call("POST","/api/v1/orders/"+initial.path("id").asString()+"/cancel",null,student,null).statusCode()).isEqualTo(200);
    }
    @ParameterizedTest @ValueSource(strings={"order","booking","event","key"})
    void failureAfterEachWriteRollsBackTheEntireCreation(String stage) throws Exception {
        var f=fixture();String token=login(user("STUDENT"));
        if(stage.equals("order")){var real=sqlSession.getMapper(OrderMapper.class);doAnswer(i->{real.insert(i.getArgument(0,ServiceOrderEntity.class));throw new IllegalStateException("injected");}).when(orders).insert(any(ServiceOrderEntity.class));}
        if(stage.equals("booking")){var real=sqlSession.getMapper(BookingMapper.class);doAnswer(i->{real.insert(i.getArgument(0,BookingDetailEntity.class));throw new IllegalStateException("injected");}).when(bookings).insert(any(BookingDetailEntity.class));}
        if(stage.equals("event")){var real=sqlSession.getMapper(OrderEventMapper.class);doAnswer(i->{real.append(i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(3),i.getArgument(4),i.getArgument(5),i.getArgument(6),i.getArgument(7),i.getArgument(8));throw new IllegalStateException("injected");}).when(events).append(anyLong(),anyLong(),anyString(),isNull(),anyString(),anyString(),anyLong(),isNull(),any());}
        if(stage.equals("key")){var real=sqlSession.getMapper(IdempotencyMapper.class);doAnswer(i->{real.save(i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(3),i.getArgument(4));throw new IllegalStateException("injected");}).when(keys).save(anyLong(),anyString(),anyString(),anyLong(),any());}
        expect(create(f,token,"failure"),500,"INTERNAL_ERROR");assertThat(count("service_order")).isZero();assertThat(count("booking_detail")).isZero();assertThat(count("order_event")).isZero();assertThat(count("idempotency_record")).isZero();assertThat(starts(f)).contains(START);
    }
    @ParameterizedTest @ValueSource(strings={"serviceId","barberId","startAt","expectedServiceVersion","expectedPayableAmount","unknown","note"})
    void malformedBodiesAndUnknownFieldsNeverWriteOrders(String field) throws Exception {
        var f=fixture();String token=login(user("STUDENT"));var body=body(f,START);
        if(field.equals("unknown")){body.put("studentId","1");}else if(field.equals("note")){body.put("note","x".repeat(301));}else{body.putNull(field);}
        expect(post(body,token,"bad"),400,"VALIDATION_FAILED");assertThat(count("service_order")).isZero();assertThat(count("idempotency_record")).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"","invalid key","key/invalid","key!"})
    void invalidKeysAreRejected(String key) throws Exception {var f=fixture();expect(post(body(f,START),login(user("STUDENT")),key),400,"IDEMPOTENCY_KEY_REQUIRED");assertThat(count("service_order")).isZero();}
    @ParameterizedTest @ValueSource(strings={"closed","serviceDown","disabled","unbound","quote","time","noSchedule","nonpublic","foreign"})
    void qualificationAndCurrentConfigurationAreRecheckedForNewOrders(String mode) throws Exception {
        var f=fixture();String token=login(user("STUDENT"));var b=body(f,START);String code="";int status=409;
        switch(mode){
            case "closed"->{jdbc.update("UPDATE shop SET business_enabled=FALSE WHERE id=?",f.shop());code="SHOP_UNAVAILABLE";}
            case "serviceDown"->{jdbc.update("UPDATE service_item SET enabled=FALSE WHERE id=?",f.service());code="SERVICE_UNAVAILABLE";}
            case "disabled"->{jdbc.update("UPDATE barber SET enabled=FALSE WHERE id=?",f.barber());code="BARBER_UNAVAILABLE";}
            case "unbound"->{bindings.delete(f.barber(),f.service());code="BARBER_UNAVAILABLE";}
            case "quote"->{b.put("expectedPayableAmount",1);code="QUOTE_CHANGED";}
            case "time"->{b.put("startAt",NOW.plusSeconds(3600).toString());code="BOOKING_TIME_INVALID";}
            case "noSchedule"->{jdbc.update("DELETE FROM barber_schedule WHERE barber_id=?",f.barber());code="BOOKING_TIME_INVALID";}
            case "nonpublic"->{jdbc.update("UPDATE shop SET approval_status='SUSPENDED' WHERE id=?",f.shop());code="RESOURCE_NOT_FOUND";status=404;}
            case "foreign"->{b.put("barberId",Long.toString(fixture().barber()));code="RESOURCE_NOT_FOUND";status=404;}
        }
        expect(post(b,token,"bad"),status,code);assertThat(count("service_order")).isZero();
    }
    @Test void accessControlIncludesRoleOwnershipInvalidTokenAndMissingKey() throws Exception {
        var f=fixture();String student=login(user("STUDENT")),other=login(user("STUDENT"));String id=data(create(f,student,"a")).path("id").asString();
        for(String role:List.of("MERCHANT","ADMIN")){var token=login(user(role));expect(create(f,token,"role"),403,"ROLE_FORBIDDEN");expect(call("GET","/api/v1/orders/"+id,null,token,null),403,"ROLE_FORBIDDEN");expect(call("POST","/api/v1/orders/"+id+"/cancel",null,token,null),403,"ROLE_FORBIDDEN");}
        expect(call("GET","/api/v1/orders/"+id,null,other,null),404,"RESOURCE_NOT_FOUND");expect(call("POST","/api/v1/orders/"+id+"/cancel",null,other,null),404,"RESOURCE_NOT_FOUND");
        expect(create(f,null,"auth"),401,"AUTH_REQUIRED");expect(create(f,"bad","auth"),401,"TOKEN_INVALID");expect(post(body(f,START),student,null),400,"IDEMPOTENCY_KEY_REQUIRED");assertThat(count("service_order")).isEqualTo(1);
    }
    @Test void waitingForGuardRechecksAdvanceBoundaryAndSessionBeforeWriting() throws Exception {
        var f=fixture();fixed(START.minusSeconds(7200));String token=login(user("STUDENT"));
        var acquired=new CountDownLatch(1);var release=new CountDownLatch(1);var tx=new TransactionTemplate(manager);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var holder=pool.submit(()->tx.executeWithoutResult(s->{schedules.lockGuard(f.barber(),DATE);acquired.countDown();await(release);}));assertThat(acquired.await(5,TimeUnit.SECONDS)).isTrue();
            var request=pool.submit(()->create(f,token,"wait"));Thread.sleep(250);assertThat(request.isDone()).isFalse();fixed(START.minusSeconds(7200).plusMillis(1));release.countDown();holder.get(5,TimeUnit.SECONDS);
            expect(request.get(10,TimeUnit.SECONDS),409,"BOOKING_TIME_INVALID");assertThat(count("service_order")).isZero();
        }finally{release.countDown();}
    }
    @ParameterizedTest @ValueSource(strings={"cancel","expire"})
    void cancellationOrExpiryRacingWithNewBookingNeverLeavesTwoLiveOverlaps(String mode) throws Exception {
        var f=fixture();String a=login(user("STUDENT")),b=login(user("STUDENT"));String id=data(create(f,a,"old")).path("id").asString();if(mode.equals("expire")){fixed(NOW.plusSeconds(900));}
        var gate=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var close=pool.submit(()->{gate.await();if(mode.equals("expire")){transactions.expire(Long.parseLong(id));}else{call("POST","/api/v1/orders/"+id+"/cancel",null,a,null);}return true;});
            var create=pool.submit(()->{gate.await();return create(f,b,"new");});gate.countDown();close.get(10,TimeUnit.SECONDS);var response=create.get(10,TimeUnit.SECONDS);assertThat(response.statusCode()).isIn(201,409);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM booking_detail WHERE occupancy_status IN ('HELD','ACTIVE','CONSUMED')",Integer.class)).isLessThanOrEqualTo(1);
        }
    }
    @ParameterizedTest @CsvSource({"true,disable","false,disable","true,unbind","false,unbind","true,schedule","false,schedule","true,hours","false,hours"})
    void shopGateSerializesCreationAgainstConfigurationChanges(boolean createFirst,String mode) throws Exception {
        var f=fixture();String student=login(user("STUDENT")),merchant=login(f.owner());
        String method=mode.equals("disable")?"PATCH":"PUT";
        String path=switch(mode){case "disable"->"/api/v1/merchant/barbers/"+f.barber();case "unbind"->"/api/v1/merchant/barbers/"+f.barber()+"/services";case "schedule"->"/api/v1/merchant/barbers/"+f.barber()+"/schedule";default->"/api/v1/merchant/shop/business-hours";};
        Object config=switch(mode){case "disable"->Map.of("expectedVersion","0","enabled",false);case "unbind"->Map.of("expectedVersion","0","serviceIds",List.of());case "schedule"->Map.of("expectedBarberVersion","0","date",DATE.toString(),"intervals",List.of());default->Map.of("expectedVersion","0","intervals",List.of());};
        var reached=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            if(createFirst) {
                var real=sqlSession.getMapper(OrderMapper.class);
                doAnswer(i->{var result=real.shareShop(i.getArgument(0));reached.countDown();await(release);return result;}).when(orders).shareShop(f.shop());
                var creation=pool.submit(()->create(f,student,"race-config"));assertThat(reached.await(10,TimeUnit.SECONDS)).isTrue();
                var configuring=pool.submit(()->call(method,path,config,merchant,null));
                Thread.sleep(200);assertThat(configuring.isDone()).isFalse();release.countDown();assertThat(creation.get(10,TimeUnit.SECONDS).statusCode()).isEqualTo(201);expect(configuring.get(10,TimeUnit.SECONDS),409,mode.equals("disable")||mode.equals("unbind")?"BOOKING_CONFLICT":"SCHEDULE_CONFLICT");
            } else {
                var real=sqlSession.getMapper(ShopMapper.class);
                doAnswer(i->{var row=real.lock(i.getArgument(0));reached.countDown();await(release);return row;}).when(shops).lock(f.shop());
                var configuring=pool.submit(()->call(method,path,config,merchant,null));
                assertThat(reached.await(10,TimeUnit.SECONDS)).isTrue();var creation=pool.submit(()->create(f,student,"race-config"));Thread.sleep(200);assertThat(creation.isDone()).isFalse();release.countDown();
                if(mode.equals("hours")){
                    // Even without an order, clearing hours must preserve the already saved schedule.
                    expect(configuring.get(10,TimeUnit.SECONDS),409,"SCHEDULE_CONFLICT");assertThat(creation.get(10,TimeUnit.SECONDS).statusCode()).isEqualTo(201);
                } else {
                    assertThat(configuring.get(10,TimeUnit.SECONDS).statusCode()).isEqualTo(200);expect(creation.get(10,TimeUnit.SECONDS),409,mode.equals("schedule")?"BOOKING_TIME_INVALID":"BARBER_UNAVAILABLE");assertThat(count("service_order")).isZero();
                }
            }
        } finally {release.countDown();}
    }
    @Test void expiryFailureIsRolledBackAndNextScanRetriesTheSameDatabaseFact() throws Exception {
        var f=fixture();String token=login(user("STUDENT"));String id=data(create(f,token,"a")).path("id").asString();fixed(NOW.plusSeconds(900));
        doThrow(new IllegalStateException("injected retry")).when(orders).close(anyLong(),anyLong(),anyString(),any());
        assertThat(expiry.scan()).isZero();assertThat(count("order_event")).isEqualTo(1);assertThat(data(call("GET","/api/v1/orders/"+id,null,token,null)).path("status").asString()).isEqualTo("PENDING_PAYMENT");
        reset(orders);assertThat(expiry.scan()).isEqualTo(1);assertThat(count("order_event")).isEqualTo(2);assertThat(expiry.scan()).isZero();
    }
    @Test void cancellationEventFailureRollsBackBothOrderAndReleasedOccupancy() throws Exception {
        var f=fixture();String token=login(user("STUDENT"));String id=data(create(f,token,"a")).path("id").asString();
        doThrow(new IllegalStateException("injected close event")).when(events).append(anyLong(),eq(1L),anyString(),eq("PENDING_PAYMENT"),eq("CLOSED"),anyString(),anyLong(),anyString(),any());
        expect(call("POST","/api/v1/orders/"+id+"/cancel",null,token,null),500,"INTERNAL_ERROR");var view=data(call("GET","/api/v1/orders/"+id,null,token,null));
        assertThat(view.path("status").asString()).isEqualTo("PENDING_PAYMENT");assertThat(view.path("booking").path("occupancyStatus").asString()).isEqualTo("HELD");assertThat(view.path("version").asString()).isEqualTo("0");assertThat(count("order_event")).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"HELD","ACTIVE","CONSUMED","RELEASED"})
    void occupancyStateFilterPreservesConsumedIntervalsEvenAfterEarlyCompletion(String state) throws Exception {
        var f=fixture();create(f,login(user("STUDENT")),"a");jdbc.update("UPDATE booking_detail SET occupancy_status=?",state);
        if(state.equals("RELEASED")){assertThat(starts(f)).contains(START);}else{assertThat(starts(f)).doesNotContain(START);}
    }
    @Test void sessionExpirationWhileWaitingForAGuardLeavesNoOrder() throws Exception {
        var f=fixture();String token=login(user("STUDENT"));var reached=new CountDownLatch(1);var release=new CountDownLatch(1);
        var real=sqlSession.getMapper(BarberScheduleMapper.class);
        doAnswer(i->{var guard=real.lockGuard(i.getArgument(0),i.getArgument(1));reached.countDown();await(release);return guard;}).when(schedules).lockGuard(f.barber(),DATE);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var result=pool.submit(()->create(f,token,"expired"));assertThat(reached.await(10,TimeUnit.SECONDS)).isTrue();fixed(NOW.plusSeconds(7200));release.countDown();expect(result.get(10,TimeUnit.SECONDS),401,"SESSION_EXPIRED");assertThat(count("service_order")).isZero();
        } finally {release.countDown();}
    }
    @Test void databaseConstraintsRejectInvalidMoneyIntervalsAndDuplicateEvents() throws Exception {
        var f=fixture();create(f,login(user("STUDENT")),"a");var id=jdbc.queryForObject("SELECT id FROM service_order",Long.class);
        assertSqlFailure(()->jdbc.update("UPDATE service_order SET payable_amount=1 WHERE id=?",id),"ck_order_amount",3819);
        assertSqlFailure(()->jdbc.update("UPDATE booking_detail SET end_at=start_at WHERE order_id=?",id),"ck_booking_time",3819);
        assertSqlFailure(()->jdbc.update("INSERT INTO order_event(order_id,event_seq,action,to_status,actor_type,occurred_at) VALUES (?,0,'CREATE','PENDING_PAYMENT','SYSTEM',?)",id,LocalDateTime.ofInstant(NOW,ZoneOffset.UTC)),"uk_order_event_seq",1062);
    }
    @Test void aFreshScannerUsesExistingDatabaseDeadlinesWithoutAnyInMemoryTimerState() throws Exception {
        var f=fixture();create(f,login(user("STUDENT")),"recover");fixed(NOW.plusSeconds(901));
        var newScanner=new BookingExpiryJob(orders,transactions,clock,true);assertThat(newScanner.scan()).isEqualTo(1);assertThat(newScanner.scan()).isZero();assertThat(count("order_event")).isEqualTo(2);
    }
    @Test void actualMysqlLockTimeoutReturns503AndDoesNotConsumeTheCreationKey() throws Exception {
        var f=fixture();String token=login(user("STUDENT"));var reached=new CountDownLatch(1);var release=new CountDownLatch(1);
        var real=sqlSession.getMapper(OrderMapper.class);
        doAnswer(i->{jdbc.execute("SET SESSION innodb_lock_wait_timeout=1");try{return real.shareShop(i.getArgument(0));}finally{jdbc.execute("SET SESSION innodb_lock_wait_timeout=50");}}).when(orders).shareShop(f.shop());
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var holder=pool.submit(()->new TransactionTemplate(manager).executeWithoutResult(tx->{shops.lock(f.shop());reached.countDown();await(release);}));assertThat(reached.await(10,TimeUnit.SECONDS)).isTrue();
            expect(create(f,token,"timeout-retry"),503,"SERVICE_TEMPORARILY_UNAVAILABLE");assertThat(count("idempotency_record")).isZero();assertThat(count("service_order")).isZero();release.countDown();holder.get(5,TimeUnit.SECONDS);
            reset(orders);assertThat(create(f,token,"timeout-retry").statusCode()).isEqualTo(201);
        } finally {release.countDown();}
    }
    @Test void replayAfterWaitingForConcurrentExpiryReturnsConsistentOrderAndOccupancy() throws Exception {
        var f=fixture();String token=login(user("STUDENT"));String id=data(create(f,token,"same")).path("id").asString();fixed(NOW.plusSeconds(900));
        var reached=new CountDownLatch(1);var release=new CountDownLatch(1);var first=new java.util.concurrent.atomic.AtomicBoolean(true);var real=sqlSession.getMapper(OrderMapper.class);
        doAnswer(i->{var shop=real.shareShop(i.getArgument(0));if(first.compareAndSet(true,false)){reached.countDown();await(release);}return shop;}).when(orders).shareShop(f.shop());
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var replay=pool.submit(()->create(f,token,"same"));assertThat(reached.await(10,TimeUnit.SECONDS)).isTrue();assertThat(transactions.expire(Long.parseLong(id))).isTrue();release.countDown();var view=data(replay.get(10,TimeUnit.SECONDS));
            assertThat(view.path("status").asString()).isEqualTo("CLOSED");assertThat(view.path("booking").path("occupancyStatus").asString()).isEqualTo("RELEASED");assertThat(view.path("closeReason").asString()).isEqualTo("PAYMENT_TIMEOUT");assertThat(count("order_event")).isEqualTo(2);
        } finally{release.countDown();}
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
