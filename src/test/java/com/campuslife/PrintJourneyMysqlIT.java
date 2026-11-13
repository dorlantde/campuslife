package com.campuslife;

import com.campuslife.admin.bootstrap.AdminAccountInitializer;
import com.campuslife.auth.mapper.AccountMapper;
import com.campuslife.file.PdfSamples;
import com.campuslife.file.storage.LocalFileStorage;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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

/** HTTP creates all business data; only controlled ADMIN bootstrap, assertions and cleanup bypass HTTP. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "campuslife.mock-payment.enabled=true","campuslife.booking.expiry-enabled=false",
        "campuslife.print.expiry-enabled=false","campuslife.files.cleanup-enabled=false"})
@ActiveProfiles("mysql")
class PrintJourneyMysqlIT {
    private static final Instant NOW=Instant.parse("2026-10-26T01:00:00Z");
    private static final String PASSWORD="PrintJourney_123!";
    private final HttpClient http=HttpClient.newHttpClient();
    private final List<Long> ownedAccounts=new ArrayList<>(),ownedShops=new ArrayList<>();private byte[] pdf;
    @Autowired JdbcTemplate jdbc;@Autowired Environment env;@Autowired ObjectMapper json;
    @Autowired AccountMapper accounts;@Autowired PasswordEncoder passwords;@Autowired LocalFileStorage storage;
    @MockitoSpyBean Clock clock;
    private record User(String id,String name){}
    private record Fixture(User student,String st,String mt,String at,String shop,String service,String file,JsonNode quote){}
    @BeforeEach void prepare()throws Exception{doReturn(NOW).when(clock).instant();pdf=PdfSamples.pdf(5);assertThat(storage.root().toAbsolutePath().normalize()).isEqualTo(Path.of(env.getRequiredProperty("FILE_TEST_STORAGE_ROOT")).toAbsolutePath().normalize());}
    @AfterEach void cleanup()throws Exception{
        reset(clock);
        for(long shop:ownedShops){
            jdbc.update("DELETE FROM order_review WHERE shop_id=?",shop);
            for(String table:List.of("refund_record","payment_record","idempotency_record","order_event","print_detail")){jdbc.update("DELETE x FROM "+table+" x JOIN service_order o ON o.id=x.order_id WHERE o.shop_id=?",shop);}
            jdbc.update("DELETE FROM service_order WHERE shop_id=?",shop);
            jdbc.update("DELETE p FROM print_price_rule p JOIN service_item s ON s.id=p.service_id WHERE s.shop_id=?",shop);
            jdbc.update("DELETE FROM service_item WHERE shop_id=?",shop);jdbc.update("DELETE FROM shop_business_hour WHERE shop_id=?",shop);
            jdbc.update("DELETE FROM shop_review WHERE shop_id=?",shop);jdbc.update("DELETE FROM shop_member WHERE shop_id=?",shop);jdbc.update("DELETE FROM shop WHERE id=?",shop);
        }
        for(long id:ownedAccounts){for(var row:jdbc.queryForList("SELECT storage_id,storage_key FROM file_asset WHERE owner_id=?",id)){storage.delete(row.get("storage_id").toString(),row.get("storage_key").toString());}jdbc.update("DELETE FROM file_asset WHERE owner_id=?",id);jdbc.update("DELETE FROM auth_session WHERE account_id=?",id);jdbc.update("DELETE FROM account WHERE id=?",id);}
    }
    private Fixture setup()throws Exception{
        var student=register();var st=login(student);var an=name();var aid=new AdminAccountInitializer(accounts,passwords).create(an,"虚构打印验收管理员",PASSWORD);ownedAccounts.add(Long.parseLong(aid));var at=login(new User(aid,an));
        String category=null;for(var c:data(call("GET","/api/v1/shop-categories",null,null,null))){if(c.path("code").asString().equals("PRINT")){category=c.path("id").asString();}}assertThat(category).isNotNull();
        var mn=name();var app=data(call("POST","/api/v1/merchant-applications",Map.of("username",mn,"password",PASSWORD,"nickname","虚构打印店主","categoryId",category,"shopName","虚构打印闭环店","locationText","虚构校园打印服务点"),null,null));
        var shop=app.path("shopId").asString();var mid=app.path("accountId").asString();ownedShops.add(Long.parseLong(shop));ownedAccounts.add(Long.parseLong(mid));var mt=login(new User(mid,mn));
        var pending=data(call("GET","/api/v1/admin/shops/"+shop,null,at,null));var approved=data(call("POST","/api/v1/admin/shops/"+shop+"/approve",Map.of("expectedVersion",pending.path("version").asString()),at,null));
        var weekday=NOW.atZone(ZoneId.of("Asia/Shanghai")).getDayOfWeek().getValue();var hours=data(call("PUT","/api/v1/merchant/shop/business-hours",Map.of("expectedVersion",approved.path("version").asString(),"intervals",List.of(Map.of("weekday",weekday,"startTime","08:00","endTime","22:00"))),mt,null));
        data(call("PATCH","/api/v1/merchant/shop",Map.of("expectedVersion",hours.path("version").asString(),"businessEnabled",true),mt,null));
        var serviceBody=new LinkedHashMap<String,Object>();serviceBody.put("serviceType","PRINT");serviceBody.put("name","模拟A4打印");serviceBody.put("priceAmount",99);serviceBody.put("durationMinutes",null);serviceBody.put("enabled",true);
        var service=data(call("POST","/api/v1/merchant/services",serviceBody,mt,null)).path("id").asString();
        data(call("PUT","/api/v1/merchant/services/"+service+"/print-prices",Map.of("rules",List.of(rule(20,null))),mt,null));
        var upload=upload(st);assertThat(upload.statusCode()).as(upload.body()).isEqualTo(201);var file=data(upload);assertThat(file.path("pageCount").asInt()).isEqualTo(5);assertThat(Instant.parse(file.path("expiresAt").asString())).isEqualTo(NOW.plus(Duration.ofHours(24)));
        var quoted=data(call("POST","/api/v1/print-quotes",quoteBody(service,file.path("id").asString()),st,null));
        assertThat(quoted.path("unitPriceAmount").asLong()).isEqualTo(20);assertThat(quoted.path("sheetCount").asLong()).isEqualTo(6);assertThat(quoted.path("payableAmount").asLong()).isEqualTo(200);assertThat(quoted.path("pageCount").asInt()).isEqualTo(5);
        assertThat(data(call("GET","/api/v1/files/"+file.path("id").asString(),null,st,null))).isEqualTo(file);
        return new Fixture(student,st,mt,at,shop,service,file.path("id").asString(),quoted);
    }
    private Map<String,Object> rule(int amount,String version){var r=new LinkedHashMap<String,Object>();r.put("paperSize","A4");r.put("colorMode","BW");r.put("sideMode","DOUBLE");r.put("pricePerImpression",amount);r.put("enabled",true);r.put("expectedVersion",version);return r;}
    private Map<String,Object> quoteBody(String service,String file){return Map.of("serviceId",service,"fileId",file,"paperSize","A4","colorMode","BW","sideMode","DOUBLE","copies",2);}
    private Map<String,Object> createBody(Fixture f,JsonNode quote){var b=new LinkedHashMap<>(quoteBody(f.service(),f.file()));b.put("expectedPriceVersion",quote.path("priceVersion").asString());b.put("expectedPayableAmount",quote.path("payableAmount").asLong());return b;}
    private User register()throws Exception{var n=name();var u=data(call("POST","/api/v1/auth/register",Map.of("username",n,"password",PASSWORD,"nickname","虚构打印同学"),null,null));var id=u.path("id").asString();ownedAccounts.add(Long.parseLong(id));return new User(id,n);}
    private String name(){return "pj_"+UUID.randomUUID().toString().replace("-","").substring(0,22);}
    private String login(User u)throws Exception{return data(call("POST","/api/v1/auth/login",Map.of("username",u.name(),"password",PASSWORD),null,null)).path("accessToken").asString();}
    private HttpResponse<String> create(Fixture f,String key,JsonNode quote)throws Exception{return call("POST","/api/v1/orders/prints",createBody(f,quote),f.st(),key);}
    private String order(Fixture f,String key)throws Exception{var r=create(f,key,f.quote());assertThat(r.statusCode()).as(r.body()).isEqualTo(201);return data(r).path("id").asString();}
    private JsonNode own(Fixture f,String id)throws Exception{return data(call("GET","/api/v1/orders/"+id,null,f.st(),null));}
    private JsonNode file(Fixture f)throws Exception{return data(call("GET","/api/v1/files/"+f.file(),null,f.st(),null));}
    private JsonNode pay(Fixture f,String id)throws Exception{return data(call("POST","/api/v1/orders/"+id+"/payments/mock",null,f.st(),null));}
    private JsonNode act(Fixture f,String id,String action,Object body)throws Exception{return data(call("POST","/api/v1/merchant/orders/"+id+"/"+action,body,f.mt(),null));}
    private long count(String table,String id){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE order_id=?",Long.class,id);}
    private void retained(Fixture f)throws Exception{assertThat(Instant.parse(file(f).path("expiresAt").asString())).isEqualTo(NOW.plus(Duration.ofDays(7)));}
    private HttpResponse<byte[]> content(Fixture f,String token)throws Exception{return http.send(request("/api/v1/files/"+f.file()+"/content",token).GET().build(),HttpResponse.BodyHandlers.ofByteArray());}
    private void downloaded(Fixture f,String token)throws Exception{var r=content(f,token);assertThat(r.statusCode()).as(new String(r.body(),StandardCharsets.UTF_8)).isEqualTo(200);assertThat(r.body()).isEqualTo(pdf);assertThat(r.headers().firstValue("Content-Type").orElse("")).contains("application/pdf");assertThat(r.headers().firstValue("Cache-Control").orElse("")).contains("no-store");}
    @Test void uploadQuotePrintPickupAndReviewUseOnlyPublicBusinessEntrypoints()throws Exception{
        var f=setup();downloaded(f,f.st());assertThat(content(f,f.mt()).statusCode()).isEqualTo(404);assertThat(content(f,f.at()).statusCode()).isEqualTo(403);var outsider=login(register());expect(call("GET","/api/v1/files/"+f.file(),null,outsider,null),404,"RESOURCE_NOT_FOUND");assertThat(content(f,outsider).statusCode()).isEqualTo(404);
        var id=order(f,"print-journey");assertThat(file(f).path("expiresAt").isNull()).isTrue();assertThat(own(f,id).path("printing").path("progress").asString()).isEqualTo("QUEUED");var replay=create(f,"print-journey",f.quote());assertThat(replay.statusCode()).isEqualTo(200);assertThat(replay.headers().firstValue("Idempotency-Replayed")).contains("true");assertThat(data(replay).path("id").asString()).isEqualTo(id);
        assertThat(data(call("GET","/api/v1/orders?orderType=PRINT",null,f.st(),null)).path("items").get(0).path("id").asString()).isEqualTo(id);assertThat(data(call("GET","/api/v1/merchant/orders?orderType=PRINT",null,f.mt(),null)).path("items").get(0).path("id").asString()).isEqualTo(id);
        var paid=pay(f,id);assertThat(pay(f,id)).isEqualTo(paid);assertThat(content(f,f.mt()).statusCode()).isEqualTo(404);var accepted=act(f,id,"accept",null);assertThat(act(f,id,"accept",null)).isEqualTo(accepted);downloaded(f,f.mt());
        assertThat(act(f,id,"start",null).path("printing").path("progress").asString()).isEqualTo("PRINTING");downloaded(f,f.mt());assertThat(act(f,id,"ready-for-pickup",null).path("printing").path("progress").asString()).isEqualTo("READY_FOR_PICKUP");downloaded(f,f.mt());
        var completed=data(call("POST","/api/v1/orders/"+id+"/pickup",null,f.st(),null));assertThat(completed.path("status").asString()).isEqualTo("COMPLETED");assertThat(completed.path("paymentStatus").asString()).isEqualTo("PAID");assertThat(completed.path("printing").path("progress").asString()).isEqualTo("PICKED_UP");assertThat(completed.path("version").asString()).isEqualTo("5");assertThat(data(call("POST","/api/v1/orders/"+id+"/pickup",null,f.st(),null))).isEqualTo(completed);retained(f);assertThat(content(f,f.mt()).statusCode()).isEqualTo(404);downloaded(f,f.st());
        var before=data(call("GET","/api/v1/orders/"+id+"/events",null,f.st(),null));var actions=new ArrayList<String>();for(var e:before){actions.add(e.path("action").asString());}assertThat(actions).containsExactly("CREATE","PAYMENT","ACCEPT","START","READY_FOR_PICKUP","PICKUP");assertThat(data(call("GET","/api/v1/merchant/orders/"+id+"/events",null,f.mt(),null))).isEqualTo(before);
        data(call("POST","/api/v1/orders/"+id+"/review",Map.of("rating",5,"content","模拟打印取件完成😀"),f.st(),null));assertThat(own(f,id).path("reviewed").asBoolean()).isTrue();assertThat(own(f,id).path("version").asString()).isEqualTo("5");assertThat(data(call("GET","/api/v1/orders/"+id+"/events",null,f.st(),null))).isEqualTo(before);retained(f);var shop=data(call("GET","/api/v1/shops/"+f.shop(),null,null,null));assertThat(shop.path("rating").asDouble()).isEqualTo(5);assertThat(shop.path("reviewCount").asLong()).isEqualTo(1);assertThat(count("payment_record",id)).isEqualTo(1);assertThat(count("refund_record",id)).isZero();
    }
    @Test void lastUnpaidReferenceStartsRetentionAndReplaysDoNotRenewIt()throws Exception{var f=setup();var first=order(f,"one");var second=order(f,"two");data(call("POST","/api/v1/orders/"+first+"/cancel",null,f.st(),null));assertThat(file(f).path("expiresAt").isNull()).isTrue();data(call("POST","/api/v1/orders/"+second+"/cancel",null,f.st(),null));retained(f);doReturn(NOW.plusSeconds(1)).when(clock).instant();assertThat(data(create(f,"one",f.quote())).path("id").asString()).isEqualTo(first);data(call("POST","/api/v1/orders/"+second+"/cancel",null,f.st(),null));retained(f);assertThat(count("payment_record",first)+count("payment_record",second)).isZero();assertThat(count("refund_record",first)+count("refund_record",second)).isZero();assertThat(content(f,f.mt()).statusCode()).isEqualTo(404);downloaded(f,f.st());}
    @Test void rejectPaidPrintRefundsOnceWithoutGrantingContent()throws Exception{var f=setup();var id=order(f,"reject");pay(f,id);var closed=act(f,id,"reject",Map.of("reason","模拟设备不可用"));assertThat(closed.path("status").asString()).isEqualTo("CLOSED");assertThat(closed.path("paymentStatus").asString()).isEqualTo("REFUNDED");assertThat(closed.path("printing").path("progress").asString()).isEqualTo("ABORTED");assertThat(act(f,id,"reject",Map.of("reason","模拟设备不可用"))).isEqualTo(closed);assertThat(count("payment_record",id)).isEqualTo(1);assertThat(count("refund_record",id)).isEqualTo(1);assertThat(count("order_event",id)).isEqualTo(3);retained(f);assertThat(content(f,f.mt()).statusCode()).isEqualTo(404);}
    @Test void acceptedPrintCancellationRevokesTheExistingDownloadGrant()throws Exception{var f=setup();var id=order(f,"cancel");pay(f,id);act(f,id,"accept",null);downloaded(f,f.mt());var closed=act(f,id,"cancel",Map.of("reason","模拟接单后发现无法履约"));assertThat(closed.path("paymentStatus").asString()).isEqualTo("REFUNDED");assertThat(act(f,id,"cancel",Map.of("reason","模拟接单后发现无法履约"))).isEqualTo(closed);assertThat(content(f,f.mt()).statusCode()).isEqualTo(404);retained(f);assertThat(count("refund_record",id)).isEqualTo(1);assertThat(count("order_event",id)).isEqualTo(4);}
    @Test void adminClosesReadyPrintPreservingStageTimesWithOneRefund()throws Exception{var f=setup();var id=order(f,"exception");pay(f,id);act(f,id,"accept",null);act(f,id,"start",null);var ready=act(f,id,"ready-for-pickup",null);expect(call("POST","/api/v1/merchant/orders/"+id+"/cancel",Map.of("reason","普通取消已经关闭"),f.mt(),null),409,"ORDER_STATE_CONFLICT");var checked=data(call("GET","/api/v1/admin/orders/"+id,null,f.at(),null));var req=Map.of("expectedVersion",checked.path("version").asString(),"reason","模拟打印异常，已核实");var closed=data(call("POST","/api/v1/admin/orders/"+id+"/close-exception",req,f.at(),null));assertThat(closed.path("status").asString()).isEqualTo("CLOSED");assertThat(closed.path("paymentStatus").asString()).isEqualTo("REFUNDED");assertThat(closed.path("printing").path("progress").asString()).isEqualTo("ABORTED");assertThat(closed.path("startedAt")).isEqualTo(ready.path("startedAt"));assertThat(closed.path("printing").path("readyAt")).isEqualTo(ready.path("printing").path("readyAt"));assertThat(data(call("POST","/api/v1/admin/orders/"+id+"/close-exception",req,f.at(),null))).isEqualTo(closed);assertThat(count("refund_record",id)).isEqualTo(1);retained(f);assertThat(content(f,f.mt()).statusCode()).isEqualTo(404);expect(call("POST","/api/v1/orders/"+id+"/pickup",null,f.st(),null),409,"ORDER_STATE_CONFLICT");}
    @Test void changedPriceRequiresARequoteAndFailedCreationDoesNotProtectFile()throws Exception{var f=setup();var before=file(f);data(call("PUT","/api/v1/merchant/services/"+f.service()+"/print-prices",Map.of("rules",List.of(rule(30,f.quote().path("priceVersion").asString()))),f.mt(),null));expect(create(f,"old-quote",f.quote()),409,"QUOTE_CHANGED");assertThat(file(f)).isEqualTo(before);var current=data(call("POST","/api/v1/print-quotes",quoteBody(f.service(),f.file()),f.st(),null));assertThat(current.path("payableAmount").asLong()).isEqualTo(300);var id=data(create(f,"new-quote",current)).path("id").asString();assertThat(own(f,id).path("payableAmount").asLong()).isEqualTo(300);assertThat(file(f).path("expiresAt").isNull()).isTrue();}
    private HttpResponse<String> upload(String token)throws Exception{var boundary="PrintJourney"+UUID.randomUUID().toString().replace("-","");var out=new ByteArrayOutputStream();out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"journey.pdf\"\r\nContent-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.UTF_8));out.write(pdf);out.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.US_ASCII));return http.send(request("/api/v1/files",token).header("Content-Type","multipart/form-data; boundary="+boundary).POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));}
    private HttpRequest.Builder request(String path,String token){var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+env.getRequiredProperty("local.server.port")+path)).timeout(Duration.ofSeconds(30));if(token!=null){b.header("Authorization","Bearer "+token);}return b;}
    private HttpResponse<String> call(String method,String path,Object body,String token,String key)throws Exception{var b=request(path,token);if(body!=null){b.header("Content-Type","application/json");}if(key!=null){b.header("Idempotency-Key",key);}return http.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));}
    private JsonNode data(HttpResponse<String> r){assertThat(r.statusCode()).as(r.body()).isIn(200,201);return json.readTree(r.body()).path("data");}
    private void expect(HttpResponse<String> r,int status,String code){assertThat(r.statusCode()).as(r.body()).isEqualTo(status);assertThat(json.readTree(r.body()).path("code").asString()).isEqualTo(code);}
}
