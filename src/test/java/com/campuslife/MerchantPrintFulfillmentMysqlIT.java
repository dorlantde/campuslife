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
class MerchantPrintFulfillmentMysqlIT {
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
    @BeforeAll void prepare()throws Exception{hash=passwords.encode("Quote_Test123!");pdf=PdfSamples.pdf(5);}
    @MockitoSpyBean com.campuslife.order.mapper.MoneyMapper money; @MockitoSpyBean com.campuslife.order.service.MockPaymentPolicy mockPayment;
    @Autowired com.campuslife.order.service.PrintOrderTransactions printTransactions;
    @BeforeEach void freeze(){storage=disk;fixed(NOW);doReturn(true).when(mockPayment).enabled();}
    @AfterEach void cleanupOwned()throws Exception {
        reset(lists,files,prices,services,shops,sessions,clock,orderEvents,keys,printRows,disk,orderRows,money,mockPayment);
        for(long id:owners){jdbc.update("DELETE f FROM refund_record f JOIN service_order o ON o.id=f.order_id WHERE o.student_id=?",id);jdbc.update("DELETE p FROM payment_record p JOIN service_order o ON o.id=p.order_id WHERE o.student_id=?",id);}
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
    @Test void startReadyReplayHistoryAndDownloadsPreserveOneStageFactAndProtectedFile()throws Exception{
        var f=fixture();var id=accepted(f);var token=login(f.merchant().user());var original=detail(f,id);var file=fileFact(f);pdf(f,token);doReturn(false).when(mockPayment).enabled();
        var printing=data(start(token,id));assertThat(printing.path("status").asString()).isEqualTo("IN_SERVICE");assertThat(printing.path("printing").path("progress").asString()).isEqualTo("PRINTING");assertThat(printing.path("startedAt").asString()).isEqualTo(NOW.toString());assertThat(printing.path("version").asString()).isEqualTo("3");assertThat(printing.path("printing").path("readyAt").isNull()).isTrue();pdf(f,token);var snapshot=facts(f,id);assertThat(data(start(token,id))).isEqualTo(printing);assertThat(facts(f,id)).isEqualTo(snapshot);
        var waiting=data(ready(token,id));assertThat(waiting.path("status").asString()).isEqualTo("IN_SERVICE");assertThat(waiting.path("printing").path("progress").asString()).isEqualTo("READY_FOR_PICKUP");assertThat(waiting.path("printing").path("readyAt").asString()).isEqualTo(NOW.toString());assertThat(waiting.path("startedAt")).isEqualTo(printing.path("startedAt"));assertThat(waiting.path("version").asString()).isEqualTo("4");assertThat(waiting.path("completedAt").isNull()).isTrue();assertThat(waiting.path("printing").path("pickedUpAt").isNull()).isTrue();assertThat(waiting.path("payment")).isEqualTo(original.path("payment"));assertThat(waiting.path("refund").isNull()).isTrue();pdf(f,token);snapshot=facts(f,id);fixed(NOW.plusSeconds(30));assertThat(data(ready(token,id))).isEqualTo(waiting);assertThat(data(start(token,id))).isEqualTo(waiting);assertThat(facts(f,id)).isEqualTo(snapshot);assertThat(fileFact(f)).isEqualTo(file);assertThat(count("refund_record",id)).isZero();assertThat(count("order_event",id)).isEqualTo(5);
        var ev=data(call("GET","/api/v1/merchant/orders/"+id+"/events",null,token));assertThat(ev).isEqualTo(data(call("GET","/api/v1/orders/"+id+"/events",null,f.token())));assertThat(ev.get(3).path("action").asString()).isEqualTo("START");assertThat(ev.get(3).path("fromStatus").asString()).isEqualTo("ACCEPTED");assertThat(ev.get(4).path("action").asString()).isEqualTo("READY_FOR_PICKUP");assertThat(ev.get(4).path("fromStatus").asString()).isEqualTo("IN_SERVICE");assertThat(ev.get(4).path("toStatus").asString()).isEqualTo("IN_SERVICE");assertThat(ev.get(4).path("actorType").asString()).isEqualTo("MERCHANT");assertThat(ev.get(4).path("actorId").asString()).isEqualTo(Long.toString(f.merchant().user().id()));assertThat(ev.get(4).path("reason").isNull()).isTrue();
        for(String path:List.of("/api/v1/orders","/api/v1/merchant/orders")){var page=data(call("GET",path+"?orderType=PRINT&status=IN_SERVICE",null,path.contains("merchant")?token:f.token()));assertThat(page.path("total").asLong()).isEqualTo(1);assertThat(page.path("items").get(0).path("printingProgress").asString()).isEqualTo("READY_FOR_PICKUP");}
        expect(action(token,id,"cancel","{\"reason\":\"test\"}"),409,"ORDER_STATE_CONFLICT");expect(cancel(f,id),409,"ORDER_STATE_CONFLICT");expect(action(token,id,"complete",null),404,"RESOURCE_NOT_FOUND");
    }
    @ParameterizedTest @ValueSource(strings={"ANONYMOUS","INVALID","STUDENT","ADMIN","DISABLED","REVOKED","EXPIRED","OWNER_DISABLED","OTHER","NO_SHOP"})
    void bothActionsRequireCurrentIdentityMembershipAndOwnShop(String change)throws Exception{
        var f=fixture();var id=accepted(f);String token=login(f.merchant().user());data(start(token,id));int status=403;String code="ROLE_FORBIDDEN";
        switch(change){case "ANONYMOUS"->{token=null;status=401;code="AUTH_REQUIRED";}case "INVALID"->{token="invalid";status=401;code="TOKEN_INVALID";}case "STUDENT","ADMIN"->token=login(user(change));case "DISABLED"->{invalidate(f.merchant().user(),change);code="ACCOUNT_DISABLED";}case "REVOKED"->{invalidate(f.merchant().user(),change);status=401;code="SESSION_REVOKED";}case "EXPIRED"->{invalidate(f.merchant().user(),change);status=401;code="SESSION_EXPIRED";}case "OWNER_DISABLED"->jdbc.update("UPDATE shop_member SET status='DISABLED' WHERE shop_id=?",f.merchant().shop());case "OTHER"->{token=login(merchant("PRINT").user());status=404;code="RESOURCE_NOT_FOUND";}case "NO_SHOP"->{token=login(user("MERCHANT"));status=404;code="RESOURCE_NOT_FOUND";}}
        var before=facts(f,id);expect(start(token,id),status,code);expect(ready(token,id),status,code);assertThat(facts(f,id)).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"{}","null"," ","[]","{\"time\":1}"})
    void noBusinessBodyIsAllowedForFirstActionOrReplay(String body)throws Exception{
        var f=fixture();var id=accepted(f);var token=login(f.merchant().user());var before=facts(f,id);expect(action(token,id,"start",body),400,"VALIDATION_FAILED");assertThat(facts(f,id)).isEqualTo(before);data(start(token,id));before=facts(f,id);expect(action(token,id,"ready-for-pickup",body),400,"VALIDATION_FAILED");assertThat(facts(f,id)).isEqualTo(before);data(ready(token,id));before=facts(f,id);for(String suffix:List.of("start","ready-for-pickup")){expect(action(token,id,suffix,body),400,"VALIDATION_FAILED");}assertThat(facts(f,id)).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"0","01","-1","+1","x","9223372036854775808"})
    void idsAreCanonicalForBothActions(String id)throws Exception{var f=fixture();var actual=accepted(f);var token=login(f.merchant().user());var before=facts(f,actual);expect(start(token,id),400,"VALIDATION_FAILED");expect(ready(token,id),400,"VALIDATION_FAILED");assertThat(facts(f,actual)).isEqualTo(before);}
    @ParameterizedTest @ValueSource(strings={"SUSPENDED","OFF","SERVICE","PRICE","STUDENT_DISABLED","MOCK_OFF","OLD_DEADLINE"})
    void oldTaskFulfillmentDoesNotDependOnCurrentSalesOrStudentEligibility(String change)throws Exception{
        var f=fixture();var id=accepted(f);var token=login(f.merchant().user());switch(change){case "SUSPENDED"->jdbc.update("UPDATE shop SET approval_status='SUSPENDED' WHERE id=?",f.merchant().shop());case "OFF"->jdbc.update("UPDATE shop SET business_enabled=0 WHERE id=?",f.merchant().shop());case "SERVICE"->jdbc.update("UPDATE service_item SET enabled=0 WHERE id=?",f.merchant().service());case "PRICE"->jdbc.update("UPDATE print_price_rule SET enabled=0 WHERE service_id=?",f.merchant().service());case "STUDENT_DISABLED"->jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?",f.student().id());case "MOCK_OFF"->doReturn(false).when(mockPayment).enabled();case "OLD_DEADLINE"->fixed(NOW.plusSeconds(1801));}data(start(token,id));var result=data(ready(token,id));assertThat(result.path("printing").path("progress").asString()).isEqualTo("READY_FOR_PICKUP");pdf(f,token);retained(f,null);assertThat(count("refund_record",id)).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"UNPAID","AWAITING","USER_CLOSED","MERCHANT_CLOSED","COMPLETED","NO_ACCEPTED","WRONG_PROGRESS","STARTED_WRONG","READY_WRONG","COMPLETED_AT","PICKED_AT"})
    void malformedOrUnopenedStageCombinationsNeverWrite(String state)throws Exception{
        var f=fixture();var id=order(f);var token=login(f.merchant().user());if(!state.equals("UNPAID")){data(pay(f,id));}if(state.equals("USER_CLOSED")){data(cancel(f,id));}else if(!Set.of("UNPAID","AWAITING").contains(state)){
            data(action(token,id,"accept",null));switch(state){case "MERCHANT_CLOSED"->data(action(token,id,"cancel","{\"reason\":\"test\"}"));case "COMPLETED"->{jdbc.update("UPDATE service_order SET status='COMPLETED',started_at=?,completed_at=? WHERE id=?",FileRules.utc(NOW),FileRules.utc(NOW),id);jdbc.update("UPDATE print_detail SET progress='PICKED_UP',picked_up_at=? WHERE order_id=?",FileRules.utc(NOW),id);}case "NO_ACCEPTED"->jdbc.update("UPDATE service_order SET accepted_at=NULL WHERE id=?",id);case "WRONG_PROGRESS"->jdbc.update("UPDATE print_detail SET progress='PRINTING' WHERE order_id=?",id);case "STARTED_WRONG"->jdbc.update("UPDATE service_order SET started_at=? WHERE id=?",FileRules.utc(NOW),id);case "READY_WRONG"->jdbc.update("UPDATE print_detail SET ready_at=? WHERE order_id=?",FileRules.utc(NOW),id);case "COMPLETED_AT"->jdbc.update("UPDATE service_order SET completed_at=? WHERE id=?",FileRules.utc(NOW),id);case "PICKED_AT"->jdbc.update("UPDATE print_detail SET picked_up_at=? WHERE order_id=?",FileRules.utc(NOW),id);}
        }
        var before=facts(f,id);expect(start(token,id),409,"ORDER_STATE_CONFLICT");expect(ready(token,id),409,"ORDER_STATE_CONFLICT");assertThat(facts(f,id)).isEqualTo(before);
    }
    @Test void readyCannotSkipStartAndOnlyPrintCanUseReadyEndpoint()throws Exception{var f=fixture();var id=accepted(f);var token=login(f.merchant().user());var before=facts(f,id);expect(ready(token,id),409,"ORDER_STATE_CONFLICT");assertThat(facts(f,id)).isEqualTo(before);jdbc.update("UPDATE service_order SET order_type='BOOKING' WHERE id=?",id);before=facts(f,id);expect(ready(token,id),404,"RESOURCE_NOT_FOUND");assertThat(facts(f,id)).isEqualTo(before);}
    @ParameterizedTest @CsvSource({"START,-1","START,0","START,1","READY,-1","READY,0","READY,1"})
    void precedingActionTimeBoundaryAllowsEqualityAndRejectsClockRegression(String stage,long delta)throws Exception{
        fixed(NOW.minusSeconds(60));var f=fixture();var id=order(f);data(pay(f,id));var token=login(f.merchant().user());fixed(NOW);data(action(token,id,"accept",null));if(stage.equals("READY")){data(start(token,id));}var before=facts(f,id);fixed(NOW.plusMillis(delta));var result=stage.equals("READY")?ready(token,id):start(token,id);expect(result,delta<0?409:200,delta<0?"PRINT_TIME_INVALID":"OK");if(delta<0){assertThat(facts(f,id)).isEqualTo(before);}else{var value=data(result);assertThat((stage.equals("READY")?value.path("printing").path("readyAt"):value.path("startedAt")).asString()).isEqualTo(NOW.plusMillis(delta).toString());}
    }
    @ParameterizedTest @CsvSource({"START,EXPIRY","START,DELETING","START,OWNER","READY,EXPIRY","READY,DELETING","READY,OWNER"})
    void firstActionRequiresProtectedMetadataButNeverChangesIt(String stage,String fault)throws Exception{var f=fixture();var id=accepted(f);var token=login(f.merchant().user());if(stage.equals("READY")){data(start(token,id));}switch(fault){case "EXPIRY"->expiryAt(f,NOW.plusSeconds(3600));case "DELETING"->jdbc.update("UPDATE file_asset SET status='DELETING' WHERE id=?",f.file().getId());case "OWNER"->jdbc.update("UPDATE file_asset SET owner_id=? WHERE id=?",f.merchant().user().id(),f.file().getId());}var before=facts(f,id);expect(stage.equals("READY")?ready(token,id):start(token,id),503,"FILE_STORAGE_UNAVAILABLE");assertThat(facts(f,id)).isEqualTo(before);}
    @Test void legalReplaySkipsFirstFileAndClockChecksWithoutOverwritingStages()throws Exception{var f=fixture();var id=accepted(f);var token=login(f.merchant().user());data(start(token,id));var result=data(ready(token,id));expiryAt(f,NOW);var before=facts(f,id);assertThat(data(start(token,id))).isEqualTo(result);assertThat(data(ready(token,id))).isEqualTo(result);assertThat(facts(f,id)).isEqualTo(before);}
    @ParameterizedTest @ValueSource(strings={"START_ORDER","READY_ORDER","FILE"})
    void orderVersionIsRequiredButFileVersionIsNotWritten(String target)throws Exception{var f=fixture();var id=accepted(f);var token=login(f.merchant().user());if(target.equals("READY_ORDER")){data(start(token,id));}jdbc.update(target.equals("FILE")?"UPDATE file_asset SET version=? WHERE id=?":"UPDATE service_order SET version=? WHERE id=?",Long.MAX_VALUE,target.equals("FILE")?f.file().getId():id);var before=facts(f,id);if(target.equals("FILE")){data(start(token,id));data(ready(token,id));assertThat(fileFact(f)).isEqualTo(before.get("file"));}else{expect(target.equals("READY_ORDER")?ready(token,id):start(token,id),409,"ORDER_STATE_CONFLICT");assertThat(facts(f,id)).isEqualTo(before);}}
    @ParameterizedTest @ValueSource(strings={"START_ORDER","START_PROGRESS","START_EVENT","START_SESSION","READY_ORDER","READY_PROGRESS","READY_EVENT","READY_SESSION"})
    void everyWriteAndFinalSessionFailureRollsBackAllStageFacts(String point)throws Exception{
        var f=fixture();var id=accepted(f);var token=login(f.merchant().user());boolean waiting=point.startsWith("READY");if(waiting){data(start(token,id));}var before=facts(f,id);
        switch(point){case "START_ORDER"->doThrow(new IllegalStateException("injected")).when(orderRows).startService(anyLong(),anyLong(),any());case "START_PROGRESS"->doThrow(new IllegalStateException("injected")).when(printRows).startPrinting(anyLong());case "READY_ORDER"->doThrow(new IllegalStateException("injected")).when(orderRows).advancePrintReady(anyLong(),anyLong());case "READY_PROGRESS"->doThrow(new IllegalStateException("injected")).when(printRows).readyPrinting(anyLong(),any());case "START_EVENT","READY_EVENT"->doThrow(new IllegalStateException("injected")).when(orderEvents).append(anyLong(),eq(waiting?4L:3L),anyString(),anyString(),anyString(),eq("MERCHANT"),anyLong(),isNull(),any());case "START_SESSION"->{var real=sqlSession.getMapper(com.campuslife.order.mapper.OrderMapper.class);doAnswer(i->{int n=real.startService(i.getArgument(0),i.getArgument(1),i.getArgument(2));fixed(NOW.plusSeconds(7200));return n;}).when(orderRows).startService(anyLong(),anyLong(),any());}case "READY_SESSION"->{var real=sqlSession.getMapper(com.campuslife.order.mapper.OrderMapper.class);doAnswer(i->{int n=real.advancePrintReady(i.getArgument(0),i.getArgument(1));fixed(NOW.plusSeconds(7200));return n;}).when(orderRows).advancePrintReady(anyLong(),anyLong());}}
        assertThat((waiting?ready(token,id):start(token,id)).statusCode()).isEqualTo(point.endsWith("SESSION")?401:500);assertThat(facts(f,id)).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"START_START","START_CANCEL","START_READY","READY_READY","READY_START"})
    void realMysqlRacesDoNotDuplicateEventsRefundOrRegressProgress(String race)throws Exception{
        var f=fixture();var id=accepted(f);var token=login(f.merchant().user());if(race.startsWith("READY")){data(start(token,id));}
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var gate=new CountDownLatch(1);var a=pool.submit(()->{await(gate);return race.startsWith("READY")?ready(token,id):start(token,id);});var b=pool.submit(()->{await(gate);return switch(race){case "START_CANCEL"->action(token,id,"cancel","{\"reason\":\"test\"}");case "START_READY","READY_READY"->ready(token,id);default->start(token,id);};});gate.countDown();var first=a.get(20,TimeUnit.SECONDS);var second=b.get(20,TimeUnit.SECONDS);assertThat(first.statusCode()).as(first.body()).isIn(200,409);assertThat(second.statusCode()).as(second.body()).isIn(200,409);if(race.equals("START_CANCEL")){assertThat(List.of(first.statusCode(),second.statusCode())).containsExactlyInAnyOrder(200,409);}else{assertThat(first.statusCode()).isEqualTo(200);if(!race.equals("START_READY")){assertThat(second.statusCode()).isEqualTo(200);}}
        }
        var o=detail(f,id);boolean closed="CLOSED".equals(o.path("status").asString());boolean waiting="READY_FOR_PICKUP".equals(o.path("printing").path("progress").asString());assertThat(count("order_event",id)).isEqualTo(waiting?5:4);assertThat(count("refund_record",id)).isEqualTo(closed?1:0);if(!closed){retained(f,null);pdf(f,token);}
    }
    @ParameterizedTest @CsvSource({"START,SHOP","START,FILE","START,ORDER","READY,SHOP","READY,FILE","READY,ORDER"})
    void actionTimeIsReadAfterRealBusinessLockWait(String stage,String target)throws Exception{
        var f=fixture();var id=accepted(f);var token=login(f.merchant().user());if(stage.equals("READY")){data(start(token,id));}var held=new CountDownLatch(1);var release=new CountDownLatch(1);var entering=new CountDownLatch(1);var tx=new TransactionTemplate(manager);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var holder=pool.submit(()->tx.execute(s->{jdbc.queryForList(switch(target){case "SHOP"->"SELECT id FROM shop WHERE id=? FOR UPDATE";case "FILE"->"SELECT id FROM file_asset WHERE id=? FOR UPDATE";default->"SELECT id FROM service_order WHERE id=? FOR UPDATE";},switch(target){case "SHOP"->f.merchant().shop();case "FILE"->f.file().getId();default->Long.parseLong(id);});held.countDown();await(release);return null;}));await(held);
            if(target.equals("FILE")){var real=sqlSession.getMapper(FileAssetMapper.class);doAnswer(i->{entering.countDown();return real.lock(i.getArgument(0));}).when(files).lock(eq(f.file().getId()));}else{var real=sqlSession.getMapper(com.campuslife.order.mapper.OrderMapper.class);if(target.equals("SHOP")){doAnswer(i->{entering.countDown();return real.shareShop(i.getArgument(0));}).when(orderRows).shareShop(eq(f.merchant().shop()));}else{doAnswer(i->{entering.countDown();return real.lock(i.getArgument(0));}).when(orderRows).lock(eq(Long.parseLong(id)));}}
            var request=pool.submit(()->stage.equals("READY")?ready(token,id):start(token,id));await(entering);fixed(NOW.plusSeconds(10));release.countDown();holder.get(10,TimeUnit.SECONDS);var value=data(request.get(15,TimeUnit.SECONDS));assertThat((stage.equals("READY")?value.path("printing").path("readyAt"):value.path("startedAt")).asString()).isEqualTo(NOW.plusSeconds(10).toString());
        }finally{release.countDown();}
    }
    @Test void noInstanceGuardDiskIoOrTargetStudentLockIsNeededForEitherAction()throws Exception{
        var f=fixture();var id=accepted(f);var token=login(f.merchant().user());var guard=storage.lock(f.file().getStorageKey());guard.lock();var held=new CountDownLatch(1);var release=new CountDownLatch(1);var tx=new TransactionTemplate(manager);clearInvocations(disk);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var holder=pool.submit(()->tx.execute(s->{jdbc.queryForList("SELECT id FROM account WHERE id=? FOR UPDATE",f.student().id());held.countDown();await(release);return null;}));await(held);data(pool.submit(()->start(token,id)).get(5,TimeUnit.SECONDS));data(pool.submit(()->ready(token,id)).get(5,TimeUnit.SECONDS));verify(disk,never()).lock(anyString());verify(disk,never()).open(anyString(),anyString(),anyLong());release.countDown();holder.get(10,TimeUnit.SECONDS);
        }finally{release.countDown();guard.unlock();}
    }
    @Test void downloadWaitingForGuardRemainsAuthorizedAfterBothStageTransitions()throws Exception{
        var f=fixture();var id=accepted(f);var token=login(f.merchant().user());var guard=storage.lock(f.file().getStorageKey());var entering=new CountDownLatch(1);doAnswer(i->{entering.countDown();return guard;}).when(disk).lock(eq(f.file().getStorageKey()));guard.lock();
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){var request=pool.submit(()->download(f,token));await(entering);data(pool.submit(()->start(token,id)).get(5,TimeUnit.SECONDS));data(pool.submit(()->ready(token,id)).get(5,TimeUnit.SECONDS));guard.unlock();var response=request.get(15,TimeUnit.SECONDS);assertThat(response.statusCode()).isEqualTo(200);assertThat(response.body()).isEqualTo(pdf);}finally{if(guard.isHeldByCurrentThread()){guard.unlock();}}
    }
    @Test void readyIsNotCompletionAndDoesNotStartRetentionOrTimeoutRefund()throws Exception{var f=fixture();var id=accepted(f);var token=login(f.merchant().user());data(start(token,id));var result=data(ready(token,id));var before=facts(f,id);fixed(NOW.plus(Duration.ofDays(8)));token=login(f.merchant().user());assertThat(data(start(token,id))).isEqualTo(result);assertThat(data(ready(token,id))).isEqualTo(result);assertThat(expiry.scan()).isZero();cleanup.runOnce();assertThat(facts(f,id)).isEqualTo(before);retained(f,null);assertThat(object(f.file())).exists();pdf(f,token);}
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
