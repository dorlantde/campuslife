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

@ActiveProfiles("mysql")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class OutboxOrderFixture {
    protected static final Instant NOW=Instant.parse("2026-10-22T01:00:00Z");
    protected static final LocalDate DATE=LocalDate.of(2026,10,23);
    protected static final Instant START=Instant.parse("2026-10-23T01:30:00Z");
    protected final HttpClient http=HttpClient.newHttpClient();
    protected final List<Long> accountIds=new ArrayList<>(),shopIds=new ArrayList<>();
    protected String hash;
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
        reset(clock,orders,bookings,events,keys,schedules,shops,money,mockPayment,couponRows,owned,outbox);
        for(String id:messageIds){jdbc.update("DELETE FROM notification WHERE source_message_id=?",id);jdbc.update("DELETE FROM message_consumption WHERE message_id=?",id);jdbc.update("DELETE FROM message_outbox WHERE message_id=?",id);}messageIds.clear();
        for(long student:accountIds){jdbc.update("DELETE c FROM message_consumption c JOIN notification n ON n.source_message_id=c.message_id WHERE n.recipient_id=?",student);jdbc.update("DELETE FROM notification WHERE recipient_id=?",student);jdbc.update("DELETE FROM message_outbox WHERE recipient_id=?",student);}
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
    protected record Case(Fixture f,User student,String token,tools.jackson.databind.node.ObjectNode body,String path,long coupon,long original) {}
    protected Case setup(String type)throws Exception{
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
    protected com.campuslife.auth.security.AuthPrincipal principal(User u){var row=jdbc.queryForMap("SELECT jti FROM auth_session WHERE account_id=? ORDER BY created_at DESC LIMIT 1",u.id());return new com.campuslife.auth.security.AuthPrincipal(u.id(),row.get("jti").toString(),new com.campuslife.auth.vo.AccountView(Long.toString(u.id()),u.name(),"模拟学生","STUDENT","ACTIVE","0"));}
    protected long coupon(Fixture f,User u,long threshold,long reduction,Instant end){
        var t=java.sql.Timestamp.from(NOW.minusSeconds(60));var e=java.sql.Timestamp.from(end);
        jdbc.update("INSERT INTO coupon_campaign(shop_id,name,threshold_amount,reduction_amount,total_quantity,issued_count,status,claim_start_at,claim_end_at,use_start_at,use_end_at,version,created_at,updated_at,published_at,published_by,publish_expected_version) VALUES (?,'订单测试券',?,?,10,1,'PUBLISHED',?,?,?,?,1,?,?,?,?,0)",f.shop(),threshold,reduction,t,e,t,e,t,t,t,f.owner().id());
        long campaign=jdbc.queryForObject("SELECT MAX(id) FROM coupon_campaign WHERE shop_id=?",Long.class,f.shop());
        jdbc.update("INSERT INTO user_coupon(student_id,campaign_id,shop_id,name_snapshot,threshold_amount,reduction_amount,use_start_at,use_end_at,source_campaign_version,claimed_at) VALUES (?,?,?,'订单测试券',?,?,?,?,1,?)",u.id(),campaign,f.shop(),threshold,reduction,t,e,t);
        return jdbc.queryForObject("SELECT id FROM user_coupon WHERE campaign_id=?",Long.class,campaign);
    }
    protected HttpResponse<String> create(Case c,String key)throws Exception{return call("POST",c.path(),c.body(),c.token(),key);}
    protected HttpResponse<String> action(Case c,String id,String action)throws Exception{return call("POST","/api/v1/orders/"+id+"/"+action,null,c.token(),null);}
    protected JsonNode quote(Case c)throws Exception{
        var body=c.body().deepCopy();for(String k:List.of("barberId","startAt","expectedServiceVersion","expectedPriceVersion","expectedPayableAmount")){body.remove(k);}
        return data(call("POST",c.path().endsWith("prints")?"/api/v1/print-quotes":"/api/v1/booking-quotes",body,c.token(),null));
    }
    protected String state(long id){return jdbc.queryForObject("SELECT state FROM user_coupon WHERE id=?",String.class,id);}
    protected String id(HttpResponse<String> r){return data(r).path("id").asString();}

    @MockitoSpyBean com.campuslife.messaging.mapper.OutboxMapper outbox;
    @Autowired com.campuslife.messaging.service.OutboxTransactions publishing;
    @Autowired com.campuslife.messaging.service.OutboxPublisher disabledPublisher;
    protected final List<String> messageIds=new ArrayList<>();
    protected String beforeNotify(Case c)throws Exception{
        String id=id(create(c,"notify"));data(action(c,id,"payments/mock"));
        if(c.path().endsWith("prints")){String mt=login(c.f().owner());data(call("POST","/api/v1/merchant/orders/"+id+"/accept",null,mt,null));data(call("POST","/api/v1/merchant/orders/"+id+"/start",null,mt,null));}
        return id;
    }
    protected HttpResponse<String> notify(Case c,String id,String token)throws Exception{return call("POST","/api/v1/merchant/orders/"+id+(c.path().endsWith("prints")?"/ready-for-pickup":"/accept"),null,token,null);}
    protected int messages(String order){return jdbc.queryForObject("SELECT COUNT(*) FROM message_outbox WHERE aggregate_id=?",Integer.class,order);}
    protected String enqueue(){String id=UUID.randomUUID().toString().replace("-","");messageIds.add(id);outbox.append(id,"BOOKING_ACCEPTED","BOOKING",System.nanoTime(),2,999,"{}","a".repeat(64),LocalDateTime.ofInstant(NOW,ZoneOffset.UTC));return id;}
    protected record User(long id,String name) {}
    protected record Fixture(long shop,long service,long barber,User owner) {}
    protected User user(String role){var a=new AccountEntity();a.setUsername("book_"+UUID.randomUUID().toString().replace("-","").substring(0,22));a.setNickname("模拟学生");a.setPasswordHash(hash);a.setRole(role);a.setStatus("ACTIVE");a.setVersion(0L);accounts.insert(a);accountIds.add(a.getId());return new User(a.getId(),a.getUsername());}
    protected Fixture fixture(){var owner=user("MERCHANT");var s=new ShopEntity();s.setName("模拟预约店");s.setCategoryId(jdbc.queryForObject("SELECT id FROM shop_category WHERE code='HAIRCUT'",Long.class));s.setCampusCode("XINGHAI_MAIN");s.setLocationText("虚构校区");s.setDescription("");s.setApprovalStatus("APPROVED");s.setBusinessEnabled(true);s.setVersion(0L);shops.insert(s);shopIds.add(s.getId());jdbc.update("INSERT INTO shop_member(shop_id,account_id,status) VALUES (?,?,'ACTIVE')",s.getId(),owner.id());var service=new ServiceItemEntity();service.setShopId(s.getId());service.setServiceType("HAIRCUT");service.setName("模拟理发");service.setDescription("");service.setPriceAmount(3000L);service.setDurationMinutes(30);service.setEnabled(true);service.setVersion(0L);services.insert(service);jdbc.update("INSERT INTO shop_business_hour(shop_id,weekday,start_time,end_time) VALUES (?,?,?,?)",s.getId(),DATE.getDayOfWeek().getValue(),"09:00","12:00");return addBarber(new Fixture(s.getId(),service.getId(),0,owner));}
    protected Fixture addBarber(Fixture f){var b=new BarberEntity();b.setShopId(f.shop());b.setDisplayName("模拟人员");b.setEnabled(true);b.setVersion(0L);barbers.insert(b);bindings.insert(b.getId(),f.service());schedules.insertGuard(b.getId(),DATE);schedules.insert(b.getId(),DATE,LocalDateTime.of(2026,10,23,1,0),LocalDateTime.of(2026,10,23,4,0));return new Fixture(f.shop(),f.service(),b.getId(),f.owner());}
    protected ObjectNode body(Fixture f,Instant start){return json.valueToTree(Map.of("serviceId",Long.toString(f.service()),"barberId",Long.toString(f.barber()),"startAt",start.toString(),"expectedServiceVersion","0","expectedPayableAmount",3000));}
    protected HttpResponse<String> create(Fixture f,String token,String key)throws Exception{return post(body(f,START),token,key);}
    protected HttpResponse<String> post(Object body,String token,String key)throws Exception{return call("POST","/api/v1/orders/bookings",body,token,key);}
    protected String login(User user)throws Exception{return data(call("POST","/api/v1/auth/login",Map.of("username",user.name(),"password","Booking_Test123!"),null,null)).path("accessToken").asString();}
    protected List<Instant> starts(Fixture f)throws Exception{var result=data(call("GET","/api/v1/shops/"+f.shop()+"/booking-slots?serviceId="+f.service()+"&barberId="+f.barber()+"&date="+DATE,null,null,null));var list=new ArrayList<Instant>();for(var item:result.path("slots")){list.add(Instant.parse(item.path("startAt").asString()));}return list;}
    protected int count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
    protected void assertSqlFailure(Runnable statement,String constraint,int code) {
        var failure=catchThrowable(statement::run);assertThat(failure).isInstanceOf(org.springframework.dao.DataAccessException.class).hasMessageContaining(constraint);
        var cause=((org.springframework.dao.DataAccessException)failure).getMostSpecificCause();assertThat(cause).isInstanceOf(java.sql.SQLException.class);
        assertThat(((java.sql.SQLException)cause).getErrorCode()).isEqualTo(code);
    }
    protected void fixed(Instant value){doReturn(value).when(clock).instant();}
    protected JsonNode data(HttpResponse<String> response){assertThat(response.statusCode()).as(response.body()).isIn(200,201);return json.readTree(response.body()).path("data");}
    protected void expect(HttpResponse<String> response,int status,String code){assertThat(response.statusCode()).as(response.body()).isEqualTo(status);assertThat(json.readTree(response.body()).path("code").asString()).isEqualTo(code);}
    protected HttpResponse<String> call(String method,String path,Object body,String token,String key)throws Exception{var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+env.getRequiredProperty("local.server.port")+path)).timeout(Duration.ofSeconds(40));if(token!=null){builder.header("Authorization","Bearer "+token);}if(key!=null){builder.header("Idempotency-Key",key);}if(body!=null){builder.header("Content-Type","application/json");}builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));return http.send(builder.build(),HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));}
    protected static void await(CountDownLatch latch){try{if(!latch.await(10,TimeUnit.SECONDS)){throw new IllegalStateException("gate timeout");}}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IllegalStateException(ex);}}
}
