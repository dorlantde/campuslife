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

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"campuslife.booking.expiry-enabled=false","campuslife.print.expiry-enabled=false","campuslife.files.cleanup-enabled=false"})
@ActiveProfiles("mysql")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CouponOrdersMysqlIT {
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
    @BeforeEach void freeze() { fixed(NOW);doReturn(true).when(mockPayment).enabled(); }
    @AfterEach void cleanup() {
        reset(clock,orders,bookings,events,keys,schedules,shops,money,mockPayment,couponRows,owned);
        for (long shop:shopIds) {
            jdbc.update("UPDATE user_coupon SET state='AVAILABLE',current_order_id=NULL WHERE shop_id=?",shop);
            jdbc.update("DELETE c FROM order_coupon c JOIN service_order o ON o.id=c.order_id WHERE o.shop_id=?",shop);
            jdbc.update("DELETE FROM coupon_claim_request WHERE campaign_id IN(SELECT id FROM coupon_campaign WHERE shop_id=?)",shop);
            jdbc.update("DELETE FROM user_coupon WHERE shop_id=?",shop);jdbc.update("DELETE FROM coupon_campaign WHERE shop_id=?",shop);
            jdbc.update("DELETE d FROM print_detail d JOIN service_order o ON o.id=d.order_id WHERE o.shop_id=?",shop);
            jdbc.update("DELETE p FROM print_price_rule p JOIN service_item s ON s.id=p.service_id WHERE s.shop_id=?",shop);
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
        for(long id:accountIds){for(var row:jdbc.queryForList("SELECT storage_key FROM file_asset WHERE owner_id=?",id)){try{disk.delete(disk.storageId(),row.get("storage_key").toString());}catch(java.io.IOException ex){throw new IllegalStateException(ex);}}jdbc.update("DELETE FROM file_asset WHERE owner_id=?",id);}
        for (long id:accountIds) { jdbc.update("DELETE FROM auth_session WHERE account_id=?",id);jdbc.update("DELETE FROM account WHERE id=?",id); }
        accountIds.clear();shopIds.clear();
    }


    @MockitoSpyBean com.campuslife.coupon.mapper.OrderCouponMapper couponRows;
    @MockitoSpyBean com.campuslife.coupon.mapper.OwnedCouponMapper owned;
    @Autowired com.campuslife.file.storage.LocalFileStorage disk;
    @Autowired com.campuslife.file.service.FileUploadService uploads;
    @Autowired com.campuslife.order.service.PrintOrderTransactions printTransactions;
    private record Case(Fixture f,User student,String token,tools.jackson.databind.node.ObjectNode body,String path,long coupon,long original) {}
    private Case setup(String type)throws Exception{
        var f=fixture();var u=user("STUDENT");String token=login(u);long original=3000;
        var body=body(f,START);String path="/api/v1/orders/bookings";
        if(type.equals("PRINT")){
            jdbc.update("UPDATE shop SET category_id=(SELECT id FROM shop_category WHERE code='PRINT') WHERE id=?",f.shop());
            jdbc.update("UPDATE service_item SET service_type='PRINT',duration_minutes=NULL WHERE id=?",f.service());
            jdbc.update("INSERT INTO shop_business_hour(shop_id,weekday,start_time,end_time) VALUES (?,?,'08:00','22:00')",f.shop(),NOW.atZone(ZoneId.of("Asia/Shanghai")).getDayOfWeek().getValue());
            jdbc.update("INSERT INTO print_price_rule(service_id,paper_size,color_mode,side_mode,price_per_impression,enabled,version) VALUES (?,'A4','BW','DOUBLE',300,1,0)",f.service());
            var principal=principal(u);var file=uploads.upload(principal,new org.springframework.mock.web.MockMultipartFile("file","test.pdf","application/pdf",com.campuslife.file.PdfSamples.pdf(5)));
            body=json.valueToTree(Map.of("serviceId",Long.toString(f.service()),"fileId",file.id(),"paperSize","A4","colorMode","BW","sideMode","DOUBLE","copies",2,"expectedPriceVersion","0","expectedPayableAmount",3000));path="/api/v1/orders/prints";
        }
        long coupon=coupon(f,u,2000,500,NOW.plusSeconds(600));body.put("userCouponId",Long.toString(coupon));body.put("expectedPayableAmount",2500);
        return new Case(f,u,token,body,path,coupon,original);
    }
    private com.campuslife.auth.security.AuthPrincipal principal(User u){var row=jdbc.queryForMap("SELECT jti FROM auth_session WHERE account_id=? ORDER BY created_at DESC LIMIT 1",u.id());return new com.campuslife.auth.security.AuthPrincipal(u.id(),row.get("jti").toString(),new com.campuslife.auth.vo.AccountView(Long.toString(u.id()),u.name(),"模拟学生","STUDENT","ACTIVE","0"));}
    private long coupon(Fixture f,User u,long threshold,long reduction,Instant end){
        var t=java.sql.Timestamp.from(NOW.minusSeconds(60));var e=java.sql.Timestamp.from(end);
        jdbc.update("INSERT INTO coupon_campaign(shop_id,name,threshold_amount,reduction_amount,total_quantity,issued_count,status,claim_start_at,claim_end_at,use_start_at,use_end_at,version,created_at,updated_at,published_at,published_by,publish_expected_version) VALUES (?,'订单测试券',?,?,10,1,'PUBLISHED',?,?,?,?,1,?,?,?,?,0)",f.shop(),threshold,reduction,t,e,t,e,t,t,t,f.owner().id());
        long campaign=jdbc.queryForObject("SELECT MAX(id) FROM coupon_campaign WHERE shop_id=?",Long.class,f.shop());
        jdbc.update("INSERT INTO user_coupon(student_id,campaign_id,shop_id,name_snapshot,threshold_amount,reduction_amount,use_start_at,use_end_at,source_campaign_version,claimed_at) VALUES (?,?,?,'订单测试券',?,?,?,?,1,?)",u.id(),campaign,f.shop(),threshold,reduction,t,e,t);
        return jdbc.queryForObject("SELECT id FROM user_coupon WHERE campaign_id=?",Long.class,campaign);
    }
    private HttpResponse<String> create(Case c,String key)throws Exception{return call("POST",c.path(),c.body(),c.token(),key);}
    private HttpResponse<String> action(Case c,String id,String action)throws Exception{return call("POST","/api/v1/orders/"+id+"/"+action,null,c.token(),null);}
    private JsonNode quote(Case c)throws Exception{
        var body=c.body().deepCopy();for(String k:List.of("barberId","startAt","expectedServiceVersion","expectedPriceVersion","expectedPayableAmount")){body.remove(k);}
        return data(call("POST",c.path().endsWith("prints")?"/api/v1/print-quotes":"/api/v1/booking-quotes",body,c.token(),null));
    }
    private String state(long id){return jdbc.queryForObject("SELECT state FROM user_coupon WHERE id=?",String.class,id);}
    private String id(HttpResponse<String> r){return data(r).path("id").asString();}
    @ParameterizedTest @ValueSource(strings={"BOOKING","PRINT"})
    void quoteCreateReleaseReusePaymentAndRefund(String type)throws Exception{
        var c=setup(type);var q=quote(c);assertThat(q.path("originalAmount").asLong()).isEqualTo(3000);assertThat(q.path("discountAmount").asLong()).isEqualTo(500);assertThat(state(c.coupon())).isEqualTo("AVAILABLE");
        String first=id(create(c,"first"));assertThat(state(c.coupon())).isEqualTo("LOCKED");assertThat(data(create(c,"first")).path("id").asString()).isEqualTo(first);
        assertThat(data(action(c,first,"cancel")).path("coupon").path("state").asString()).isEqualTo("RELEASED");assertThat(state(c.coupon())).isEqualTo("AVAILABLE");
        String second=id(create(c,"second"));jdbc.update("UPDATE coupon_campaign SET status='STOPPED',version=2,stop_expected_version=1,stopped_at=updated_at,stopped_by=published_by WHERE shop_id=?",c.f().shop());
        fixed(NOW.plusSeconds(601));assertThat(data(action(c,second,"payments/mock")).path("amount").asLong()).isEqualTo(2500);assertThat(state(c.coupon())).isEqualTo("USED");
        var closed=data(action(c,second,"cancel"));assertThat(closed.path("refund").path("amount").asLong()).isEqualTo(2500);assertThat(closed.path("coupon").path("state").asString()).isEqualTo("USED");assertThat(state(c.coupon())).isEqualTo("USED");
        assertThat(data(create(c,"first")).path("coupon").path("state").asString()).isEqualTo("RELEASED");
        assertThat(data(call("GET","/api/v1/me/coupons?state=USED",null,c.token(),null)).path("total").asLong()).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"BOOKING","PRINT"})
    void timeoutReleasesExpiredCouponAndNeverGeneratesMoney(String type)throws Exception{
        var c=setup(type);String id=id(create(c,"timeout"));fixed(NOW.plusSeconds(900));expect(action(c,id,"payments/mock"),409,"PAYMENT_DEADLINE_PASSED");assertThat(state(c.coupon())).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_record WHERE order_id=?",Integer.class,id)).isZero();expect(action(c,id,"cancel"),409,"PAYMENT_DEADLINE_PASSED");assertThat(jdbc.queryForObject("SELECT version FROM user_coupon WHERE id=?",Long.class,c.coupon())).isEqualTo(2);
    }
    @ParameterizedTest @ValueSource(strings={"BOOKING","PRINT"})
    void invalidCouponAndWrongPayableRollbackOrderResources(String type)throws Exception{
        var c=setup(type);c.body().put("expectedPayableAmount",2499);expect(create(c,"wrong"),409,"QUOTE_CHANGED");assertThat(state(c.coupon())).isEqualTo("AVAILABLE");assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM service_order WHERE shop_id=?",Integer.class,c.f().shop())).isZero();
        c.body().put("expectedPayableAmount",2500);String other=login(user("STUDENT"));expect(call("POST",c.path(),c.body(),other,"foreign"),type.equals("PRINT")?404:404,"RESOURCE_NOT_FOUND");
        c.body().put("userCouponId","9223372036854775807");expect(create(c,"missing"),404,"RESOURCE_NOT_FOUND");c.body().put("userCouponId",c.coupon());expect(create(c,"numeric"),400,"VALIDATION_FAILED");
    }
    @ParameterizedTest @ValueSource(strings={"BOOKING","PRINT"})
    void snapshotAndConsumeFailureRollbackAllFacts(String type)throws Exception{
        var c=setup(type);doThrow(new IllegalStateException("injected coupon snapshot failure")).when(couponRows).create(anyLong(),any(),any());expect(create(c,"rollback"),500,"INTERNAL_ERROR");assertThat(state(c.coupon())).isEqualTo("AVAILABLE");reset(couponRows);
        String id=id(create(c,"rollback"));doThrow(new IllegalStateException("injected consume failure")).when(couponRows).use(anyLong(),any());expect(action(c,id,"payments/mock"),500,"INTERNAL_ERROR");assertThat(state(c.coupon())).isEqualTo("LOCKED");assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_record WHERE order_id=?",Integer.class,id)).isZero();reset(couponRows);data(action(c,id,"payments/mock"));assertThat(state(c.coupon())).isEqualTo("USED");
    }
    @ParameterizedTest @ValueSource(strings={"BOOKING","PRINT"})
    void sameCouponTwoCreatesHaveExactlyOneWinner(String type)throws Exception{
        var c=setup(type);var gate=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){var a=pool.submit(()->{gate.await();return create(c,"race-a");});var b=pool.submit(()->{gate.await();return create(c,"race-b");});gate.countDown();var x=a.get(30,TimeUnit.SECONDS);var y=b.get(30,TimeUnit.SECONDS);assertThat(List.of(x.statusCode(),y.statusCode())).containsExactlyInAnyOrder(201,409);}
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_coupon WHERE user_coupon_id=?",Integer.class,c.coupon())).isEqualTo(1);assertThat(state(c.coupon())).isEqualTo("LOCKED");
    }
    @Test void bookingOpportunisticExpiryReleasesThenReusesSameCoupon()throws Exception{
        var c=setup("BOOKING");jdbc.update("UPDATE user_coupon SET use_end_at=? WHERE id=?",java.sql.Timestamp.from(NOW.plusSeconds(3600)),c.coupon());String first=id(create(c,"old"));fixed(NOW.plusSeconds(900));String next=id(create(c,"replacement"));assertThat(next).isNotEqualTo(first);assertThat(jdbc.queryForObject("SELECT state FROM order_coupon WHERE order_id=?",String.class,first)).isEqualTo("RELEASED");assertThat(state(c.coupon())).isEqualTo("LOCKED");
    }
    @Test void oneFenPayableAndThresholdEqualityAreAcceptedButBelowThresholdRejected()throws Exception{
        var c=setup("BOOKING");long id=coupon(c.f(),c.student(),3000,2999,NOW.plusSeconds(3600));c.body().put("userCouponId",Long.toString(id));c.body().put("expectedPayableAmount",1);assertThat(data(create(c,"onefen")).path("payableAmount").asLong()).isEqualTo(1);
        data(action(c,jdbc.queryForObject("SELECT current_order_id FROM user_coupon WHERE id=?",Long.class,id).toString(),"cancel"));long high=coupon(c.f(),c.student(),3001,100,NOW.plusSeconds(3600));c.body().put("userCouponId",Long.toString(high));expect(create(c,"threshold"),409,"COUPON_THRESHOLD_NOT_MET");
    }

    @ParameterizedTest @ValueSource(strings={"BOOKING","PRINT"})
    void releaseFailureRollsBackClosureAndCanRetry(String type)throws Exception{
        var c=setup(type);String id=id(create(c,"release-failure"));doThrow(new IllegalStateException("injected release failure")).when(couponRows).release(anyLong(),any());expect(action(c,id,"cancel"),500,"INTERNAL_ERROR");assertThat(state(c.coupon())).isEqualTo("LOCKED");assertThat(jdbc.queryForObject("SELECT status FROM service_order WHERE id=?",String.class,id)).isEqualTo("PENDING_PAYMENT");reset(couponRows);data(action(c,id,"cancel"));assertThat(state(c.coupon())).isEqualTo("AVAILABLE");
    }
    @ParameterizedTest @ValueSource(strings={"BOOKING","PRINT"})
    void realCouponRowWaitRechecksTimeBeforeReserving(String type)throws Exception{
        var c=setup(type);var held=new CountDownLatch(1);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var real=sqlSession.getMapper(com.campuslife.coupon.mapper.OwnedCouponMapper.class);
        doAnswer(i->{entered.countDown();return real.lock(i.getArgument(0));}).when(owned).lock(c.coupon());
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var holder=pool.submit(()->new TransactionTemplate(manager).executeWithoutResult(tx->{jdbc.queryForList("SELECT id FROM user_coupon WHERE id=? FOR UPDATE",c.coupon());held.countDown();await(release);}));assertThat(held.await(10,TimeUnit.SECONDS)).isTrue();
            var creating=pool.submit(()->create(c,"wait"));assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();fixed(NOW.plusSeconds(600));release.countDown();holder.get(10,TimeUnit.SECONDS);expect(creating.get(20,TimeUnit.SECONDS),409,"COUPON_UNAVAILABLE");assertThat(state(c.coupon())).isEqualTo("AVAILABLE");assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM service_order WHERE shop_id=?",Integer.class,c.f().shop())).isZero();
        }finally{release.countDown();}
    }

    @ParameterizedTest @ValueSource(strings={"BOOKING","PRINT"})
    void paidExpiryRefundsPayableAndRetainsUsedCoupon(String type)throws Exception{
        var c=setup(type);String id=id(create(c,"paid-expiry"));data(action(c,id,"payments/mock"));fixed(NOW.plusSeconds(1800));assertThat(type.equals("BOOKING")?transactions.expire(Long.parseLong(id)):printTransactions.expire(Long.parseLong(id))).isTrue();
        assertThat(jdbc.queryForObject("SELECT amount FROM refund_record WHERE order_id=?",Long.class,id)).isEqualTo(2500);assertThat(state(c.coupon())).isEqualTo("USED");assertThat(type.equals("BOOKING")?transactions.expire(Long.parseLong(id)):printTransactions.expire(Long.parseLong(id))).isFalse();
    }
    @Test void quoteEnforcesIdentityCouponWindowShopAndReadOnlySemantics()throws Exception{
        var c=setup("BOOKING");expect(call("POST","/api/v1/booking-quotes",Map.of("serviceId",Long.toString(c.f().service())),null,null),401,"AUTH_REQUIRED");
        expect(call("POST","/api/v1/booking-quotes",Map.of("serviceId",Long.toString(c.f().service())),login(c.f().owner()),null),403,"ROLE_FORBIDDEN");
        jdbc.update("UPDATE shop SET business_enabled=0 WHERE id=?",c.f().shop());assertThat(quote(c).path("payableAmount").asLong()).isEqualTo(2500);assertThat(jdbc.queryForObject("SELECT version FROM user_coupon WHERE id=?",Long.class,c.coupon())).isZero();
        var second=fixture();long foreign=coupon(second,c.student(),2000,500,NOW.plusSeconds(600));expect(call("POST","/api/v1/booking-quotes",Map.of("serviceId",Long.toString(c.f().service()),"userCouponId",Long.toString(foreign)),c.token(),null),404,"RESOURCE_NOT_FOUND");
        fixed(NOW.plusSeconds(600));expect(call("POST","/api/v1/booking-quotes",Map.of("serviceId",Long.toString(c.f().service()),"userCouponId",Long.toString(c.coupon())),c.token(),null),409,"COUPON_UNAVAILABLE");
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
