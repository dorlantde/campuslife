package com.campuslife;

import com.campuslife.auth.entity.AccountEntity;
import com.campuslife.auth.mapper.AccountMapper;
import com.campuslife.file.PdfSamples;
import com.campuslife.file.entity.FileAssetEntity;
import com.campuslife.file.mapper.FileAssetMapper;
import com.campuslife.file.service.*;
import com.campuslife.file.storage.LocalFileStorage;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.*;
import java.util.stream.Stream;
import com.campuslife.auth.mapper.AuthSessionMapper;
import com.campuslife.merchant.entity.*;
import com.campuslife.merchant.mapper.*;
import com.campuslife.order.dto.PrintQuoteRequest;
import com.campuslife.order.service.PrintQuoteService;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="campuslife.files.cleanup-enabled=false")
@ActiveProfiles("mysql")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminAccountStatusDomainMysqlIT {
    private static final Instant NOW=Instant.parse("2026-10-23T01:00:00Z");
    private final HttpClient http=HttpClient.newHttpClient();private final List<Long> owners=new ArrayList<>(),shopIds=new ArrayList<>();
    private String hash;private byte[] pdf;
    @MockitoSpyBean AccountMapper accounts;@Autowired PasswordEncoder passwords;@Autowired JdbcTemplate jdbc;
    @Autowired Environment env;@Autowired ObjectMapper json;@Autowired FileCleanup cleanup;private LocalFileStorage storage;
    @Autowired ShopMapper shopWriter;@Autowired PrintQuoteService quotes;@Autowired PlatformTransactionManager manager;
    @Autowired org.mybatis.spring.SqlSessionTemplate sqlSession;
    @Autowired com.campuslife.order.service.PrintOrderService printService; @Autowired com.campuslife.order.service.PrintExpiryJob expiry;
    @MockitoSpyBean com.campuslife.order.mapper.OrderMapper orderRows;
    @MockitoSpyBean com.campuslife.order.mapper.OrderEventMapper orderEvents; @MockitoSpyBean com.campuslife.order.mapper.IdempotencyMapper keys; @MockitoSpyBean com.campuslife.order.mapper.PrintOrderMapper printRows; @MockitoSpyBean LocalFileStorage disk;
    @MockitoSpyBean FileAssetMapper files;@MockitoSpyBean PrintPriceRuleMapper prices;
    @MockitoSpyBean ServiceItemMapper services;@MockitoSpyBean PublicShopMapper shops;@MockitoSpyBean AuthSessionMapper sessions;@MockitoSpyBean Clock clock;
    @MockitoSpyBean com.campuslife.order.mapper.OrderListMapper lists;
    @MockitoSpyBean com.campuslife.order.mapper.OrderReviewMapper reviews;
    @MockitoSpyBean com.campuslife.order.mapper.BookingMapper bookingRows;
    @Autowired BarberMapper barberRows; @Autowired BarberServiceMapper bindingRows; @MockitoSpyBean BarberScheduleMapper scheduleRows;
    @MockitoSpyBean PublicReviewMapper publicReviews;
    @BeforeAll void prepare()throws Exception{hash=passwords.encode("Quote_Test123!");pdf=PdfSamples.pdf(5);}
    @MockitoSpyBean com.campuslife.order.mapper.MoneyMapper money; @MockitoSpyBean com.campuslife.order.service.MockPaymentPolicy mockPayment;
    @Autowired com.campuslife.order.service.PrintOrderTransactions printTransactions;
    @BeforeEach void freeze(){storage=disk;fixed(NOW);doReturn(true).when(mockPayment).enabled();}
    @AfterEach void cleanupOwned()throws Exception {
        reset(accounts,publicReviews,reviews,bookingRows,scheduleRows,lists,files,prices,services,shops,sessions,clock,orderEvents,keys,printRows,disk,orderRows,money,mockPayment);
        for(long id:owners){jdbc.update("DELETE FROM account_status_change WHERE actor_account_id=? OR target_account_id=?",id,id);}
        for(long id:owners){jdbc.update("DELETE FROM order_review WHERE student_id=?",id);}
        for(long id:owners){jdbc.update("DELETE f FROM refund_record f JOIN service_order o ON o.id=f.order_id WHERE o.student_id=?",id);jdbc.update("DELETE p FROM payment_record p JOIN service_order o ON o.id=p.order_id WHERE o.student_id=?",id);}
        for(long id:owners){jdbc.update("DELETE e FROM order_event e JOIN service_order o ON o.id=e.order_id WHERE o.student_id=?",id);jdbc.update("DELETE FROM idempotency_record WHERE account_id=?",id);jdbc.update("DELETE p FROM print_detail p JOIN service_order o ON o.id=p.order_id WHERE o.student_id=?",id);jdbc.update("DELETE b FROM booking_detail b JOIN service_order o ON o.id=b.order_id WHERE o.student_id=?",id);jdbc.update("DELETE FROM service_order WHERE student_id=?",id);}
        for(long id:owners){for(var row:jdbc.queryForList("SELECT storage_key FROM file_asset WHERE owner_id=?",id)){storage.delete(storage.storageId(),row.get("storage_key").toString());}jdbc.update("DELETE FROM file_asset WHERE owner_id=?",id);}
        for(long id:shopIds){jdbc.update("DELETE s FROM barber_schedule s JOIN barber b ON b.id=s.barber_id WHERE b.shop_id=?",id);jdbc.update("DELETE g FROM barber_day_guard g JOIN barber b ON b.id=g.barber_id WHERE b.shop_id=?",id);jdbc.update("DELETE x FROM barber_service x JOIN barber b ON b.id=x.barber_id WHERE b.shop_id=?",id);jdbc.update("DELETE FROM barber WHERE shop_id=?",id);jdbc.update("DELETE p FROM print_price_rule p JOIN service_item s ON s.id=p.service_id WHERE s.shop_id=?",id);jdbc.update("DELETE FROM service_item WHERE shop_id=?",id);jdbc.update("DELETE FROM shop_business_hour WHERE shop_id=?",id);jdbc.update("DELETE FROM shop_review WHERE shop_id=?",id);jdbc.update("DELETE FROM shop_member WHERE shop_id=?",id);jdbc.update("DELETE FROM shop WHERE id=?",id);}
        for(long id:owners){jdbc.update("DELETE FROM auth_session WHERE account_id=?",id);jdbc.update("DELETE FROM account WHERE id=?",id);}
        owners.clear();shopIds.clear();
    }

    private record Fixture(Merchant merchant,User student,String token,FileAssetEntity file,LinkedHashMap<String,Object> body){}
    private Fixture fixture()throws Exception{
        var m=merchant("PRINT");var u=user("STUDENT");var t=login(u);var f=row(data(upload(t,"虚构材料.pdf","application/pdf",pdf)));var b=body(m,f.getId().toString());b.put("expectedPriceVersion","7");b.put("expectedPayableAmount",200L);return new Fixture(m,u,t,f,b);
    }
    private HttpResponse<String> create(Fixture f,String key)throws Exception{return create(f.token(),f.body(),key);}
    private HttpResponse<String> create(String token,Object body,String key)throws Exception{
        var r=request("/api/v1/orders/prints",token).header("Content-Type","application/json");if(key!=null){r.header("Idempotency-Key",key);}return http.send(r.POST(HttpRequest.BodyPublishers.ofString(body instanceof String?(String)body:json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
    private HttpResponse<String> cancel(Fixture f,String id)throws Exception{return call("POST","/api/v1/orders/"+id+"/cancel",null,f.token());}
    private Map<String,Object> fileFact(Fixture f){return jdbc.queryForMap("SELECT * FROM file_asset WHERE id=?",f.file().getId());}
    private void expiryAt(Fixture f,Instant t){jdbc.update("UPDATE file_asset SET expires_at=? WHERE id=?",FileRules.utc(t),f.file().getId());}
    private void retained(Fixture f,Instant t){assertThat(files.selectById(f.file().getId()).getExpiresAt()).isEqualTo(t==null?null:FileRules.utc(t));}
    private void noOrders(Fixture f,Map<String,Object> before){assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM service_order WHERE student_id=?",Long.class,f.student().id())).isZero();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_record WHERE account_id=?",Long.class,f.student().id())).isZero();assertThat(fileFact(f)).isEqualTo(before);}

    private HttpResponse<String> pay(Fixture f,String id)throws Exception{return call("POST","/api/v1/orders/"+id+"/payments/mock",null,f.token());}
    private JsonNode detail(Fixture f,String id)throws Exception{return data(call("GET","/api/v1/orders/"+id,null,f.token()));}
    private long count(String table,String id){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE order_id=?",Long.class,id);}
    private Map<String,Object> facts(Fixture f,String id){return Map.of("order",jdbc.queryForMap("SELECT * FROM service_order WHERE id=?",id),"print",jdbc.queryForMap("SELECT * FROM print_detail WHERE order_id=?",id),"file",fileFact(f),"events",jdbc.queryForList("SELECT * FROM order_event WHERE order_id=? ORDER BY event_seq",id),"payment",jdbc.queryForList("SELECT * FROM payment_record WHERE order_id=?",id),"refund",jdbc.queryForList("SELECT * FROM refund_record WHERE order_id=?",id));}
    private String order(Fixture f)throws Exception{return data(create(f,"pay-test")).path("id").asString();}
    private void assertPaid(Fixture f,String id)throws Exception{var o=detail(f,id);assertThat(o.path("status").asString()).isEqualTo("PENDING_ACCEPTANCE");assertThat(o.path("paymentStatus").asString()).isEqualTo("PAID");assertThat(o.path("printing").path("progress").asString()).isEqualTo("QUEUED");assertThat(o.path("refund").isNull()).isTrue();assertThat(count("payment_record",id)).isEqualTo(1);assertThat(count("refund_record",id)).isZero();retained(f,null);}
    private void assertRefunded(Fixture f,String id,String reason,Instant at)throws Exception{var o=detail(f,id);assertThat(o.path("status").asString()).isEqualTo("CLOSED");assertThat(o.path("paymentStatus").asString()).isEqualTo("REFUNDED");assertThat(o.path("closeReason").asString()).isEqualTo(reason);assertThat(o.path("printing").path("progress").asString()).isEqualTo("ABORTED");assertThat(o.path("payment").path("amount")).isEqualTo(o.path("refund").path("amount"));assertThat(count("payment_record",id)).isEqualTo(1);assertThat(count("refund_record",id)).isEqualTo(1);assertThat(count("order_event",id)).isEqualTo(3);if(at!=null){retained(f,at.plus(Duration.ofDays(7)));}}



    private HttpResponse<String> action(String token,String id,String suffix,String body)throws Exception{return call("POST","/api/v1/merchant/orders/"+id+"/"+suffix,body,token);}
    private HttpResponse<String> start(String token,String id)throws Exception{return action(token,id,"start",null);}
    private HttpResponse<String> ready(String token,String id)throws Exception{return action(token,id,"ready-for-pickup",null);}
    private String accepted(Fixture f)throws Exception{var id=order(f);data(pay(f,id));data(action(login(f.merchant().user()),id,"accept",null));return id;}
    private HttpResponse<byte[]> download(Fixture f,String token)throws Exception{return http.send(request("/api/v1/files/"+f.file().getId()+"/content",token).GET().build(),HttpResponse.BodyHandlers.ofByteArray());}
    private void pdf(Fixture f,String token)throws Exception{var response=download(f,token);assertThat(response.statusCode()).as(new String(response.body(),StandardCharsets.UTF_8)).isEqualTo(200);assertThat(response.body()).isEqualTo(pdf);}

    private String waiting(Fixture f)throws Exception{var id=accepted(f);var t=login(f.merchant().user());data(start(t,id));data(ready(t,id));return id;}
    private HttpResponse<String> pickup(Fixture f,String id)throws Exception{return pickup(f.token(),id,null);}
    private HttpResponse<String> pickup(String token,String id,String body)throws Exception{return call("POST","/api/v1/orders/"+id+"/pickup",body,token);}

    private record Done(Fixture f,String id,String student,String merchant,boolean booking,long barber){}
    private static final Instant BOOK_START=NOW.plusSeconds(9000);
    private Done done(String type)throws Exception{return stage(type,true);}
    private Done stage(String type,boolean completed)throws Exception{
        var f=fixture();if(type.equals("PRINT")){var id=waiting(f);if(completed){data(pickup(f,id));}return new Done(f,id,f.token(),login(f.merchant().user()),false,0);}
        var m=merchant("HAIRCUT");var person=new BarberEntity();person.setShopId(m.shop());person.setDisplayName("虚构评价理发师");person.setEnabled(true);person.setVersion(0L);barberRows.insert(person);bindingRows.insert(person.getId(),m.service());var date=NOW.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();scheduleRows.insertGuard(person.getId(),date);scheduleRows.insert(person.getId(),date,FileRules.utc(NOW),FileRules.utc(NOW.plusSeconds(28800)));
        var b=json.writeValueAsString(Map.of("serviceId",Long.toString(m.service()),"barberId",person.getId().toString(),"startAt",BOOK_START.toString(),"expectedServiceVersion","5","expectedPayableAmount",99));
        var request=request("/api/v1/orders/bookings",f.token()).header("Content-Type","application/json").header("Idempotency-Key","review-booking");var id=data(http.send(request.POST(HttpRequest.BodyPublishers.ofString(b)).build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))).path("id").asString();data(pay(f,id));var mt=login(m.user());data(action(mt,id,"accept",null));fixed(BOOK_START);var st=login(f.student());mt=login(m.user());data(start(mt,id));if(completed){data(action(mt,id,"complete",null));}f=new Fixture(m,f.student(),st,f.file(),f.body());return new Done(f,id,st,mt,true,person.getId());
    }
    private HttpResponse<String> review(String token,String id,String body)throws Exception{return call("POST","/api/v1/orders/"+id+"/review",body,token);}
    private HttpResponse<String> review(Done d,int rating,String content)throws Exception{return review(d.student(),d.id(),json.writeValueAsString(Map.of("rating",rating,"content",content)));}
    private HttpResponse<String> ownReview(String token,String id)throws Exception{return call("GET","/api/v1/orders/"+id+"/review",null,token);}
    private Map<String,Object> life(Done d){var f=d.f();return Map.of("order",jdbc.queryForMap("SELECT * FROM service_order WHERE id=?",d.id()),"detail",jdbc.queryForMap(d.booking()?"SELECT * FROM booking_detail WHERE order_id=?":"SELECT * FROM print_detail WHERE order_id=?",d.id()),"file",fileFact(f),"events",jdbc.queryForList("SELECT * FROM order_event WHERE order_id=? ORDER BY event_seq",d.id()),"payment",jdbc.queryForList("SELECT * FROM payment_record WHERE order_id=?",d.id()),"refund",jdbc.queryForList("SELECT * FROM refund_record WHERE order_id=?",d.id()));}
    private long reviewCount(String id){return jdbc.queryForObject("SELECT COUNT(*) FROM order_review WHERE order_id=?",Long.class,id);}


    private HttpResponse<String> change(String token,User u,String status,long v)throws Exception{return call("PATCH","/api/v1/admin/accounts/"+u.id()+"/status",json.writeValueAsString(Map.of("status",status,"expectedVersion",Long.toString(v))),token);}
    @ParameterizedTest @ValueSource(strings={"BOOKING","PRINT"})
    void disabledStudentKeepsOrderAndMerchantCanFulfill(String type)throws Exception{Done d;if(type.equals("PRINT")){var f=fixture();var id=accepted(f);d=new Done(f,id,f.token(),login(f.merchant().user()),false,0);}else{d=stage(type,false);}var admin=login(user("ADMIN"));var before=life(d);data(change(admin,d.f().student(),"DISABLED",0));assertThat(life(d)).isEqualTo(before);expect(call("GET","/api/v1/orders/"+d.id(),null,d.student()),401,"SESSION_REVOKED");if(d.booking()){data(action(d.merchant(),d.id(),"complete",null));}else{data(start(d.merchant(),d.id()));data(ready(d.merchant(),d.id()));}assertThat(count("refund_record",d.id())).isZero();}
    @ParameterizedTest @ValueSource(strings={"APPROVED","SUSPENDED"})
    void merchantDisableAndRestoreDoNotRewriteShopOrPrintProtection(String approval)throws Exception{var f=fixture();var id=accepted(f);jdbc.update("UPDATE shop SET approval_status=? WHERE id=?",approval,f.merchant().shop());var admin=login(user("ADMIN"));var before=facts(f,id);var config=jdbc.queryForMap("SELECT * FROM shop WHERE id=?",f.merchant().shop());var members=jdbc.queryForList("SELECT * FROM shop_member WHERE shop_id=?",f.merchant().shop());data(change(admin,f.merchant().user(),"DISABLED",0));assertThat(facts(f,id)).isEqualTo(before);expect(call("GET","/api/v1/shops/"+f.merchant().shop(),null,null),404,"RESOURCE_NOT_FOUND");expect(pay(f,id),200,"OK");data(change(admin,f.merchant().user(),"ACTIVE",1));assertThat(jdbc.queryForMap("SELECT * FROM shop WHERE id=?",f.merchant().shop())).isEqualTo(config);assertThat(jdbc.queryForList("SELECT * FROM shop_member WHERE shop_id=?",f.merchant().shop())).isEqualTo(members);assertThat(facts(f,id)).isEqualTo(before);var publicResponse=call("GET","/api/v1/shops/"+f.merchant().shop(),null,null);assertThat(publicResponse.statusCode()).isEqualTo(approval.equals("APPROVED")?200:404);data(start(login(f.merchant().user()),id));}
    @Test void visibleReviewAndCompletedFileRetentionSurviveAuthorDisable()throws Exception{var d=done("PRINT");var original=data(review(d,5,"保留公开评价"));var before=life(d);var admin=login(user("ADMIN"));data(change(admin,d.f().student(),"DISABLED",0));var page=data(call("GET","/api/v1/shops/"+d.f().merchant().shop()+"/reviews",null,null));assertThat(page.path("total").asLong()).isEqualTo(1);assertThat(page.path("items").get(0).path("reviewId").asString()).isEqualTo(original.path("id").asString());assertThat(data(call("GET","/api/v1/shops/"+d.f().merchant().shop(),null,null)).path("reviewCount").asLong()).isEqualTo(1);assertThat(life(d)).isEqualTo(before);}
    @Test void paidDisabledMerchantCanBeHandledByAdminAndRefundSwitchStillApplies()throws Exception{var f=fixture();var id=accepted(f);var admin=login(user("ADMIN"));data(change(admin,f.merchant().user(),"DISABLED",0));var before=facts(f,id);doReturn(false).when(mockPayment).enabled();var body=json.writeValueAsString(Map.of("reason","经营方停用后核实异常","expectedVersion","2"));expect(call("POST","/api/v1/admin/orders/"+id+"/close-exception",body,admin),503,"MOCK_PAYMENT_DISABLED");assertThat(facts(f,id)).isEqualTo(before);doReturn(true).when(mockPayment).enabled();data(call("POST","/api/v1/admin/orders/"+id+"/close-exception",body,admin));assertThat(count("refund_record",id)).isEqualTo(1);assertThat(files.selectById(f.file().getId()).getExpiresAt()).isEqualTo(FileRules.utc(NOW.plus(Duration.ofDays(7))));}
    @Test void disabledStudentDoesNotBlockOriginalUnpaidExpiryScan()throws Exception{var f=fixture();var id=order(f);var admin=login(user("ADMIN"));var before=facts(f,id);data(change(admin,f.student(),"DISABLED",0));assertThat(facts(f,id)).isEqualTo(before);fixed(NOW.plusSeconds(900));expiry.scan();assertThat(jdbc.queryForObject("SELECT status FROM service_order WHERE id=?",String.class,id)).isEqualTo("CLOSED");assertThat(count("payment_record",id)).isZero();assertThat(count("refund_record",id)).isZero();}
    private record Merchant(User user,long shop,long service){}
    private Merchant merchant(String type) {
        var owner=user("MERCHANT");var shop=new ShopEntity();shop.setCategoryId(jdbc.queryForObject("SELECT id FROM shop_category WHERE code=?",Long.class,type));shop.setName("虚构报价店");shop.setCampusCode("XINGHAI_MAIN");shop.setLocationText("虚构校区");shop.setDescription("");shop.setApprovalStatus("APPROVED");shop.setBusinessEnabled(true);shop.setVersion(3L);shopWriter.insert(shop);shopIds.add(shop.getId());
        jdbc.update("INSERT INTO shop_member(shop_id,account_id,status) VALUES (?,?,'ACTIVE')",shop.getId(),owner.id());
        var service=new ServiceItemEntity();service.setShopId(shop.getId());service.setServiceType(type);service.setName("模拟打印服务");service.setDescription("");service.setPriceAmount(99L);service.setDurationMinutes(type.equals("PRINT")?null:30);service.setEnabled(true);service.setVersion(5L);services.insert(service);
        if(type.equals("PRINT")){int amount=0;for(String color:List.of("BW","COLOR")){for(String side:List.of("SINGLE","DOUBLE")){jdbc.update("INSERT INTO print_price_rule(service_id,paper_size,color_mode,side_mode,price_per_impression,enabled,version) VALUES (?,'A4',?,?,?,1,7)",service.getId(),color,side,amount+=10);}}}
        for(int day=1;day<=7;day++){jdbc.update("INSERT INTO shop_business_hour(shop_id,weekday,start_time,end_time) VALUES (?,?,'08:00','22:00')",shop.getId(),day);}
        return new Merchant(owner,shop.getId(),service.getId());
    }
    private LinkedHashMap<String,Object> body(Merchant merchant,JsonNode file){return body(merchant,file.path("id").asString());}
    private LinkedHashMap<String,Object> body(Merchant merchant,String file){return new LinkedHashMap<>(Map.of("serviceId",Long.toString(merchant.service()),"fileId",file,"paperSize","A4","colorMode","BW","sideMode","DOUBLE","copies",2));}
    private HttpResponse<String> quote(String token,Object body)throws Exception{return call("POST","/api/v1/print-quotes",json.writeValueAsString(body),token);}
    private record User(long id,String name){}
    private User user(String role){var a=new AccountEntity();a.setUsername("print_"+UUID.randomUUID().toString().replace("-","").substring(0,22));a.setNickname("虚构文件同学");a.setPasswordHash(hash);a.setRole(role);a.setStatus("ACTIVE");a.setVersion(0L);accounts.insert(a);owners.add(a.getId());return new User(a.getId(),a.getUsername());}
    private void invalidate(User a,String kind){if(kind.equals("DISABLED")){jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?",a.id());}else if(kind.equals("REVOKED")){jdbc.update("UPDATE auth_session SET revoked_at=? WHERE account_id=?",FileRules.utc(NOW),a.id());}else{fixed(NOW.plusSeconds(7200));}}
    private void fixed(Instant now){doReturn(now).when(clock).instant();}
    private String login(User a)throws Exception{return data(call("POST","/api/v1/auth/login",json.writeValueAsString(Map.of("username",a.name(),"password","Quote_Test123!")),null)).path("accessToken").asString();}
    private FileAssetEntity row(JsonNode view){return files.selectById(Long.parseLong(view.path("id").asString()));}
    private Path object(FileAssetEntity row){return storage.root().resolve("objects").resolve(row.getStorageKey());}
    private HttpResponse<String> call(String method,String path,String body,String token)throws Exception{var builder=request(path,token);if(body!=null){builder.header("Content-Type","application/json");}return http.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));}
    private HttpRequest.Builder request(String path,String token){var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+env.getRequiredProperty("local.server.port")+path)).timeout(Duration.ofSeconds(60));if(token!=null){builder.header("Authorization","Bearer "+token);}return builder;}
    private record Part(String name,String filename,String type,byte[] bytes){}
    private HttpResponse<String> upload(String token,String name,String type,byte[] bytes)throws Exception{return multipart(token,List.of(new Part("file",name,type,bytes)));}
    private HttpResponse<String> multipart(String token,List<Part> parts)throws Exception {
        String boundary="CampusLife"+UUID.randomUUID().toString().replace("-","");var output=new ByteArrayOutputStream();for(var part:parts){output.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\""+part.name()+"\""+(part.filename()==null?"":"; filename=\""+part.filename()+"\"")+"\r\n"+(part.type()==null?"":"Content-Type: "+part.type()+"\r\n")+"\r\n").getBytes(StandardCharsets.UTF_8));output.write(part.bytes());output.write("\r\n".getBytes(StandardCharsets.US_ASCII));}output.write(("--"+boundary+"--\r\n").getBytes(StandardCharsets.US_ASCII));return http.send(request("/api/v1/files",token).header("Content-Type","multipart/form-data; boundary="+boundary).POST(HttpRequest.BodyPublishers.ofByteArray(output.toByteArray())).build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
    private JsonNode data(HttpResponse<String> response){assertThat(response.statusCode()).as(response.body()).isIn(200,201);return json.readTree(response.body()).path("data");}
    private void expect(HttpResponse<String> response,int status,String code){assertThat(response.statusCode()).as(response.body()).isEqualTo(status);assertThat(json.readTree(response.body()).path("code").asString()).isEqualTo(code);}
    private static void await(CountDownLatch latch){try{if(!latch.await(20,TimeUnit.SECONDS)){throw new IllegalStateException("gate timeout");}}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IllegalStateException(ex);}}
}
