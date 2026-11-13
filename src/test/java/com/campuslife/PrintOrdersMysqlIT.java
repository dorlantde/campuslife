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
class PrintOrdersMysqlIT {
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
    @BeforeAll void prepare()throws Exception{hash=passwords.encode("Quote_Test123!");pdf=PdfSamples.pdf(5);}
    @BeforeEach void freeze(){storage=disk;fixed(NOW);}
    @AfterEach void cleanupOwned()throws Exception {
        reset(files,prices,services,shops,sessions,clock,orderEvents,keys,printRows,disk,orderRows);
        for(long id:owners){jdbc.update("DELETE e FROM order_event e JOIN service_order o ON o.id=e.order_id WHERE o.student_id=?",id);jdbc.update("DELETE FROM idempotency_record WHERE account_id=?",id);jdbc.update("DELETE p FROM print_detail p JOIN service_order o ON o.id=p.order_id WHERE o.student_id=?",id);jdbc.update("DELETE FROM service_order WHERE student_id=?",id);}
        for(long id:owners){for(var row:jdbc.queryForList("SELECT storage_key FROM file_asset WHERE owner_id=?",id)){storage.delete(storage.storageId(),row.get("storage_key").toString());}jdbc.update("DELETE FROM file_asset WHERE owner_id=?",id);}
        for(long id:shopIds){jdbc.update("DELETE p FROM print_price_rule p JOIN service_item s ON s.id=p.service_id WHERE s.shop_id=?",id);jdbc.update("DELETE FROM service_item WHERE shop_id=?",id);jdbc.update("DELETE FROM shop_business_hour WHERE shop_id=?",id);jdbc.update("DELETE FROM shop_review WHERE shop_id=?",id);jdbc.update("DELETE FROM shop_member WHERE shop_id=?",id);jdbc.update("DELETE FROM shop WHERE id=?",id);}
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
    @Test void actualUploadQuoteCreateFrozenHistoryAndClosedReplay()throws Exception{
        var f=fixture();var q=data(quote(f.token(),body(f.merchant(),f.file().getId().toString())));assertThat(q.path("payableAmount").asLong()).isEqualTo(200);
        var first=create(f,"demo");expect(first,201,"OK");var o=data(first);var id=o.path("id").asString();retained(f,null);
        assertThat(o.path("orderType").asString()).isEqualTo("PRINT");assertThat(o.path("booking").isNull()).isTrue();assertThat(o.path("payment").isNull()).isTrue();assertThat(o.path("refund").isNull()).isTrue();assertThat(o.path("reviewed").asBoolean()).isFalse();
        assertThat(o.path("printing").path("sheetCount").asLong()).isEqualTo(6);assertThat(o.path("printing").path("progress").asString()).isEqualTo("QUEUED");assertThat(Instant.parse(o.path("paymentDeadline").asString())).isEqualTo(NOW.plusSeconds(900));
        assertThat(first.body()).doesNotContain("storageKey","storageId","sha256","objects");
        var again=create(f,"demo");expect(again,200,"OK");assertThat(again.headers().firstValue("Idempotency-Replayed")).contains("true");assertThat(data(again)).isEqualTo(o);
        var list=data(call("GET","/api/v1/orders",null,f.token()));assertThat(list.path("total").asLong()).isEqualTo(1);assertThat(list.path("items").get(0).path("bookingStartAt").isNull()).isTrue();assertThat(list.path("items").get(0).path("printingProgress").asString()).isEqualTo("QUEUED");
        assertThat(data(call("GET","/api/v1/orders?orderType=BOOKING",null,f.token())).path("total").asLong()).isZero();assertThat(data(call("GET","/api/v1/orders?orderType=PRINT",null,f.token())).path("total").asLong()).isEqualTo(1);
        var events=data(call("GET","/api/v1/orders/"+id+"/events",null,f.token()));assertThat(events.size()).isEqualTo(1);assertThat(events.get(0).path("eventSeq").asString()).isEqualTo("0");
        jdbc.update("UPDATE shop SET approval_status='SUSPENDED',business_enabled=0,name='更名' WHERE id=?",f.merchant().shop());jdbc.update("UPDATE service_item SET enabled=0,name='改名' WHERE id=?",f.merchant().service());
        var closed=data(cancel(f,id));assertThat(closed.path("closeReason").asString()).isEqualTo("USER_CANCEL");assertThat(closed.path("printing").path("progress").asString()).isEqualTo("ABORTED");retained(f,NOW.plus(Duration.ofDays(7)));var before=fileFact(f);
        fixed(NOW.plusSeconds(60));assertThat(data(cancel(f,id))).isEqualTo(closed);storage.delete(storage.storageId(),f.file().getStorageKey());assertThat(data(create(f,"demo"))).isEqualTo(closed);assertThat(fileFact(f)).isEqualTo(before);
        jdbc.update("UPDATE file_asset SET status='DELETED',deleted_at=? WHERE id=?",FileRules.utc(NOW),f.file().getId());assertThat(data(call("GET","/api/v1/orders/"+id,null,f.token()))).isEqualTo(closed);assertThat(data(call("GET","/api/v1/orders/"+id+"/events",null,f.token())).size()).isEqualTo(2);
    }
    @ParameterizedTest @ValueSource(strings={"VERSION","AMOUNT","OFF","HOURS","SUSPENDED","SERVICE_OFF","PRICE_OFF","PRICE_MISSING","OTHER","EXPIRED","DELETING","UPLOADING","DISK","SIZE","ROOT"})
    void rejectsInvalidResourcesWithoutAnyOrderOrRetentionWrite(String kind)throws Exception{
        var f=fixture();int status=409;String code="QUOTE_CHANGED";
        switch(kind){
            case "VERSION"->f.body().put("expectedPriceVersion","6");case "AMOUNT"->f.body().put("expectedPayableAmount",201);
            case "OFF"->{jdbc.update("UPDATE shop SET business_enabled=0 WHERE id=?",f.merchant().shop());code="SHOP_UNAVAILABLE";}
            case "HOURS"->{jdbc.update("DELETE FROM shop_business_hour WHERE shop_id=?",f.merchant().shop());code="SHOP_UNAVAILABLE";}
            case "SUSPENDED"->{jdbc.update("UPDATE shop SET approval_status='SUSPENDED' WHERE id=?",f.merchant().shop());status=404;code="RESOURCE_NOT_FOUND";}
            case "SERVICE_OFF"->{jdbc.update("UPDATE service_item SET enabled=0 WHERE id=?",f.merchant().service());code="SERVICE_UNAVAILABLE";}
            case "PRICE_OFF"->{jdbc.update("UPDATE print_price_rule SET enabled=0 WHERE service_id=?",f.merchant().service());code="PRINT_SPEC_UNAVAILABLE";}
            case "PRICE_MISSING"->{jdbc.update("DELETE FROM print_price_rule WHERE service_id=?",f.merchant().service());code="PRINT_SPEC_UNAVAILABLE";}
            case "OTHER"->{jdbc.update("UPDATE file_asset SET owner_id=? WHERE id=?",f.merchant().user().id(),f.file().getId());status=404;code="RESOURCE_NOT_FOUND";}
            case "EXPIRED"->{expiryAt(f,NOW);status=410;code="FILE_EXPIRED";}
            case "DELETING"->{jdbc.update("UPDATE file_asset SET status='DELETING' WHERE id=?",f.file().getId());status=410;code="FILE_EXPIRED";}
            case "UPLOADING"->{jdbc.update("UPDATE file_asset SET status='UPLOADING' WHERE id=?",f.file().getId());status=404;code="RESOURCE_NOT_FOUND";}
            case "DISK"->{storage.delete(storage.storageId(),f.file().getStorageKey());status=503;code="FILE_STORAGE_UNAVAILABLE";}
            case "SIZE"->{Files.write(object(f.file()),new byte[]{1},StandardOpenOption.APPEND);status=503;code="FILE_STORAGE_UNAVAILABLE";}
            case "ROOT"->{jdbc.update("UPDATE file_asset SET storage_id=? WHERE id=?",UUID.randomUUID().toString(),f.file().getId());status=503;code="FILE_STORAGE_UNAVAILABLE";}
        }
        var before=fileFact(f);expect(create(f,"invalid"),status,code);noOrders(f,before);
    }
    @ParameterizedTest @ValueSource(strings={"expectedPriceVersion","expectedPayableAmount","note","copies","fileId","unknown"})
    void strictJsonTypesAndUnknownFields(String field)throws Exception{
        var f=fixture();f.body().put(field,field.equals("expectedPayableAmount")?"200":field.equals("expectedPriceVersion")?7:field.equals("note")?true:field.equals("copies")?2.0:field.equals("fileId")?1:"x");var before=fileFact(f);expect(create(f,"invalid"),400,"VALIDATION_FAILED");noOrders(f,before);
    }
    @ParameterizedTest @NullSource @ValueSource(strings={"","x!","x.","abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789abc"})
    void strictIdempotencyKey(String key)throws Exception{var f=fixture();expect(create(f,key),400,"IDEMPOTENCY_KEY_REQUIRED");}
    @ParameterizedTest @CsvSource({"08:59:59.999,409","09:00:00,201","09:59:59.999,201","10:00:00,409"})
    void shanghaiBusinessWindowIncludesStartExcludesEnd(String local,int code)throws Exception{
        fixed(NOW.minusSeconds(1800));var f=fixture();jdbc.update("DELETE FROM shop_business_hour WHERE shop_id=?",f.merchant().shop());jdbc.update("INSERT INTO shop_business_hour(shop_id,weekday,start_time,end_time) VALUES (?,5,'09:00','10:00')",f.merchant().shop());fixed(LocalDateTime.parse("2026-10-23T"+local).atZone(ZoneId.of("Asia/Shanghai")).toInstant());expect(create(f,"window"),code,code==201?"OK":"SHOP_UNAVAILABLE");
    }
    @Test void longAmountIsExactAndBookingLimitStaysIndependent()throws Exception{
        var f=fixture();var other=row(data(upload(f.token(),"500.pdf","application/pdf",PdfSamples.pdf(500))));f.body().put("fileId",other.getId().toString());f.body().put("copies",100);f.body().put("expectedPayableAmount",5000000000L);jdbc.update("UPDATE print_price_rule SET price_per_impression=100000 WHERE service_id=?",f.merchant().service());var o=data(create(f,"large"));assertThat(o.path("payableAmount").asLong()).isEqualTo(5000000000L);assertThat(o.path("printing").path("sheetCount").asLong()).isEqualTo(25000);
        expect(call("POST","/api/v1/orders/bookings",json.writeValueAsString(Map.of("serviceId","1","barberId","1","startAt","2026-10-24T01:00:00Z","expectedServiceVersion","0","expectedPayableAmount",5000000000L)),f.token()),400,"IDEMPOTENCY_KEY_REQUIRED");
    }
    @Test void oneFileAcrossShopsOnlyLastTerminalStartsSevenDaysAndNewReferenceProtectsAgain()throws Exception{
        var f=fixture();var a=data(create(f,"one")).path("id").asString();var second=merchant("PRINT");f.body().put("serviceId",Long.toString(second.service()));var b=data(create(f,"two")).path("id").asString();long v=files.selectById(f.file().getId()).getVersion();
        data(cancel(f,a));retained(f,null);assertThat(files.selectById(f.file().getId()).getVersion()).isEqualTo(v);fixed(NOW.plusSeconds(30));data(cancel(f,b));retained(f,NOW.plusSeconds(30).plus(Duration.ofDays(7)));
        fixed(NOW.plusSeconds(60));var c=data(create(f,"three")).path("id").asString();retained(f,null);data(cancel(f,c));retained(f,NOW.plusSeconds(60).plus(Duration.ofDays(7)));
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void exactDeadlineCommitsBefore409OrScannerAndRepeatedCallsNeverRenew(boolean scanner)throws Exception{
        var f=fixture();var id=data(create(f,"timeout")).path("id").asString();fixed(NOW.plusSeconds(900));
        assertThat(data(call("GET","/api/v1/orders/"+id,null,f.token())).path("status").asString()).isEqualTo("PENDING_PAYMENT");retained(f,null);
        if(scanner){jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?",f.student().id());jdbc.update("UPDATE shop SET approval_status='SUSPENDED' WHERE id=?",f.merchant().shop());assertThat(expiry.scan()).isEqualTo(1);jdbc.update("UPDATE account SET status='ACTIVE' WHERE id=?",f.student().id());}else{expect(cancel(f,id),409,"PAYMENT_DEADLINE_PASSED");}
        var before=fileFact(f);retained(f,NOW.plusSeconds(900).plus(Duration.ofDays(7)));fixed(NOW.plusSeconds(901));expect(cancel(f,id),409,"PAYMENT_DEADLINE_PASSED");assertThat(expiry.scan()).isZero();assertThat(fileFact(f)).isEqualTo(before);var ev=data(call("GET","/api/v1/orders/"+id+"/events",null,f.token()));assertThat(ev.size()).isEqualTo(2);assertThat(ev.get(1).path("actorType").asString()).isEqualTo("SYSTEM");assertThat(ev.get(1).path("actorId").isNull()).isTrue();
    }
    @ParameterizedTest @ValueSource(strings={"INSERT_DETAIL","EVENT","KEY","RETENTION"})
    void anyCreateWriteFailureRollsBackAllFacts(String point)throws Exception{
        var f=fixture();var before=fileFact(f);
        switch(point){case "INSERT_DETAIL"->doThrow(new org.springframework.dao.DataIntegrityViolationException("injected")).when(printRows).insert(any(com.campuslife.order.entity.PrintDetailEntity.class));case "EVENT"->doThrow(new IllegalStateException("injected")).when(orderEvents).append(anyLong(),anyLong(),anyString(),nullable(String.class),anyString(),anyString(),nullable(Long.class),nullable(String.class),any());case "KEY"->doThrow(new org.springframework.dao.DuplicateKeyException("injected")).when(keys).savePrint(anyLong(),anyString(),anyString(),anyLong(),any());case "RETENTION"->doThrow(new IllegalStateException("injected")).when(files).expiry(anyLong(),anyLong(),nullable(LocalDateTime.class),any());}
        assertThat(create(f,"fail").statusCode()).isIn(500,503);noOrders(f,before);
    }
    @ParameterizedTest @ValueSource(strings={"ABORT","EVENT","RETENTION"})
    void closeFailureRollsBackOrderEventAndRetention(String point)throws Exception{
        var f=fixture();var id=data(create(f,"close")).path("id").asString();var before=fileFact(f);
        switch(point){case "ABORT"->doThrow(new IllegalStateException("injected")).when(printRows).abort(anyLong());case "EVENT"->doThrow(new IllegalStateException("injected")).when(orderEvents).append(anyLong(),anyLong(),anyString(),nullable(String.class),anyString(),anyString(),nullable(Long.class),nullable(String.class),any());case "RETENTION"->doThrow(new IllegalStateException("injected")).when(files).expiry(anyLong(),anyLong(),nullable(LocalDateTime.class),any());}
        assertThat(cancel(f,id).statusCode()).isEqualTo(500);assertThat(fileFact(f)).isEqualTo(before);assertThat(jdbc.queryForObject("SELECT status FROM service_order WHERE id=?",String.class,id)).isEqualTo("PENDING_PAYMENT");assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_event WHERE order_id=?",Long.class,id)).isEqualTo(1);
    }
    @Test void normalizedNoteAndNamespaceConflictAreDeterministic()throws Exception{
        var f=fixture();f.body().put("note","  请装订  ");var a=data(create(f,"same"));f.body().put("note","请装订");assertThat(data(create(f,"same"))).isEqualTo(a);f.body().put("note","其他");expect(create(f,"same"),409,"IDEMPOTENCY_KEY_CONFLICT");
        jdbc.update("INSERT INTO idempotency_record(account_id,operation,request_key,request_hash,order_id,completed_at) VALUES (?,'CREATE_BOOKING','isolated',?,?,?)",f.student().id(),"a".repeat(64),a.path("id").asString(),FileRules.utc(NOW));expect(create(f,"isolated"),201,"OK");
    }
    @ParameterizedTest @ValueSource(strings={"CREATE_SAME","CREATE_DIFFERENT","CLOSE_BOTH","CLOSE_CREATE"})
    void concurrentRealMysqlWritersSerializeReferencesWithoutCrossShopLocks(String kind)throws Exception{
        var f=fixture();var a=kind.startsWith("CLOSE")?data(create(f,"first")).path("id").asString():null;var b=kind.equals("CLOSE_BOTH")?data(create(f,"second")).path("id").asString():null;
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var start=new CountDownLatch(1);var one=pool.submit(()->{await(start);return a==null?create(f,"parallel"):cancel(f,a);});var two=pool.submit(()->{await(start);return b!=null?cancel(f,b):create(f,kind.equals("CREATE_SAME")?"parallel":"different");});start.countDown();var r1=one.get(20,TimeUnit.SECONDS);var r2=two.get(20,TimeUnit.SECONDS);assertThat(r1.statusCode()).as(r1.body()).isIn(200,201);assertThat(r2.statusCode()).as(r2.body()).isIn(200,201);
            if(kind.equals("CREATE_SAME")){assertThat(data(r1).path("id")).isEqualTo(data(r2).path("id"));assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM service_order WHERE student_id=?",Long.class,f.student().id())).isEqualTo(1);}
            retained(f,kind.equals("CLOSE_BOTH")?NOW.plus(Duration.ofDays(7)):null);
        }
    }
    @ParameterizedTest @ValueSource(strings={"EXPIRED","REVOKED","OFF"})
    void waitForFileGuardHoldsNoDatabaseLocksAndRevalidatesAfterWait(String change)throws Exception{
        var f=fixture();var guard=storage.lock(f.file().getStorageKey());guard.lock();var seen=new CountDownLatch(1);var real=sqlSession.getMapper(FileAssetMapper.class);
        doAnswer(i->{var row=real.selectById((java.io.Serializable)i.getArgument(0));seen.countDown();return row;}).when(files).selectById((java.io.Serializable)eq(f.file().getId()));
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var future=pool.submit(()->create(f,"wait"));await(seen);
            var tx=new TransactionTemplate(manager);pool.submit(()->tx.execute(s->{jdbc.queryForList("SELECT id FROM account WHERE id=? FOR UPDATE",f.student().id());jdbc.queryForList("SELECT jti FROM auth_session WHERE account_id=? FOR UPDATE",f.student().id());jdbc.queryForList("SELECT id FROM file_asset WHERE id=? FOR UPDATE",f.file().getId());if(change.equals("EXPIRED")){expiryAt(f,NOW);}else if(change.equals("REVOKED")){invalidate(f.student(),"REVOKED");}else{jdbc.update("UPDATE shop SET business_enabled=0 WHERE id=?",f.merchant().shop());}return null;})).get(5,TimeUnit.SECONDS);
            guard.unlock();var r=future.get(20,TimeUnit.SECONDS);expect(r,change.equals("EXPIRED")?410:change.equals("REVOKED")?401:409,change.equals("EXPIRED")?"FILE_EXPIRED":change.equals("REVOKED")?"SESSION_REVOKED":"SHOP_UNAVAILABLE");
        }finally{if(guard.isHeldByCurrentThread()){guard.unlock();}}
    }
    @Test void fileIoIsOutsideTransactionAndExpiryCrossingAfterDecisionIsProtected()throws Exception{
        var f=fixture();expiryAt(f,NOW.plusMillis(1));var real=sqlSession.getMapper(com.campuslife.order.mapper.OrderEventMapper.class);
        doAnswer(i->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return i.callRealMethod();}).when(disk).open(anyString(),anyString(),anyLong());
        doAnswer(i->{var n=real.append(i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(3),i.getArgument(4),i.getArgument(5),i.getArgument(6),i.getArgument(7),i.getArgument(8));fixed(NOW.plusMillis(2));return n;}).when(orderEvents).append(anyLong(),anyLong(),anyString(),nullable(String.class),anyString(),anyString(),nullable(Long.class),nullable(String.class),any());
        expect(create(f,"cross"),201,"OK");retained(f,null);
    }
    @Test void cleanupDefensivelySkipsUnfinishedReferencesAndReclaimsAfterRetention()throws Exception{
        var f=fixture();var id=data(create(f,"ref")).path("id").asString();expiryAt(f,NOW);cleanup.runOnce();assertThat(files.selectById(f.file().getId()).getStatus()).isEqualTo("AVAILABLE");assertThat(Files.exists(object(f.file()))).isTrue();
        jdbc.update("UPDATE file_asset SET expires_at=NULL WHERE id=?",f.file().getId());data(cancel(f,id));fixed(NOW.plus(Duration.ofDays(7)));cleanup.runOnce();assertThat(files.selectById(f.file().getId()).getStatus()).isEqualTo("DELETED");assertThat(Files.exists(object(f.file()))).isFalse();
        fixed(NOW);assertThat(data(call("GET","/api/v1/orders/"+id,null,f.token())).path("status").asString()).isEqualTo("CLOSED");expect(create(f,"new"),410,"FILE_EXPIRED");
    }
    @Test void unpaidPrintSupportsMerchantReadsButNotFulfillmentOrFunds()throws Exception{
        var f=fixture();var id=data(create(f,"type")).path("id").asString();var owner=login(f.merchant().user());var admin=login(user("ADMIN"));var other=login(user("STUDENT"));
        expect(call("POST","/api/v1/orders/"+id+"/payments/mock",null,f.token()),503,"MOCK_PAYMENT_DISABLED");
        for(String suffix:List.of("","/events")){data(call("GET","/api/v1/merchant/orders/"+id+suffix,null,owner));}
        expect(call("POST","/api/v1/merchant/orders/"+id+"/accept",null,owner),409,"ORDER_STATE_CONFLICT");
        for(String suffix:List.of("/start","/ready-for-pickup")){expect(call("POST","/api/v1/merchant/orders/"+id+suffix,null,owner),409,"ORDER_STATE_CONFLICT");}
        expect(call("POST","/api/v1/merchant/orders/"+id+"/complete",null,owner),404,"RESOURCE_NOT_FOUND");
        assertThat(data(call("GET","/api/v1/admin/orders/"+id,null,admin)).path("orderType").asString()).isEqualTo("PRINT");
        for(String suffix:List.of("","/events","/cancel")){expect(call(suffix.equals("/cancel")?"POST":"GET","/api/v1/orders/"+id+suffix,null,other),404,"RESOURCE_NOT_FOUND");}
        assertThat(data(call("GET","/api/v1/merchant/orders?orderType=PRINT",null,owner)).path("total").asLong()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_record WHERE order_id=?",Long.class,id)).isZero();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refund_record WHERE order_id=?",Long.class,id)).isZero();retained(f,null);
    }
    @ParameterizedTest @ValueSource(strings={"ANONYMOUS","INVALID","MERCHANT","ADMIN","DISABLED","REVOKED","EXPIRED"})
    void printingCreationRequiresCurrentActiveStudent(String kind)throws Exception{
        var f=fixture();String token=f.token();int status=403;String code="ROLE_FORBIDDEN";
        switch(kind){case "ANONYMOUS"->{token=null;status=401;code="AUTH_REQUIRED";}case "INVALID"->{token="invalid";status=401;code="TOKEN_INVALID";}case "MERCHANT","ADMIN"->token=login(user(kind));case "DISABLED"->{invalidate(f.student(),kind);code="ACCOUNT_DISABLED";}case "REVOKED"->{invalidate(f.student(),kind);status=401;code="SESSION_REVOKED";}case "EXPIRED"->{invalidate(f.student(),kind);status=401;code="SESSION_EXPIRED";}}
        var before=fileFact(f);expect(create(token,f.body(),"auth"),status,code);noOrders(f,before);
    }
    @ParameterizedTest @ValueSource(strings={"FIRST_CREATE","NEW_REFERENCE","LAST_CLOSE"})
    void fileVersionOverflowOnlyRejectsActualRetentionChanges(String phase)throws Exception{
        var f=fixture();String id=phase.equals("FIRST_CREATE")?null:data(create(f,"first")).path("id").asString();jdbc.update("UPDATE file_asset SET version=? WHERE id=?",Long.MAX_VALUE,f.file().getId());
        if(phase.equals("NEW_REFERENCE")){expect(create(f,"next"),201,"OK");retained(f,null);}else if(id==null){var before=fileFact(f);expect(create(f,"overflow"),503,"FILE_STORAGE_UNAVAILABLE");noOrders(f,before);}else{expect(cancel(f,id),503,"FILE_STORAGE_UNAVAILABLE");assertThat(jdbc.queryForObject("SELECT status FROM service_order WHERE id=?",String.class,id)).isEqualTo("PENDING_PAYMENT");retained(f,null);}
    }
    @Test void twoShopsSystemTimeoutsSerializeOnFileWithoutStudentLock()throws Exception{
        var f=fixture();var a=data(create(f,"one")).path("id").asString();var second=merchant("PRINT");f.body().put("serviceId",Long.toString(second.service()));var b=data(create(f,"two")).path("id").asString();fixed(NOW.plusSeconds(900));
        var transactions=org.springframework.test.util.ReflectionTestUtils.getField(expiry,"transactions");var tx=(com.campuslife.order.service.PrintOrderTransactions)transactions;
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var start=new CountDownLatch(1);var one=pool.submit(()->{await(start);return tx.expire(Long.parseLong(a));});var two=pool.submit(()->{await(start);return tx.expire(Long.parseLong(b));});start.countDown();assertThat(one.get(10,TimeUnit.SECONDS)).isTrue();assertThat(two.get(10,TimeUnit.SECONDS)).isTrue();
        }
        retained(f,NOW.plusSeconds(900).plus(Duration.ofDays(7)));assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_event WHERE order_id IN (?,?)",Long.class,a,b)).isEqualTo(4);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void shopConfigurationLockIsReadAfterWait(boolean closeShop)throws Exception{
        var f=fixture();var held=new CountDownLatch(1);var release=new CountDownLatch(1);var tx=new TransactionTemplate(manager);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var writer=pool.submit(()->tx.execute(s->{jdbc.queryForList("SELECT id FROM shop WHERE id=? FOR UPDATE",f.merchant().shop());if(closeShop){jdbc.update("UPDATE shop SET business_enabled=0 WHERE id=?",f.merchant().shop());}else{jdbc.update("UPDATE print_price_rule SET version=version+1,price_per_impression=21 WHERE service_id=?",f.merchant().service());}held.countDown();await(release);return null;}));await(held);
            var waiting=new CountDownLatch(1);var real=sqlSession.getMapper(com.campuslife.order.mapper.OrderMapper.class);doAnswer(i->{waiting.countDown();return real.shareShop(i.getArgument(0));}).when(orderRows).shareShop(eq(f.merchant().shop()));
            var request=pool.submit(()->create(f,"shop-wait"));
            await(waiting);
            release.countDown();writer.get(10,TimeUnit.SECONDS);expect(request.get(15,TimeUnit.SECONDS),409,closeShop?"SHOP_UNAVAILABLE":"QUOTE_CHANGED");
        }finally{release.countDown();}
    }
    @Test void cleanupWinningBeforeCreationCannotBeResurrected()throws Exception{
        var f=fixture();expiryAt(f,NOW);cleanup.runOnce();assertThat(files.selectById(f.file().getId()).getStatus()).isEqualTo("DELETED");expect(create(f,"late"),410,"FILE_EXPIRED");assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM service_order WHERE student_id=?",Long.class,f.student().id())).isZero();
    }
    @Test void creationWinningAtExpiryKeepsObjectWhileCleanupSkipsBusyGuard()throws Exception{
        var f=fixture();expiryAt(f,NOW.plusMillis(1));var held=new CountDownLatch(1);var release=new CountDownLatch(1);var real=sqlSession.getMapper(com.campuslife.order.mapper.OrderEventMapper.class);
        doAnswer(i->{var n=real.append(i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(3),i.getArgument(4),i.getArgument(5),i.getArgument(6),i.getArgument(7),i.getArgument(8));held.countDown();await(release);return n;}).when(orderEvents).append(anyLong(),anyLong(),anyString(),nullable(String.class),anyString(),anyString(),nullable(Long.class),nullable(String.class),any());
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var creating=pool.submit(()->create(f,"protect"));await(held);fixed(NOW.plusMillis(2));pool.submit(()->cleanup.runOnce()).get(5,TimeUnit.SECONDS);assertThat(Files.exists(object(f.file()))).isTrue();release.countDown();expect(creating.get(10,TimeUnit.SECONDS),201,"OK");retained(f,null);
        }finally{release.countDown();}
        cleanup.runOnce();assertThat(files.selectById(f.file().getId()).getStatus()).isEqualTo("AVAILABLE");
    }
    @Test void lockFailureIs503AndRetryKeepsOriginalKey()throws Exception{
        var f=fixture();var before=fileFact(f);doThrow(new org.springframework.dao.CannotAcquireLockException("injected contention")).when(files).lock(eq(f.file().getId()));expect(create(f,"retry"),503,"SERVICE_TEMPORARILY_UNAVAILABLE");noOrders(f,before);reset(files);expect(create(f,"retry"),201,"OK");
    }
    @Test void aNewJarProcessRecoversOverduePrintWithOriginalDeadline()throws Exception{
        var f=fixture();var id=data(create(f,"restart")).path("id").asString();int port;try(var socket=new ServerSocket(0,0,InetAddress.getLoopbackAddress())){port=socket.getLocalPort();}
        var output=Path.of(".cache/print-order-recovery-"+UUID.randomUUID()+".log").toAbsolutePath();
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"-jar",Path.of("target/campuslife-0.0.1-SNAPSHOT.jar").toAbsolutePath().toString(),"--server.address=127.0.0.1","--server.port="+port,"--campuslife.files.root="+storage.root(),"--campuslife.files.cleanup-enabled=false","--campuslife.booking.expiry-enabled=false","--campuslife.print.expiry-enabled=true").redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try{
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);boolean closed=false;
            while(System.nanoTime()<deadline&&process.isAlive()){
                if("CLOSED".equals(jdbc.queryForObject("SELECT status FROM service_order WHERE id=?",String.class,id))){closed=true;break;}Thread.sleep(100);
            }
            assertThat(closed).as("new process startup scanner; diagnostics %s",output).isTrue();
            var actual=jdbc.queryForObject("SELECT closed_at FROM service_order WHERE id=?",LocalDateTime.class,id);assertThat(files.selectById(f.file().getId()).getExpiresAt()).isEqualTo(actual.plusDays(7));
            assertThat(jdbc.queryForObject("SELECT payment_deadline FROM service_order WHERE id=?",LocalDateTime.class,id)).isEqualTo(FileRules.utc(NOW.plusSeconds(900)));assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_event WHERE order_id=?",Long.class,id)).isEqualTo(2);
        }finally{process.destroy();if(!process.waitFor(10,TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor(10,TimeUnit.SECONDS);}}
    }
    @Test void expiryHasIndependentHundredOrderBatchAndContinuesNextScan()throws Exception{
        var f=fixture();var first=data(create(f,"batch")).path("id").asString();
        for(int n=0;n<100;n++){
            jdbc.update("INSERT INTO service_order(order_no,student_id,shop_id,order_type,status,payment_status,original_amount,discount_amount,payable_amount,currency,shop_name_snapshot,created_at,payment_deadline,version) SELECT ?,student_id,shop_id,order_type,status,payment_status,original_amount,discount_amount,payable_amount,currency,shop_name_snapshot,created_at,payment_deadline,version FROM service_order WHERE id=?",UUID.randomUUID().toString().replace("-",""),first);
            var id=jdbc.queryForObject("SELECT MAX(id) FROM service_order WHERE student_id=?",Long.class,f.student().id());
            jdbc.update("INSERT INTO print_detail(order_id,service_id,file_id,price_rule_id,service_name_snapshot,file_name_snapshot,page_count,copies,paper_size,color_mode,side_mode,sheet_count,unit_price_amount,source_price_version,progress) SELECT ?,service_id,file_id,price_rule_id,service_name_snapshot,file_name_snapshot,page_count,copies,paper_size,color_mode,side_mode,sheet_count,unit_price_amount,source_price_version,progress FROM print_detail WHERE order_id=?",id,first);
            jdbc.update("INSERT INTO order_event(order_id,event_seq,action,to_status,actor_type,actor_id,occurred_at) VALUES (?,0,'CREATE','PENDING_PAYMENT','STUDENT',?,?)",id,f.student().id(),FileRules.utc(NOW));
        }
        fixed(NOW.plusSeconds(900));assertThat(expiry.scan()).isEqualTo(100);retained(f,null);assertThat(expiry.scan()).isEqualTo(1);retained(f,NOW.plusSeconds(900).plus(Duration.ofDays(7)));assertThat(expiry.scan()).isZero();
    }
    @ParameterizedTest @CsvSource({"page_count,0","page_count,501","copies,0","copies,101","unit_price_amount,0","unit_price_amount,100001","source_price_version,-1","sheet_count,5"})
    void databaseCheckConstraintsProtectFrozenPrintFacts(String column,long value)throws Exception{
        var f=fixture();var id=data(create(f,"constraint")).path("id").asString();
        var failure=catchThrowable(()->jdbc.update("UPDATE print_detail SET "+column+"=? WHERE order_id=?",value,id));
        assertThat(failure).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(((org.springframework.dao.DataAccessException)failure).getMostSpecificCause()).isInstanceOfSatisfying(java.sql.SQLException.class,ex->{assertThat(ex.getErrorCode()).isEqualTo(3819);assertThat(ex.getSQLState()).isEqualTo("HY000");});
        assertThat(data(call("GET","/api/v1/orders/"+id,null,f.token())).path("printing").path("sheetCount").asLong()).isEqualTo(6);
    }
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
