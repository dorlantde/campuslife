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
class PrintQuoteMysqlIT {
    private static final Instant NOW=Instant.parse("2026-10-23T01:00:00Z");
    private final HttpClient http=HttpClient.newHttpClient();private final List<Long> owners=new ArrayList<>(),shopIds=new ArrayList<>();
    private String hash;private byte[] pdf;
    @Autowired AccountMapper accounts;@Autowired PasswordEncoder passwords;@Autowired JdbcTemplate jdbc;
    @Autowired Environment env;@Autowired ObjectMapper json;@Autowired FileCleanup cleanup;@Autowired LocalFileStorage storage;
    @Autowired ShopMapper shopWriter;@Autowired PrintQuoteService quotes;@Autowired PlatformTransactionManager manager;
    @Autowired org.mybatis.spring.SqlSessionTemplate sqlSession;
    @MockitoSpyBean FileAssetMapper files;@MockitoSpyBean PrintPriceRuleMapper prices;
    @MockitoSpyBean ServiceItemMapper services;@MockitoSpyBean PublicShopMapper shops;@MockitoSpyBean AuthSessionMapper sessions;@MockitoSpyBean Clock clock;
    @BeforeAll void prepare()throws Exception{hash=passwords.encode("Quote_Test123!");pdf=PdfSamples.pdf(5);}
    @BeforeEach void freeze(){fixed(NOW);}
    @AfterEach void cleanupOwned()throws Exception {
        reset(files,prices,services,shops,sessions,clock);
        for(long id:owners){for(var row:jdbc.queryForList("SELECT storage_key FROM file_asset WHERE owner_id=?",id)){storage.delete(storage.storageId(),row.get("storage_key").toString());}jdbc.update("DELETE FROM file_asset WHERE owner_id=?",id);}
        for(long id:shopIds){jdbc.update("DELETE p FROM print_price_rule p JOIN service_item s ON s.id=p.service_id WHERE s.shop_id=?",id);jdbc.update("DELETE FROM service_item WHERE shop_id=?",id);jdbc.update("DELETE FROM shop_business_hour WHERE shop_id=?",id);jdbc.update("DELETE FROM shop_review WHERE shop_id=?",id);jdbc.update("DELETE FROM shop_member WHERE shop_id=?",id);jdbc.update("DELETE FROM shop WHERE id=?",id);}
        for(long id:owners){jdbc.update("DELETE FROM auth_session WHERE account_id=?",id);jdbc.update("DELETE FROM account WHERE id=?",id);}
        owners.clear();shopIds.clear();
    }
    @ParameterizedTest @CsvSource({"BW,SINGLE,10,10,100","BW,DOUBLE,20,6,200","COLOR,SINGLE,30,10,300","COLOR,DOUBLE,40,6,400"})
    void realUploadAndEachConfiguredSpecUseOwnPriceVersion(String color,String side,long unit,long sheets,long amount)throws Exception {
        var merchant=merchant("PRINT");var student=user("STUDENT");var token=login(student);var file=data(upload(token,"虚构报价.pdf","application/pdf",pdf));
        var body=body(merchant,file);body.put("colorMode",color);body.put("sideMode",side);
        var before=jdbc.queryForMap("SELECT * FROM file_asset WHERE id=?",file.path("id").asString());
        long orders=jdbc.queryForObject("SELECT COUNT(*) FROM service_order",Long.class),events=jdbc.queryForObject("SELECT COUNT(*) FROM order_event",Long.class),payments=jdbc.queryForObject("SELECT COUNT(*) FROM payment_record",Long.class);
        var response=quote(token,body);expect(response,200,"OK");var value=data(response);
        assertThat(value.size()).isEqualTo(17);assertThat(value.path("userCouponId").isNull()).isTrue();assertThat(value.path("pageCount").asInt()).isEqualTo(5);assertThat(value.path("copies").asInt()).isEqualTo(2);
        assertThat(value.path("sheetCount").asLong()).isEqualTo(sheets);assertThat(value.path("unitPriceAmount").asLong()).isEqualTo(unit);
        assertThat(value.path("originalAmount").asLong()).isEqualTo(amount);assertThat(value.path("payableAmount").asLong()).isEqualTo(amount);assertThat(value.path("discountAmount").asLong()).isZero();assertThat(value.path("currency").asString()).isEqualTo("CNY");
        assertThat(value.path("serviceId").asString()).isEqualTo(Long.toString(merchant.service()));assertThat(value.path("shopId").asString()).isEqualTo(Long.toString(merchant.shop()));assertThat(value.path("fileId").asString()).isEqualTo(file.path("id").asString());
        assertThat(value.path("priceVersion").asString()).isEqualTo("7");assertThat(value.path("priceRuleId").isString()).isTrue();
        assertThat(jdbc.queryForObject("SELECT id FROM print_price_rule WHERE service_id=? AND color_mode=? AND side_mode=?",Long.class,merchant.service(),color,side).toString()).isEqualTo(value.path("priceRuleId").asString());
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");assertThat(response.body()).doesNotContain("storageKey","storageId","sha256","passwordHash");
        assertThat(jdbc.queryForMap("SELECT * FROM file_asset WHERE id=?",file.path("id").asString())).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM service_order",Long.class)).isEqualTo(orders);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_event",Long.class)).isEqualTo(events);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_record",Long.class)).isEqualTo(payments);
        assertThat(data(quote(token,body))).isEqualTo(value);
    }
    @Test void actualFiveBillionCentQuoteRemainsExactJsonInteger()throws Exception {
        var merchant=merchant("PRINT");var token=login(user("STUDENT"));var file=data(upload(token,"500-pages.pdf","application/pdf",PdfSamples.pdf(500)));
        jdbc.update("UPDATE print_price_rule SET price_per_impression=100000 WHERE service_id=?",merchant.service());
        var body=body(merchant,file);body.put("copies",100);
        var value=data(quote(token,body));assertThat(value.path("payableAmount").isIntegralNumber()).isTrue();assertThat(value.path("payableAmount").asLong()).isEqualTo(5000000000L);assertThat(value.path("sheetCount").asLong()).isEqualTo(25000);
    }
    @ParameterizedTest @MethodSource("invalidBodies")
    void requestRequiresExactSixFieldsAndStrictTypes(String raw)throws Exception {
        expect(call("POST","/api/v1/print-quotes",raw,login(user("STUDENT"))),400,"VALIDATION_FAILED");
    }
    static Stream<String> invalidBodies() {
        var json=new ObjectMapper();String base="{\"serviceId\":\"1\",\"fileId\":\"2\",\"paperSize\":\"A4\",\"colorMode\":\"BW\",\"sideMode\":\"DOUBLE\",\"copies\":2}";
        var result=new ArrayList<String>();
        for(String key:List.of("serviceId","fileId","paperSize","colorMode","sideMode","copies")) {
            var absent=(tools.jackson.databind.node.ObjectNode)json.readTree(base);absent.remove(key);result.add(absent.toString());
            var nil=(tools.jackson.databind.node.ObjectNode)json.readTree(base);nil.putNull(key);result.add(nil.toString());
        }
        for(String value:List.of("\"2\"","2.0","2e0","0","-1","101","true","{}","[]","999999999999999999999999")){result.add(base.replace("\"copies\":2","\"copies\":"+value));}
        result.add(base.replace("\"serviceId\":\"1\"","\"serviceId\":1"));result.add(base.replace("\"serviceId\":\"1\"","\"serviceId\":\"9223372036854775808\""));
        for(String value:List.of("01","0"," 2","+2","2.0")){result.add(base.replace("\"fileId\":\"2\"","\"fileId\":\""+value+"\""));}
        result.add(base.replace("A4","A3"));result.add(base.replace("BW","bw"));result.add(base.replace("DOUBLE","DOUBLE "));result.add(base.replace("\"BW\"","true"));
        for(String field:List.of("pageCount","ownerId","shopId","unitPriceAmount","priceVersion","payableAmount")){result.add(base.replace("}",",\""+field+"\":1}"));}
        result.add("[]");result.add("null");result.add("{");return result.stream();
    }
    @ParameterizedTest @ValueSource(strings={"ANONYMOUS","INVALID","MERCHANT","ADMIN","DISABLED","REVOKED","EXPIRED"})
    void authenticationAndRoleGateQuoteBeforeResourceReads(String kind)throws Exception {
        String token=null;int status=403;String code="ROLE_FORBIDDEN";
        if(kind.equals("ANONYMOUS")){status=401;code="AUTH_REQUIRED";}else if(kind.equals("INVALID")){status=401;code="TOKEN_INVALID";token="invalid";}
        else{var a=user(Set.of("MERCHANT","ADMIN").contains(kind)?kind:"STUDENT");token=login(a);if(Set.of("DISABLED","REVOKED","EXPIRED").contains(kind)){invalidate(a,kind);}if(kind.equals("DISABLED")){code="ACCOUNT_DISABLED";}if(kind.equals("REVOKED")){code="SESSION_REVOKED";status=401;}if(kind.equals("EXPIRED")){code="SESSION_EXPIRED";status=401;}}
        clearInvocations(files,services,shops,prices);
        expect(quote(token,Map.of("serviceId","1","fileId","2","paperSize","A4","colorMode","BW","sideMode","DOUBLE","copies",2)),status,code);
        verifyNoInteractions(files,services,shops,prices);
    }
    @ParameterizedTest @ValueSource(strings={"OTHER","MISSING","UPLOADING","DELETING","DELETED","EXPIRED"})
    void fileIsolationAndLifecyclePrecedeEvenUnavailableTarget(String state)throws Exception {
        var student=user("STUDENT");var token=login(student);var file=data(upload(token,"test.pdf","application/pdf",pdf));String id=file.path("id").asString();
        if(state.equals("OTHER")){token=login(user("STUDENT"));}
        if(state.equals("MISSING")){id="9223372036854775807";}
        if(state.equals("EXPIRED")){jdbc.update("UPDATE file_asset SET expires_at=? WHERE id=?",FileRules.utc(NOW),id);}
        if(state.equals("DELETED")){jdbc.update("UPDATE file_asset SET status='DELETED',deleted_at=? WHERE id=?",FileRules.utc(NOW),id);}
        if(Set.of("UPLOADING","DELETING").contains(state)){jdbc.update("UPDATE file_asset SET status=? WHERE id=?",state,id);}
        var body=new LinkedHashMap<String,Object>(Map.of("serviceId","9223372036854775807","fileId",id,"paperSize","A4","colorMode","BW","sideMode","DOUBLE","copies",2));
        expect(quote(token,body),Set.of("OTHER","MISSING","UPLOADING").contains(state)?404:410,Set.of("OTHER","MISSING","UPLOADING").contains(state)?"RESOURCE_NOT_FOUND":"FILE_EXPIRED");
        var other=login(user("STUDENT"));body.put("fileId",file.path("id").asString());expect(quote(other,body),404,"RESOURCE_NOT_FOUND");
    }
    @ParameterizedTest @ValueSource(ints={-1,0,1})
    void fileExpiryUsesExactBoundary(int milliseconds)throws Exception {
        var merchant=merchant("PRINT");var token=login(user("STUDENT"));var file=data(upload(token,"test.pdf","application/pdf",pdf));
        jdbc.update("UPDATE file_asset SET expires_at=? WHERE id=?",FileRules.utc(NOW.plusMillis(milliseconds)),file.path("id").asString());
        var response=quote(token,body(merchant,file));expect(response,milliseconds>0?200:410,milliseconds>0?"OK":"FILE_EXPIRED");
    }
    @ParameterizedTest @ValueSource(strings={"SERVICE_MISSING","SERVICE_OFF","HAIRCUT","SHOP_CATEGORY","SHOP_SUSPENDED","OWNER_DISABLED","MERCHANT_DISABLED","MERCHANT_ROLE"})
    void onlyVisiblePrintServiceCanBeQuoted(String change)throws Exception {
        var merchant=merchant(change.equals("HAIRCUT")?"HAIRCUT":"PRINT");var token=login(user("STUDENT"));var file=data(upload(token,"test.pdf","application/pdf",pdf));var body=body(merchant,file);
        switch(change) {
            case "SERVICE_MISSING"->body.put("serviceId","9223372036854775807");
            case "SERVICE_OFF"->jdbc.update("UPDATE service_item SET enabled=0 WHERE id=?",merchant.service());
            case "SHOP_CATEGORY"->jdbc.update("UPDATE shop SET category_id=(SELECT id FROM shop_category WHERE code='HAIRCUT') WHERE id=?",merchant.shop());
            case "SHOP_SUSPENDED"->jdbc.update("UPDATE shop SET approval_status='SUSPENDED' WHERE id=?",merchant.shop());
            case "OWNER_DISABLED"->jdbc.update("UPDATE shop_member SET status='DISABLED' WHERE shop_id=?",merchant.shop());
            case "MERCHANT_DISABLED"->jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?",merchant.user().id());
            case "MERCHANT_ROLE"->jdbc.update("UPDATE account SET role='STUDENT' WHERE id=?",merchant.user().id());
        }
        expect(quote(token,body),404,"RESOURCE_NOT_FOUND");
    }
    @ParameterizedTest @ValueSource(strings={"MISSING","DISABLED"})
    void unavailableSpecNeverFallsBackToReferencePrice(String state)throws Exception {
        var merchant=merchant("PRINT");var token=login(user("STUDENT"));var file=data(upload(token,"test.pdf","application/pdf",pdf));
        if(state.equals("MISSING")){jdbc.update("DELETE FROM print_price_rule WHERE service_id=? AND color_mode='BW' AND side_mode='DOUBLE'",merchant.service());}
        else{jdbc.update("UPDATE print_price_rule SET enabled=0 WHERE service_id=? AND color_mode='BW' AND side_mode='DOUBLE'",merchant.service());}
        expect(quote(token,body(merchant,file)),409,"PRINT_SPEC_UNAVAILABLE");
    }
    @Test void closedRestingAndCategoryDisabledShopsCanQuoteWithoutDiskReadsOrRenewal()throws Exception {
        var merchant=merchant("PRINT");var token=login(user("STUDENT"));var file=data(upload(token,"test.pdf","application/pdf",pdf));var row=row(file);
        var category=jdbc.queryForObject("SELECT enabled FROM shop_category WHERE code='PRINT'",Boolean.class);
        try {
            jdbc.update("UPDATE shop_category SET enabled=0 WHERE code='PRINT'");
            // Default closed/no hours. Physical absence is not a metadata quote failure.
            storage.delete(storage.storageId(),row.getStorageKey());assertThat(data(quote(token,body(merchant,file))).path("payableAmount").asLong()).isEqualTo(200);
            jdbc.update("UPDATE shop SET business_enabled=1 WHERE id=?",merchant.shop());
            jdbc.update("INSERT INTO shop_business_hour (shop_id,weekday,start_time,end_time) VALUES (?,1,'01:00:00','02:00:00')",merchant.shop());
            assertThat(data(quote(token,body(merchant,file))).path("payableAmount").asLong()).isEqualTo(200);
            jdbc.update("UPDATE file_asset SET expires_at=NULL WHERE id=?",row.getId());assertThat(data(quote(token,body(merchant,file))).path("payableAmount").asLong()).isEqualTo(200);
            assertThat(files.selectById(row.getId()).getExpiresAt()).isNull();
        }finally{jdbc.update("UPDATE shop_category SET enabled=? WHERE code='PRINT'",category);}
    }
    @ParameterizedTest @ValueSource(strings={"PRICE","SERVICE","OWNER","SHOP","FILE_CLEANUP","SESSION"})
    void realConcurrentChangeAfterSnapshotOnlyAffectsNextRequest(String change)throws Exception {
        var merchant=merchant("PRINT");var student=user("STUDENT");var token=login(student);var file=row(data(upload(token,"test.pdf","application/pdf",pdf)));
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var real=sqlSession.getMapper(FileAssetMapper.class);
        doAnswer(i->{var result=real.selectById(file.getId());entered.countDown();await(release);return result;}).when(files).selectById(file.getId());
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var future=pool.submit(()->quote(token,body(merchant,file.getId().toString())));await(entered);
            try {
                switch(change) {
                    case "PRICE"->jdbc.update("UPDATE print_price_rule SET price_per_impression=21,version=8 WHERE service_id=? AND color_mode='BW' AND side_mode='DOUBLE'",merchant.service());
                    case "SERVICE"->jdbc.update("UPDATE service_item SET enabled=0 WHERE id=?",merchant.service());
                    case "OWNER"->jdbc.update("UPDATE shop_member SET status='DISABLED' WHERE shop_id=?",merchant.shop());
                    case "SHOP"->jdbc.update("UPDATE shop SET approval_status='SUSPENDED' WHERE id=?",merchant.shop());
                    case "SESSION"->invalidate(student,"REVOKED");
                    case "FILE_CLEANUP"->{jdbc.update("UPDATE file_asset SET expires_at=? WHERE id=?",FileRules.utc(NOW.minusSeconds(1)),file.getId());cleanup.runOnce();assertThat(object(file)).doesNotExist();}
                }
            }finally{release.countDown();}
            var first=data(future.get(15,TimeUnit.SECONDS));assertThat(first.path("payableAmount").asLong()).isEqualTo(200);assertThat(first.path("priceVersion").asString()).isEqualTo("7");
            reset(files);
            var next=quote(token,body(merchant,file.getId().toString()));
            if(change.equals("PRICE")){var value=data(next);assertThat(value.path("payableAmount").asLong()).isEqualTo(210);assertThat(value.path("priceVersion").asString()).isEqualTo("8");}
            else{expect(next,change.equals("SESSION")?401:change.equals("FILE_CLEANUP")?410:404,change.equals("SESSION")?"SESSION_REVOKED":change.equals("FILE_CLEANUP")?"FILE_EXPIRED":"RESOURCE_NOT_FOUND");}
        }finally{release.countDown();}
    }
    @ParameterizedTest @ValueSource(strings={"SESSION","FILE"})
    void timeIsCheckedAfterReadsInsteadOfReturningExpiredQuote(String expiry)throws Exception {
        var merchant=merchant("PRINT");var token=login(user("STUDENT"));var file=row(data(upload(token,"test.pdf","application/pdf",pdf)));
        if(expiry.equals("FILE")){jdbc.update("UPDATE file_asset SET expires_at=? WHERE id=?",FileRules.utc(NOW.plusSeconds(1)),file.getId());}
        var real=sqlSession.getMapper(PrintPriceRuleMapper.class);
        doAnswer(i->{var result=real.list(merchant.service());fixed(NOW.plusSeconds(expiry.equals("SESSION")?7200:1));return result;}).when(prices).list(merchant.service());
        expect(quote(token,body(merchant,file.getId().toString())),expiry.equals("SESSION")?401:410,expiry.equals("SESSION")?"SESSION_EXPIRED":"FILE_EXPIRED");
    }
    @ParameterizedTest @ValueSource(strings={"REVOKED","DISABLED","ROLE","REMOVED"})
    void identityChangedAfterHttpAuthenticationIsRecheckedInsideQuoteSnapshot(String change)throws Exception {
        var merchant=merchant("PRINT");var student=user("STUDENT");var token=login(student);var file=data(upload(token,"test.pdf","application/pdf",pdf));
        var real=sqlSession.getMapper(AuthSessionMapper.class);
        doAnswer(i->{
            var result=real.find(i.getArgument(0),i.getArgument(1));
            if(!TransactionSynchronizationManager.isActualTransactionActive()) {
                switch(change) {
                    case "ROLE"->jdbc.update("UPDATE account SET role='MERCHANT' WHERE id=?",student.id());
                    case "REMOVED"->jdbc.update("DELETE FROM auth_session WHERE account_id=?",student.id());
                    default->invalidate(student,change);
                }
            }
            return result;
        }).when(sessions).find(eq(student.id()),anyString());
        clearInvocations(files,services,shops,prices);
        expect(quote(token,body(merchant,file)),Set.of("REVOKED","REMOVED").contains(change)?401:403,
                switch(change){case "REVOKED"->"SESSION_REVOKED";case "REMOVED"->"TOKEN_INVALID";case "DISABLED"->"ACCOUNT_DISABLED";default->"ROLE_FORBIDDEN";});
        verifyNoInteractions(files,services,shops,prices);
    }
    @Test void noBusinessLocksOrFileGuardAreTakenAndClockIsSampledOnce()throws Exception {
        var merchant=merchant("PRINT");var student=user("STUDENT");var token=login(student);var file=row(data(upload(token,"test.pdf","application/pdf",pdf)));
        var principal=new com.campuslife.auth.security.AuthPrincipal(student.id(),jdbc.queryForObject("SELECT jti FROM auth_session WHERE account_id=?",String.class,student.id()),new com.campuslife.auth.vo.AccountView(Long.toString(student.id()),student.name(),"同学","STUDENT","ACTIVE","0"));
        var tx=new TransactionTemplate(manager);var guard=storage.lock(file.getStorageKey());guard.lock();
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
            var writer=pool.submit(()->tx.execute(status->{
                jdbc.queryForList("SELECT id FROM account WHERE id IN (?,?) FOR UPDATE",student.id(),merchant.user().id());jdbc.queryForList("SELECT jti FROM auth_session WHERE account_id=? FOR UPDATE",student.id());
                jdbc.queryForList("SELECT id FROM shop WHERE id=? FOR UPDATE",merchant.shop());jdbc.queryForList("SELECT id FROM service_item WHERE id=? FOR UPDATE",merchant.service());jdbc.queryForList("SELECT id FROM print_price_rule WHERE service_id=? FOR UPDATE",merchant.service());jdbc.queryForList("SELECT id FROM file_asset WHERE id=? FOR UPDATE",file.getId());locked.countDown();await(release);return null;
            }));await(locked);
            try {
                clearInvocations(clock);
                var result=pool.submit(()->quotes.quote(principal,json.convertValue(body(merchant,file.getId().toString()),PrintQuoteRequest.class))).get(5,TimeUnit.SECONDS);
                assertThat(result.payableAmount()).isEqualTo(200);verify(clock,times(1)).instant();
            }finally{release.countDown();}
            writer.get(10,TimeUnit.SECONDS);
        }finally{guard.unlock();}
    }
    private record Merchant(User user,long shop,long service){}
    private Merchant merchant(String type) {
        var owner=user("MERCHANT");var shop=new ShopEntity();shop.setCategoryId(jdbc.queryForObject("SELECT id FROM shop_category WHERE code=?",Long.class,type));shop.setName("虚构报价店");shop.setCampusCode("XINGHAI_MAIN");shop.setLocationText("虚构校区");shop.setDescription("");shop.setApprovalStatus("APPROVED");shop.setBusinessEnabled(false);shop.setVersion(3L);shopWriter.insert(shop);shopIds.add(shop.getId());
        jdbc.update("INSERT INTO shop_member(shop_id,account_id,status) VALUES (?,?,'ACTIVE')",shop.getId(),owner.id());
        var service=new ServiceItemEntity();service.setShopId(shop.getId());service.setServiceType(type);service.setName("模拟打印服务");service.setDescription("");service.setPriceAmount(99L);service.setDurationMinutes(type.equals("PRINT")?null:30);service.setEnabled(true);service.setVersion(5L);services.insert(service);
        if(type.equals("PRINT")){int amount=0;for(String color:List.of("BW","COLOR")){for(String side:List.of("SINGLE","DOUBLE")){jdbc.update("INSERT INTO print_price_rule(service_id,paper_size,color_mode,side_mode,price_per_impression,enabled,version) VALUES (?,'A4',?,?,?,1,7)",service.getId(),color,side,amount+=10);}}}
        return new Merchant(owner,shop.getId(),service.getId());
    }
    private LinkedHashMap<String,Object> body(Merchant merchant,JsonNode file){return body(merchant,file.path("id").asString());}
    private LinkedHashMap<String,Object> body(Merchant merchant,String file){return new LinkedHashMap<>(Map.of("serviceId",Long.toString(merchant.service()),"fileId",file,"paperSize","A4","colorMode","BW","sideMode","DOUBLE","copies",2));}
    private HttpResponse<String> quote(String token,Object body)throws Exception{return call("POST","/api/v1/print-quotes",json.writeValueAsString(body),token);}
    private record User(long id,String name){}
    private User user(String role){var a=new AccountEntity();a.setUsername("quote_"+UUID.randomUUID().toString().replace("-","").substring(0,22));a.setNickname("虚构文件同学");a.setPasswordHash(hash);a.setRole(role);a.setStatus("ACTIVE");a.setVersion(0L);accounts.insert(a);owners.add(a.getId());return new User(a.getId(),a.getUsername());}
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
