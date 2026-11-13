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
class PublicShopReviewsMysqlIT {
    private static final Instant NOW=Instant.parse("2026-10-23T01:00:00Z");
    private final HttpClient http=HttpClient.newHttpClient();private final List<Long> owners=new ArrayList<>(),shopIds=new ArrayList<>();
    private String hash;private byte[] pdf;
    @Autowired AccountMapper accounts;@Autowired PasswordEncoder passwords;@Autowired JdbcTemplate jdbc;
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
        reset(publicReviews,reviews,bookingRows,scheduleRows,lists,files,prices,services,shops,sessions,clock,orderEvents,keys,printRows,disk,orderRows,money,mockPayment);
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

    private long sample(long shop,User author,int rating,String visibility,Instant at){
        var o=new com.campuslife.order.entity.ServiceOrderEntity();o.setOrderNo(UUID.randomUUID().toString().replace("-",""));o.setStudentId(author.id());o.setShopId(shop);o.setOrderType("PRINT");o.setStatus("COMPLETED");o.setPaymentStatus("PAID");o.setOriginalAmount(100L);o.setDiscountAmount(0L);o.setPayableAmount(100L);o.setCurrency("CNY");o.setShopNameSnapshot("虚构统计样本");o.setCreatedAt(FileRules.utc(NOW));o.setPaymentDeadline(FileRules.utc(NOW.plusSeconds(900)));o.setPaidAt(FileRules.utc(NOW));o.setAcceptedAt(FileRules.utc(NOW));o.setStartedAt(FileRules.utc(NOW));o.setCompletedAt(FileRules.utc(NOW));o.setVersion(4L);orderRows.insert(o);
        var row=new com.campuslife.order.entity.OrderReviewEntity();row.setOrderId(o.getId());row.setShopId(shop);row.setStudentId(author.id());row.setRating(rating);row.setContent("公开样本文字😀\n换行");row.setVisibility(visibility);row.setCreatedAt(FileRules.utc(at));reviews.insert(row);return row.getId();
    }
    private HttpResponse<String> publicPage(long shop,String query,String token)throws Exception{return call("GET","/api/v1/shops/"+shop+"/reviews"+query,null,token);}
    private JsonNode page(long shop)throws Exception{return data(publicPage(shop,"",null));}
    private JsonNode publicDetail(long shop)throws Exception{return data(call("GET","/api/v1/shops/"+shop,null,null));}
    private void stats(long shop,long count,Double average)throws Exception{
        var detail=publicDetail(shop);assertThat(detail.path("reviewCount").asLong()).isEqualTo(count);if(average==null){assertThat(detail.path("rating").isNull()).isTrue();}else{assertThat(detail.path("rating").asDouble()).isEqualTo(average);}
        var list=data(call("GET","/api/v1/shops?size=100",null,null));JsonNode found=null;for(var item:list.path("items")){if(item.path("id").asString().equals(Long.toString(shop))){found=item;break;}}assertThat(found).isNotNull();assertThat(found.path("rating")).isEqualTo(detail.path("rating"));assertThat(found.path("reviewCount")).isEqualTo(detail.path("reviewCount"));
    }
    @ParameterizedTest @ValueSource(strings={"BOOKING","PRINT"})
    void actualOrderReviewIsAnonymousPublicAndHasOnlyFiveFields(String type)throws Exception{var d=done(type);var original=data(review(d,4,"  真实完成😀\n保留换行  "));var before=life(d);var p=page(d.f().merchant().shop());assertThat(p.path("total").asLong()).isEqualTo(1);assertThat(p.path("page").asInt()).isEqualTo(1);assertThat(p.path("size").asInt()).isEqualTo(20);var row=p.path("items").get(0);assertThat(row.propertyNames()).containsExactlyInAnyOrder("reviewId","rating","content","reviewerNickname","createdAt");assertThat(row.path("reviewId")).isEqualTo(original.path("id"));assertThat(row.path("content")).isEqualTo(original.path("content"));assertThat(row.path("createdAt")).isEqualTo(original.path("createdAt"));assertThat(row.path("reviewerNickname").asString()).isEqualTo("虚构文件同学");stats(d.f().merchant().shop(),1,4D);assertThat(data(ownReview(d.student(),d.id()))).isEqualTo(original);assertThat(life(d)).isEqualTo(before);}
    @ParameterizedTest @CsvSource({"5;4;4,4.33","5;5;4,4.67","1;1;1;1;1;1;1;2,1.13","1,1.0","5,5.0","1;5,3.0"})
    void exactVisibleSumDividedByCountRoundsHalfUp(String values,double expected)throws Exception{var m=merchant("PRINT");var u=user("STUDENT");for(String value:values.split(";")){sample(m.shop(),u,Integer.parseInt(value),"VISIBLE",NOW);}sample(m.shop(),u,1,"HIDDEN",NOW.plusSeconds(1));stats(m.shop(),values.split(";").length,expected);var p=data(publicPage(m.shop(),"?size=1",null));assertThat(p.path("items").size()).isEqualTo(1);assertThat(p.path("total").asLong()).isEqualTo(values.split(";").length);}
    @Test void zeroAndAllHiddenReturnNullZeroAndEmptyPage()throws Exception{var m=merchant("PRINT");stats(m.shop(),0,null);assertThat(page(m.shop()).path("items").size()).isZero();var u=user("STUDENT");sample(m.shop(),u,5,"HIDDEN",NOW);stats(m.shop(),0,null);var p=page(m.shop());assertThat(p.path("total").asLong()).isZero();assertThat(p.path("totalPages").asLong()).isZero();}
    @Test void hiddenReviewStaysOwnReviewedButNeverPublicAndOtherShopDoesNotLeak()throws Exception{var d=done("PRINT");data(review(d,5,"保留本人内容"));var other=merchant("PRINT");sample(other.shop(),user("STUDENT"),1,"VISIBLE",NOW);jdbc.update("UPDATE order_review SET visibility='HIDDEN' WHERE order_id=?",d.id());assertThat(page(d.f().merchant().shop()).path("items").size()).isZero();stats(d.f().merchant().shop(),0,null);stats(other.shop(),1,1D);assertThat(data(ownReview(d.student(),d.id())).path("visibility").asString()).isEqualTo("HIDDEN");assertThat(detail(d.f(),d.id()).path("reviewed").asBoolean()).isTrue();expect(review(d,1,"重发"),409,"REVIEW_ALREADY_EXISTS");}
    @Test void currentNicknameUsesExistingPatchAndAuthorDisableOrRoleChangeDoesNotHide()throws Exception{var m=merchant("PRINT");var u=user("STUDENT");sample(m.shop(),u,5,"VISIBLE",NOW);var original=page(m.shop()).path("items").get(0);var token=login(u);data(call("PATCH","/api/v1/me","{\"nickname\":\"新的昵称😀\",\"expectedVersion\":\"0\"}",token));var renamed=page(m.shop()).path("items").get(0);assertThat(renamed.path("reviewerNickname").asString()).isEqualTo("新的昵称😀");assertThat(renamed.path("reviewId")).isEqualTo(original.path("reviewId"));assertThat(renamed.path("createdAt")).isEqualTo(original.path("createdAt"));jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?",u.id());assertThat(page(m.shop()).path("total").asLong()).isEqualTo(1);stats(m.shop(),1,5D);jdbc.update("UPDATE account SET status='ACTIVE',role='MERCHANT' WHERE id=?",u.id());assertThat(page(m.shop()).path("items").get(0).path("reviewerNickname").asString()).isEqualTo("新的昵称😀");stats(m.shop(),1,5D);}
    @ParameterizedTest @ValueSource(strings={"PENDING","REJECTED","SUSPENDED","OWNER_DISABLED","OWNER_MISSING","MERCHANT_DISABLED","MERCHANT_ROLE","MISSING"})
    void shopVisibilityIsRequiredBeforeReviewReads(String state)throws Exception{var m=merchant("PRINT");sample(m.shop(),user("STUDENT"),5,"VISIBLE",NOW);long id=m.shop();switch(state){case "PENDING","REJECTED","SUSPENDED"->jdbc.update("UPDATE shop SET approval_status=? WHERE id=?",state,id);case "OWNER_DISABLED"->jdbc.update("UPDATE shop_member SET status='DISABLED' WHERE shop_id=?",id);case "OWNER_MISSING"->jdbc.update("DELETE FROM shop_member WHERE shop_id=?",id);case "MERCHANT_DISABLED"->jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?",m.user().id());case "MERCHANT_ROLE"->jdbc.update("UPDATE account SET role='STUDENT' WHERE id=?",m.user().id());case "MISSING"->id=Long.MAX_VALUE;}clearInvocations(publicReviews);expect(publicPage(id,"",null),404,"RESOURCE_NOT_FOUND");verify(publicReviews,never()).count(anyLong());verify(publicReviews,never()).page(anyLong(),anyInt(),anyLong());}
    @ParameterizedTest @ValueSource(strings={"OFF","HOURS","NO_SERVICE","CATEGORY_DISABLED"})
    void nonSalesReadinessDoesNotHidePublicReviews(String state)throws Exception{var m=merchant("PRINT");sample(m.shop(),user("STUDENT"),4,"VISIBLE",NOW);switch(state){case "OFF"->jdbc.update("UPDATE shop SET business_enabled=0 WHERE id=?",m.shop());case "HOURS"->jdbc.update("DELETE FROM shop_business_hour WHERE shop_id=?",m.shop());case "NO_SERVICE"->{jdbc.update("DELETE FROM print_price_rule WHERE service_id=?",m.service());jdbc.update("DELETE FROM service_item WHERE id=?",m.service());}case "CATEGORY_DISABLED"->jdbc.update("UPDATE shop_category SET enabled=0 WHERE code='PRINT'");}try{assertThat(page(m.shop()).path("total").asLong()).isEqualTo(1);stats(m.shop(),1,4D);}finally{jdbc.update("UPDATE shop_category SET enabled=1 WHERE code='PRINT'");}}
    @ParameterizedTest @ValueSource(strings={"0","01","-1","+1","x","9223372036854775808"})
    void shopIdsAreCanonical(String id)throws Exception{expect(call("GET","/api/v1/shops/"+id+"/reviews",null,null),400,"VALIDATION_FAILED");}
    @ParameterizedTest @ValueSource(strings={"?page=0","?page=-1","?page=","?page=1.0","?page=x","?page=2147483648","?size=0","?size=101","?size=","?size=1.0","?size=2147483648"})
    void paginationValidatesBeforeVisibility(String query)throws Exception{expect(publicPage(Long.MAX_VALUE,query,null),400,"VALIDATION_FAILED");}
    @Test void pagesUseTimeThenIdDescendingAndLongOffset()throws Exception{var m=merchant("PRINT");var u=user("STUDENT");var ids=new ArrayList<Long>();for(int i=0;i<5;i++){ids.add(sample(m.shop(),u,i+1,"VISIBLE",NOW));}var newer=sample(m.shop(),u,4,"VISIBLE",NOW.plusMillis(1));var a=data(publicPage(m.shop(),"?size=2",null));assertThat(a.path("total").asLong()).isEqualTo(6);assertThat(a.path("totalPages").asLong()).isEqualTo(3);assertThat(a.path("items").get(0).path("reviewId").asString()).isEqualTo(Long.toString(newer));assertThat(a.path("items").get(1).path("reviewId").asString()).isEqualTo(ids.get(4).toString());var b=data(publicPage(m.shop(),"?page=2&size=2",null));assertThat(b.path("items").get(0).path("reviewId").asString()).isEqualTo(ids.get(3).toString());var empty=data(publicPage(m.shop(),"?page=2147483647&size=100",null));assertThat(empty.path("items").size()).isZero();assertThat(empty.path("total").asLong()).isEqualTo(6);data(publicPage(m.shop(),"?page=0001&size=01",null));}
    @ParameterizedTest @ValueSource(strings={"INVALID","REVOKED","EXPIRED","DISABLED"})
    void anonymousAllowedButExplicitBadCredentialsStillFail(String kind)throws Exception{var m=merchant("PRINT");var u=user("STUDENT");sample(m.shop(),u,5,"VISIBLE",NOW);var token=login(u);int status=401;String code="TOKEN_INVALID";switch(kind){case "INVALID"->token="invalid";case "REVOKED"->{invalidate(u,kind);code="SESSION_REVOKED";}case "EXPIRED"->{invalidate(u,kind);code="SESSION_EXPIRED";}case "DISABLED"->{invalidate(u,kind);status=403;code="ACCOUNT_DISABLED";}}expect(publicPage(m.shop(),"",token),status,code);assertThat(page(m.shop()).path("total").asLong()).isEqualTo(1);}
    @Test void onlyExactGetIsAnonymousAndQueriesCannotSwitchShopScope()throws Exception{var a=merchant("PRINT");var b=merchant("PRINT");sample(a.shop(),user("STUDENT"),5,"VISIBLE",NOW);sample(b.shop(),user("STUDENT"),1,"VISIBLE",NOW);assertThat(data(publicPage(a.shop(),"?shopId="+b.shop()+"&studentId=1",null)).path("items").get(0).path("rating").asInt()).isEqualTo(5);expect(call("POST","/api/v1/shops/"+a.shop()+"/reviews",null,null),401,"AUTH_REQUIRED");expect(call("GET","/api/v1/orders/1/review",null,null),401,"AUTH_REQUIRED");expect(call("GET","/api/v1/shops/"+a.shop()+"/reviews/1",null,null),401,"AUTH_REQUIRED");}
    @Test void shopListUsesOnePageBatchAndEmptyPageDoesNotQueryAggregates()throws Exception{var a=merchant("PRINT");var b=merchant("PRINT");var u=user("STUDENT");sample(a.shop(),u,5,"VISIBLE",NOW);sample(b.shop(),u,2,"VISIBLE",NOW);clearInvocations(publicReviews);var p=data(call("GET","/api/v1/shops?size=100",null,null));verify(publicReviews,times(1)).ratings(argThat(ids->ids.size()==2&&ids.containsAll(List.of(a.shop(),b.shop()))));verify(publicReviews,never()).page(anyLong(),anyInt(),anyLong());assertThat(p.path("items").size()).isEqualTo(2);clearInvocations(publicReviews);var empty=data(call("GET","/api/v1/shops?page=2147483647&size=100",null,null));assertThat(empty.path("items").size()).isZero();verify(publicReviews,never()).ratings(anyList());}
    @ParameterizedTest @ValueSource(strings={"INSERT","HIDE","NICKNAME","SHOP"})
    void reviewPageKeepsQualificationCountRowsAndNicknameInOneRealSnapshot(String change)throws Exception{var m=merchant("PRINT");var u=user("STUDENT");var id=sample(m.shop(),u,5,"VISIBLE",NOW);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var real=sqlSession.getMapper(PublicReviewMapper.class);doAnswer(i->{long n=real.count(i.getArgument(0));entered.countDown();await(release);return n;}).when(publicReviews).count(eq(m.shop()));try(var pool=Executors.newVirtualThreadPerTaskExecutor()){var reading=pool.submit(()->publicPage(m.shop(),"",null));await(entered);switch(change){case "INSERT"->sample(m.shop(),u,1,"VISIBLE",NOW.plusSeconds(1));case "HIDE"->jdbc.update("UPDATE order_review SET visibility='HIDDEN' WHERE id=?",id);case "NICKNAME"->jdbc.update("UPDATE account SET nickname='新昵称' WHERE id=?",u.id());case "SHOP"->jdbc.update("UPDATE shop SET approval_status='SUSPENDED' WHERE id=?",m.shop());}release.countDown();var old=data(reading.get(15,TimeUnit.SECONDS));assertThat(old.path("total").asLong()).isEqualTo(1);assertThat(old.path("items").size()).isEqualTo(1);assertThat(old.path("items").get(0).path("reviewerNickname").asString()).isEqualTo("虚构文件同学");}finally{release.countDown();}reset(publicReviews);if(change.equals("SHOP")){expect(publicPage(m.shop(),"",null),404,"RESOURCE_NOT_FOUND");}else{var current=page(m.shop());assertThat(current.path("total").asLong()).isEqualTo(change.equals("INSERT")?2:change.equals("HIDE")?0:1);if(change.equals("NICKNAME")){assertThat(current.path("items").get(0).path("reviewerNickname").asString()).isEqualTo("新昵称");}}}
    @ParameterizedTest @CsvSource({"DETAIL,INSERT","DETAIL,HIDE","DETAIL,SHOP","LIST,INSERT","LIST,HIDE","LIST,SHOP"})
    void catalogStatsAndQualificationUseTheSameRealSnapshot(String surface,String change)throws Exception{var m=merchant("PRINT");var u=user("STUDENT");var id=sample(m.shop(),u,5,"VISIBLE",NOW);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var real=sqlSession.getMapper(PublicReviewMapper.class);doAnswer(i->{entered.countDown();await(release);return real.ratings(i.getArgument(0));}).when(publicReviews).ratings(anyList());String path=surface.equals("DETAIL")?"/api/v1/shops/"+m.shop():"/api/v1/shops";try(var pool=Executors.newVirtualThreadPerTaskExecutor()){var reading=pool.submit(()->call("GET",path,null,null));await(entered);if(change.equals("INSERT")){sample(m.shop(),u,1,"VISIBLE",NOW.plusSeconds(1));}else if(change.equals("HIDE")){jdbc.update("UPDATE order_review SET visibility='HIDDEN' WHERE id=?",id);}else{jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?",m.user().id());}release.countDown();var old=data(reading.get(15,TimeUnit.SECONDS));if(surface.equals("LIST")){old=old.path("items").get(0);}assertThat(old.path("rating").asDouble()).isEqualTo(5);assertThat(old.path("reviewCount").asLong()).isEqualTo(1);}finally{release.countDown();}reset(publicReviews);if(change.equals("SHOP")){expect(call("GET","/api/v1/shops/"+m.shop(),null,null),404,"RESOURCE_NOT_FOUND");}else{stats(m.shop(),change.equals("INSERT")?2:0,change.equals("INSERT")?3D:null);}}
    @Test void ordinaryReadDoesNotBlockActualReviewSubmission()throws Exception{var d=done("PRINT");var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var real=sqlSession.getMapper(PublicReviewMapper.class);doAnswer(i->{long n=real.count(i.getArgument(0));entered.countDown();await(release);return n;}).when(publicReviews).count(eq(d.f().merchant().shop()));try(var pool=Executors.newVirtualThreadPerTaskExecutor()){var reading=pool.submit(()->publicPage(d.f().merchant().shop(),"",null));await(entered);data(pool.submit(()->review(d,5,"新评价")).get(5,TimeUnit.SECONDS));release.countDown();var old=data(reading.get(15,TimeUnit.SECONDS));assertThat(old.path("total").asLong()).isZero();assertThat(old.path("items").size()).isZero();}finally{release.countDown();}reset(publicReviews);stats(d.f().merchant().shop(),1,5D);}
    @Test void publicReadsNeedNoBusinessLocksOrInstanceGuardAndPerformNoIo()throws Exception{var f=fixture();var id=sample(f.merchant().shop(),f.student(),5,"VISIBLE",NOW);var order=jdbc.queryForObject("SELECT order_id FROM order_review WHERE id=?",Long.class,id);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var tx=new TransactionTemplate(manager);var guard=storage.lock(f.file().getStorageKey());guard.lock();clearInvocations(disk);try(var pool=Executors.newVirtualThreadPerTaskExecutor()){var holder=pool.submit(()->tx.execute(s->{jdbc.queryForList("SELECT id FROM account WHERE id=? FOR UPDATE",f.student().id());jdbc.queryForList("SELECT id FROM shop WHERE id=? FOR UPDATE",f.merchant().shop());jdbc.queryForList("SELECT id FROM service_order WHERE id=? FOR UPDATE",order);jdbc.queryForList("SELECT id FROM file_asset WHERE id=? FOR UPDATE",f.file().getId());entered.countDown();await(release);return null;}));await(entered);data(pool.submit(()->publicPage(f.merchant().shop(),"",null)).get(5,TimeUnit.SECONDS));pool.submit(()->publicDetail(f.merchant().shop())).get(5,TimeUnit.SECONDS);data(pool.submit(()->call("GET","/api/v1/shops",null,null)).get(5,TimeUnit.SECONDS));verify(disk,never()).lock(anyString());verify(disk,never()).open(anyString(),anyString(),anyLong());release.countDown();holder.get(10,TimeUnit.SECONDS);}finally{release.countDown();guard.unlock();}}
    @ParameterizedTest @ValueSource(strings={"COUNT","PAGE","RATINGS"})
    void databaseReadFailureIsNotSilentlyReplacedWithNullOrZero(String query)throws Exception{var m=merchant("PRINT");sample(m.shop(),user("STUDENT"),5,"VISIBLE",NOW);switch(query){case "COUNT"->doThrow(new org.springframework.dao.CannotAcquireLockException("injected")).when(publicReviews).count(anyLong());case "PAGE"->doThrow(new org.springframework.dao.CannotAcquireLockException("injected")).when(publicReviews).page(anyLong(),anyInt(),anyLong());case "RATINGS"->doThrow(new org.springframework.dao.CannotAcquireLockException("injected")).when(publicReviews).ratings(anyList());}var response=query.equals("RATINGS")?call("GET","/api/v1/shops/"+m.shop(),null,null):publicPage(m.shop(),"",null);expect(response,503,"SERVICE_TEMPORARILY_UNAVAILABLE");assertThat(json.readTree(response.body()).path("data").isNull()).isTrue();}
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
