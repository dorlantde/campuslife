package com.campuslife;

import com.campuslife.admin.bootstrap.AdminAccountInitializer;
import com.campuslife.auth.mapper.AccountMapper;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Business setup and transitions use HTTP; SQL is limited to assertions and owned-fixture cleanup. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "campuslife.mock-payment.enabled=true","campuslife.booking.expiry-enabled=false",
        "campuslife.print.expiry-enabled=false","campuslife.files.cleanup-enabled=false"})
@ActiveProfiles("mysql")
class BookingJourneyMysqlIT {
    private static final Instant NOW=Instant.parse("2026-10-26T01:00:00Z");
    private static final String PASSWORD="Journey_Test123!";
    private final HttpClient http=HttpClient.newHttpClient();
    private final List<Long> ownedAccounts=new ArrayList<>(),ownedShops=new ArrayList<>();
    @Autowired JdbcTemplate jdbc;@Autowired Environment env;@Autowired ObjectMapper json;
    @Autowired AccountMapper accounts;@Autowired PasswordEncoder passwords;@MockitoSpyBean Clock clock;
    private record User(String id,String username){}
    private record Fixture(User student,User merchant,User admin,String st,String mt,String at,
                           String shop,String service,String barber,String barberVersion,JsonNode candidate,String date){}
    @BeforeEach void freeze(){fixed(NOW);}
    @AfterEach void cleanup(){
        reset(clock);
        for(long shop:ownedShops){
            jdbc.update("DELETE FROM order_review WHERE shop_id=?",shop);
            for(String table:List.of("refund_record","payment_record","idempotency_record","order_event","booking_detail")){
                jdbc.update("DELETE x FROM "+table+" x JOIN service_order o ON o.id=x.order_id WHERE o.shop_id=?",shop);
            }
            jdbc.update("DELETE FROM service_order WHERE shop_id=?",shop);
            jdbc.update("DELETE s FROM barber_schedule s JOIN barber b ON b.id=s.barber_id WHERE b.shop_id=?",shop);
            jdbc.update("DELETE g FROM barber_day_guard g JOIN barber b ON b.id=g.barber_id WHERE b.shop_id=?",shop);
            jdbc.update("DELETE x FROM barber_service x JOIN barber b ON b.id=x.barber_id WHERE b.shop_id=?",shop);
            jdbc.update("DELETE FROM barber WHERE shop_id=?",shop);jdbc.update("DELETE FROM service_item WHERE shop_id=?",shop);
            jdbc.update("DELETE FROM shop_business_hour WHERE shop_id=?",shop);jdbc.update("DELETE FROM shop_review WHERE shop_id=?",shop);
            jdbc.update("DELETE FROM shop_member WHERE shop_id=?",shop);jdbc.update("DELETE FROM shop WHERE id=?",shop);
        }
        for(long id:ownedAccounts){jdbc.update("DELETE FROM auth_session WHERE account_id=?",id);jdbc.update("DELETE FROM account WHERE id=?",id);}
    }
    private Fixture setup()throws Exception{
        var student=register();var st=login(student);
        var adminName=name();var adminId=new AdminAccountInitializer(accounts,passwords).create(adminName,"虚构验收管理员",PASSWORD);
        ownedAccounts.add(Long.parseLong(adminId));var admin=new User(adminId,adminName);var at=login(admin);
        String category=null;for(var row:data(call("GET","/api/v1/shop-categories",null,null,null))){if(row.path("code").asString().equals("HAIRCUT")){category=row.path("id").asString();}}
        assertThat(category).isNotNull();var merchantName=name();
        var app=data(call("POST","/api/v1/merchant-applications",Map.of("username",merchantName,"password",PASSWORD,"nickname","虚构理发店主","categoryId",category,"shopName","虚构预约闭环店","locationText","虚构校园服务中心"),null,null));
        var merchant=new User(app.path("accountId").asString(),merchantName);var shop=app.path("shopId").asString();ownedAccounts.add(Long.parseLong(merchant.id()));ownedShops.add(Long.parseLong(shop));var mt=login(merchant);
        expect(call("GET","/api/v1/shops/"+shop,null,null,null),404,"RESOURCE_NOT_FOUND");
        var pending=data(call("GET","/api/v1/admin/shops/"+shop,null,at,null));
        var approved=data(call("POST","/api/v1/admin/shops/"+shop+"/approve",Map.of("expectedVersion",pending.path("version").asString()),at,null));
        assertThat(approved.path("approvalStatus").asString()).isEqualTo("APPROVED");assertThat(approved.path("businessEnabled").asBoolean()).isFalse();
        var date=NOW.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
        var hours=data(call("PUT","/api/v1/merchant/shop/business-hours",Map.of("expectedVersion",approved.path("version").asString(),"intervals",List.of(Map.of("weekday",date.getDayOfWeek().getValue(),"startTime","08:00","endTime","22:00"))),mt,null));
        data(call("PATCH","/api/v1/merchant/shop",Map.of("expectedVersion",hours.path("version").asString(),"businessEnabled",true),mt,null));
        var service=data(call("POST","/api/v1/merchant/services",Map.of("serviceType","HAIRCUT","name","模拟标准理发","priceAmount",3000,"durationMinutes",30,"enabled",true),mt,null));
        var person=data(call("POST","/api/v1/merchant/barbers",Map.of("displayName","模拟理发师小林","serviceIds",List.of()),mt,null));
        assertThat(person.path("enabled").asBoolean()).isFalse();var barber=person.path("id").asString();
        var bound=data(call("PUT","/api/v1/merchant/barbers/"+barber+"/services",Map.of("expectedVersion",person.path("version").asString(),"serviceIds",List.of(service.path("id").asString())),mt,null));
        var enabled=data(call("PATCH","/api/v1/merchant/barbers/"+barber,Map.of("expectedVersion",bound.path("version").asString(),"enabled",true),mt,null));
        var scheduled=data(call("PUT","/api/v1/merchant/barbers/"+barber+"/schedule",Map.of("date",date.toString(),"expectedBarberVersion",enabled.path("version").asString(),"intervals",List.of(Map.of("startTime","08:00","endTime","22:00"))),mt,null));
        assertThat(data(call("GET","/api/v1/shops/"+shop+"/services",null,null,null)).size()).isEqualTo(1);
        assertThat(data(call("GET","/api/v1/shops/"+shop+"/barbers?serviceId="+service.path("id").asString(),null,null,null)).get(0).path("id").asString()).isEqualTo(barber);
        var candidate=data(call("GET","/api/v1/shops/"+shop+"/booking-slots?serviceId="+service.path("id").asString()+"&barberId="+barber+"&date="+date,null,null,null));
        assertThat(candidate.path("slots").size()).isGreaterThan(0);
        return new Fixture(student,merchant,admin,st,mt,at,shop,service.path("id").asString(),barber,scheduled.path("barberVersion").asString(),candidate,date.toString());
    }
    private User register()throws Exception{var n=name();var row=data(call("POST","/api/v1/auth/register",Map.of("username",n,"password",PASSWORD,"nickname","虚构验收同学"),null,null));var u=new User(row.path("id").asString(),n);ownedAccounts.add(Long.parseLong(u.id()));return u;}
    private String name(){return "journey_"+UUID.randomUUID().toString().replace("-","").substring(0,20);}
    private String login(User u)throws Exception{return data(call("POST","/api/v1/auth/login",Map.of("username",u.username(),"password",PASSWORD),null,null)).path("accessToken").asString();}
    private Map<String,Object> body(Fixture f){return Map.of("serviceId",f.service(),"barberId",f.barber(),"startAt",start(f).toString(),"expectedServiceVersion",f.candidate().path("serviceVersion").asString(),"expectedPayableAmount",f.candidate().path("priceAmount").asLong());}
    private Instant start(Fixture f){return Instant.parse(f.candidate().path("slots").get(0).path("startAt").asString());}
    private HttpResponse<String> create(Fixture f,String key)throws Exception{return call("POST","/api/v1/orders/bookings",body(f),f.st(),key);}
    private String book(Fixture f)throws Exception{var r=create(f,"journey-booking");assertThat(r.statusCode()).as(r.body()).isEqualTo(201);return data(r).path("id").asString();}
    private JsonNode own(String id,String t)throws Exception{return data(call("GET","/api/v1/orders/"+id,null,t,null));}
    private JsonNode pay(Fixture f,String id)throws Exception{return data(call("POST","/api/v1/orders/"+id+"/payments/mock",null,f.st(),null));}
    private JsonNode merchant(Fixture f,String id,String action,Object body)throws Exception{return data(call("POST","/api/v1/merchant/orders/"+id+"/"+action,body,f.mt(),null));}
    private JsonNode events(String id,String t)throws Exception{return data(call("GET","/api/v1/orders/"+id+"/events",null,t,null));}
    private long count(String table,String id){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE order_id=?",Long.class,id);}
    private boolean offered(Fixture f)throws Exception{var v=data(call("GET","/api/v1/shops/"+f.shop()+"/booking-slots?serviceId="+f.service()+"&barberId="+f.barber()+"&date="+f.date(),null,null,null));for(var slot:v.path("slots")){if(slot.path("startAt").asString().equals(start(f).toString())){return true;}}return false;}
    @Test void completeJourneyCreatesBusinessThroughHttpAndPublishesARealReview()throws Exception{
        var f=setup();var id=book(f);var initial=own(id,f.st());assertThat(initial.path("status").asString()).isEqualTo("PENDING_PAYMENT");assertThat(offered(f)).isFalse();
        var replay=create(f,"journey-booking");assertThat(replay.statusCode()).isEqualTo(200);assertThat(replay.headers().firstValue("Idempotency-Replayed")).contains("true");assertThat(data(replay).path("id").asString()).isEqualTo(id);
        var outsider=login(register());expect(call("GET","/api/v1/orders/"+id,null,outsider,null),404,"RESOURCE_NOT_FOUND");
        assertThat(data(call("GET","/api/v1/orders?orderType=BOOKING&status=PENDING_PAYMENT",null,f.st(),null)).path("items").get(0).path("id").asString()).isEqualTo(id);
        assertThat(data(call("GET","/api/v1/merchant/orders?orderType=BOOKING",null,f.mt(),null)).path("items").get(0).path("id").asString()).isEqualTo(id);
        var paid=pay(f,id);assertThat(pay(f,id)).isEqualTo(paid);var accepted=merchant(f,id,"accept",null);assertThat(merchant(f,id,"accept",null)).isEqualTo(accepted);
        expect(call("PATCH","/api/v1/merchant/barbers/"+f.barber(),Map.of("expectedVersion",f.barberVersion(),"enabled",false),f.mt(),null),409,"BOOKING_CONFLICT");
        expect(call("POST","/api/v1/merchant/orders/"+id+"/start",null,f.mt(),null),409,"BOOKING_TIME_INVALID");
        fixed(start(f));var mt=login(f.merchant());var st=login(f.student());
        data(call("POST","/api/v1/merchant/orders/"+id+"/start",null,mt,null));var done=data(call("POST","/api/v1/merchant/orders/"+id+"/complete",null,mt,null));
        assertThat(done.path("status").asString()).isEqualTo("COMPLETED");assertThat(done.path("paymentStatus").asString()).isEqualTo("PAID");assertThat(done.path("booking").path("occupancyStatus").asString()).isEqualTo("CONSUMED");assertThat(done.path("version").asString()).isEqualTo("4");
        var before=events(id,st);var review=data(call("POST","/api/v1/orders/"+id+"/review",Map.of("rating",5,"content","模拟预约全流程完成😀"),st,null));assertThat(own(id,st).path("reviewed").asBoolean()).isTrue();assertThat(own(id,st).path("version").asString()).isEqualTo("4");assertThat(events(id,st)).isEqualTo(before);
        var actions=new ArrayList<String>();for(var e:before){actions.add(e.path("action").asString());}assertThat(actions).containsExactly("CREATE","PAYMENT","ACCEPT","START","COMPLETE");
        assertThat(data(call("GET","/api/v1/merchant/orders/"+id+"/events",null,mt,null))).isEqualTo(before);
        data(call("PATCH","/api/v1/me",Map.of("expectedVersion","0","nickname","改名后的验收同学"),st,null));var page=data(call("GET","/api/v1/shops/"+f.shop()+"/reviews",null,null,null));assertThat(page.path("total").asLong()).isEqualTo(1);assertThat(page.path("items").get(0).path("reviewId").asString()).isEqualTo(review.path("id").asString());assertThat(page.path("items").get(0).path("reviewerNickname").asString()).isEqualTo("改名后的验收同学");
        var shop=data(call("GET","/api/v1/shops/"+f.shop(),null,null,null));assertThat(shop.path("rating").asDouble()).isEqualTo(5);assertThat(shop.path("reviewCount").asLong()).isEqualTo(1);assertThat(count("payment_record",id)).isEqualTo(1);assertThat(count("refund_record",id)).isZero();assertThat(count("order_review",id)).isEqualTo(1);
    }
    @Test void merchantRejectRefundsExactlyOnceAndOffersTheSlotAgain()throws Exception{var f=setup();var id=book(f);pay(f,id);var closed=merchant(f,id,"reject",Map.of("reason","模拟人员临时不可用"));assertThat(closed.path("status").asString()).isEqualTo("CLOSED");assertThat(closed.path("paymentStatus").asString()).isEqualTo("REFUNDED");assertThat(closed.path("booking").path("occupancyStatus").asString()).isEqualTo("RELEASED");assertThat(merchant(f,id,"reject",Map.of("reason","模拟人员临时不可用"))).isEqualTo(closed);assertThat(offered(f)).isTrue();assertThat(count("payment_record",id)).isEqualTo(1);assertThat(count("refund_record",id)).isEqualTo(1);assertThat(count("order_event",id)).isEqualTo(3);assertThat(events(id,f.st()).get(2).path("action").asString()).isEqualTo("MERCHANT_REJECT");}
    @Test void unpaidCancelReleasesWithoutMoneyAndCreationKeyKeepsTheOriginalOrder()throws Exception{var f=setup();var id=book(f);var closed=data(call("POST","/api/v1/orders/"+id+"/cancel",null,f.st(),null));assertThat(closed.path("paymentStatus").asString()).isEqualTo("NOT_PAID");assertThat(closed.path("booking").path("occupancyStatus").asString()).isEqualTo("RELEASED");assertThat(offered(f)).isTrue();assertThat(data(create(f,"journey-booking")).path("id").asString()).isEqualTo(id);assertThat(data(create(f,"journey-booking-new")).path("id").asString()).isNotEqualTo(id);assertThat(count("payment_record",id)).isZero();assertThat(count("refund_record",id)).isZero();assertThat(count("order_event",id)).isEqualTo(2);}
    @Test void adminClosesStartedServiceWithOneRefundAndKeepsConsumedInterval()throws Exception{var f=setup();var id=book(f);pay(f,id);merchant(f,id,"accept",null);fixed(start(f));var mt=login(f.merchant());var at=login(f.admin());var st=login(f.student());data(call("POST","/api/v1/merchant/orders/"+id+"/start",null,mt,null));var checked=data(call("GET","/api/v1/admin/orders/"+id,null,at,null));var request=Map.of("expectedVersion",checked.path("version").asString(),"reason","模拟服务开始后异常，已核实");var closed=data(call("POST","/api/v1/admin/orders/"+id+"/close-exception",request,at,null));assertThat(closed.path("status").asString()).isEqualTo("CLOSED");assertThat(closed.path("paymentStatus").asString()).isEqualTo("REFUNDED");assertThat(closed.path("booking").path("occupancyStatus").asString()).isEqualTo("CONSUMED");assertThat(data(call("POST","/api/v1/admin/orders/"+id+"/close-exception",request,at,null))).isEqualTo(closed);assertThat(events(id,st).get(4).path("actorType").asString()).isEqualTo("ADMIN");assertThat(count("refund_record",id)).isEqualTo(1);assertThat(count("order_event",id)).isEqualTo(5);expect(call("POST","/api/v1/orders/"+id+"/review",Map.of("rating",5,"content","关闭单不能评价"),st,null),409,"REVIEW_NOT_ALLOWED");}
    private void fixed(Instant t){doReturn(t).when(clock).instant();}
    private HttpResponse<String> call(String method,String path,Object body,String token,String key)throws Exception{var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+env.getRequiredProperty("local.server.port")+path)).timeout(Duration.ofSeconds(30));if(token!=null){b.header("Authorization","Bearer "+token);}if(key!=null){b.header("Idempotency-Key",key);}if(body!=null){b.header("Content-Type","application/json");}return http.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));}
    private JsonNode data(HttpResponse<String> r){assertThat(r.statusCode()).as(r.body()).isIn(200,201);return json.readTree(r.body()).path("data");}
    private void expect(HttpResponse<String> r,int status,String code){assertThat(r.statusCode()).as(r.body()).isEqualTo(status);assertThat(json.readTree(r.body()).path("code").asString()).isEqualTo(code);}
}
