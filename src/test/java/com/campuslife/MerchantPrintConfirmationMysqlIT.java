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
class MerchantPrintConfirmationMysqlIT {
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

    private HttpResponse<String> merchantCall(String token,String id,String action,String body)throws Exception{return call(action.isEmpty()||action.equals("/events")?"GET":"POST","/api/v1/merchant/orders/"+id+action,body,token);}
    private HttpResponse<String> accept(String token,String id)throws Exception{return merchantCall(token,id,"/accept",null);}
    private HttpResponse<String> reject(String token,String id,String reason)throws Exception{return merchantCall(token,id,"/reject",json.writeValueAsString(Map.of("reason",reason)));}
    private JsonNode list(String token,String query)throws Exception{return data(call("GET","/api/v1/merchant/orders"+query,null,token));}
    private String paid(Fixture f)throws Exception{var id=order(f);data(pay(f,id));return id;}
    private void accepted(Fixture f,String id)throws Exception{
        var o=detail(f,id);assertThat(o.path("status").asString()).isEqualTo("ACCEPTED");assertThat(o.path("paymentStatus").asString()).isEqualTo("PAID");assertThat(o.path("acceptedAt").asString()).isEqualTo(NOW.toString());assertThat(o.path("printing").path("progress").asString()).isEqualTo("QUEUED");assertThat(count("refund_record",id)).isZero();assertThat(count("order_event",id)).isEqualTo(3);retained(f,null);
    }
    @Test void merchantReadAcceptAndReplayPreserveSnapshotsFundsAndFile()throws Exception{
        var f=fixture();var id=paid(f);var token=login(f.merchant().user());var before=fileFact(f);clearInvocations(disk);
        assertThat(data(merchantCall(token,id,"",null))).isEqualTo(detail(f,id));
        var page=list(token,"");assertThat(page.path("total").asLong()).isEqualTo(1);var item=page.path("items").get(0);assertThat(item.path("orderType").asString()).isEqualTo("PRINT");assertThat(item.path("printingProgress").asString()).isEqualTo("QUEUED");assertThat(item.path("bookingStartAt").isNull()).isTrue();assertThat(item.has("studentId")).isFalse();
        doReturn(false).when(mockPayment).enabled();var result=data(accept(token,id));accepted(f,id);assertThat(fileFact(f)).isEqualTo(before);var snapshot=facts(f,id);fixed(NOW.plusSeconds(1801));assertThat(data(accept(token,id))).isEqualTo(result);assertThat(facts(f,id)).isEqualTo(snapshot);assertThat(expiry.scan()).isZero();expect(cancel(f,id),409,"ORDER_STATE_CONFLICT");
        var events=data(merchantCall(token,id,"/events",null));assertThat(events).isEqualTo(data(call("GET","/api/v1/orders/"+id+"/events",null,f.token())));assertThat(events.size()).isEqualTo(3);assertThat(events.get(2).path("action").asString()).isEqualTo("ACCEPT");assertThat(events.get(2).path("actorType").asString()).isEqualTo("MERCHANT");assertThat(events.get(2).path("actorId").asString()).isEqualTo(Long.toString(f.merchant().user().id()));verify(disk,never()).open(anyString(),anyString(),anyLong());verify(disk,never()).lock(anyString());
        expect(merchantCall(token,id,"/cancel","{\"reason\":\"test\"}"),503,"MOCK_PAYMENT_DISABLED");assertThat(facts(f,id)).isEqualTo(snapshot);
        expect(merchantCall(token,id,"/start","{}"),400,"VALIDATION_FAILED");expect(merchantCall(token,id,"/complete",null),404,"RESOURCE_NOT_FOUND");
    }
    @Test void rejectionPersistsMerchantReasonOnceAndRetainsLastReferenceForSevenDays()throws Exception{
        var f=fixture();var id=paid(f);var token=login(f.merchant().user());var result=data(reject(token,id,"  设备维护  "));assertRefunded(f,id,"MERCHANT_REJECT",NOW);assertThat(result.path("closeNote").asString()).isEqualTo("设备维护");
        assertThat(jdbc.queryForObject("SELECT reason_note FROM refund_record WHERE order_id=?",String.class,id)).isEqualTo("设备维护");var ev=data(merchantCall(token,id,"/events",null)).get(2);assertThat(ev.path("actorType").asString()).isEqualTo("MERCHANT");assertThat(ev.path("actorId").asString()).isEqualTo(Long.toString(f.merchant().user().id()));assertThat(ev.path("reason").asString()).isEqualTo("设备维护");
        var snapshot=facts(f,id);doReturn(false).when(mockPayment).enabled();fixed(NOW.plusSeconds(1801));assertThat(data(reject(token,id,"设备维护"))).isEqualTo(result);expect(reject(token,id,"other"),409,"ORDER_STATE_CONFLICT");expect(accept(token,id),409,"ORDER_STATE_CONFLICT");assertThat(facts(f,id)).isEqualTo(snapshot);
    }
    @ParameterizedTest @CsvSource({"ACCEPT,1799999","ACCEPT,1800000","ACCEPT,1800001","REJECT,1799999","REJECT,1800000","REJECT,1800001"})
    void deadlineEqualityCommitsSystemRefundBeforeReturningConflict(String action,long elapsed)throws Exception{
        var f=fixture();var id=paid(f);var token=login(f.merchant().user());fixed(NOW.plusMillis(elapsed));var response=action.equals("ACCEPT")?accept(token,id):reject(token,id,"merchant reason");
        expect(response,elapsed<1800000?200:409,elapsed<1800000?"OK":"ACCEPTANCE_DEADLINE_PASSED");
        if(elapsed>=1800000){assertRefunded(f,id,"ACCEPTANCE_TIMEOUT",NOW.plusMillis(elapsed));var ev=data(merchantCall(token,id,"/events",null)).get(2);assertThat(ev.path("actorType").asString()).isEqualTo("SYSTEM");assertThat(ev.path("actorId").isNull()).isTrue();assertThat(detail(f,id).path("closeNote").isNull()).isTrue();var snapshot=facts(f,id);expect(accept(token,id),409,"ACCEPTANCE_DEADLINE_PASSED");expect(reject(token,id,"again"),409,"ACCEPTANCE_DEADLINE_PASSED");assertThat(facts(f,id)).isEqualTo(snapshot);}
    }
    @ParameterizedTest @ValueSource(strings={"OFF","HOURS","SERVICE","PRICE","STUDENT","DISK"})
    void validOldOrdersIgnoreCurrentSalesConfigurationStudentStatusAndDisk(String change)throws Exception{
        var f=fixture();var id=paid(f);var second=data(create(f,"second")).path("id").asString();data(pay(f,second));var token=login(f.merchant().user());
        switch(change){case "OFF"->jdbc.update("UPDATE shop SET business_enabled=0 WHERE id=?",f.merchant().shop());case "HOURS"->jdbc.update("DELETE FROM shop_business_hour WHERE shop_id=?",f.merchant().shop());case "SERVICE"->jdbc.update("UPDATE service_item SET enabled=0 WHERE id=?",f.merchant().service());case "PRICE"->jdbc.update("UPDATE print_price_rule SET enabled=0,price_per_impression=999 WHERE service_id=?",f.merchant().service());case "STUDENT"->jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?",f.student().id());case "DISK"->storage.delete(storage.storageId(),f.file().getStorageKey());}
        assertThat(list(token,"").path("total").asLong()).isEqualTo(2);data(merchantCall(token,id,"",null));data(merchantCall(token,id,"/events",null));assertThat(data(accept(token,id)).path("status").asString()).isEqualTo("ACCEPTED");assertThat(data(reject(token,second,"test")).path("closeReason").asString()).isEqualTo("MERCHANT_REJECT");retained(f,null);
    }
    @Test void suspendedShopCanReadAndRejectButCannotAcceptEvenReplayOrExpired()throws Exception{
        var f=fixture();var id=paid(f);var token=login(f.merchant().user());data(accept(token,id));var second=data(create(f,"second")).path("id").asString();data(pay(f,second));jdbc.update("UPDATE shop SET approval_status='SUSPENDED' WHERE id=?",f.merchant().shop());
        var before=facts(f,id);expect(accept(token,id),403,"SHOP_NOT_APPROVED");assertThat(facts(f,id)).isEqualTo(before);assertThat(list(token,"").path("total").asLong()).isEqualTo(2);data(merchantCall(token,id,"",null));data(merchantCall(token,id,"/events",null));data(reject(token,second,"maintenance"));
        fixed(NOW.plusSeconds(1800));before=facts(f,id);expect(accept(token,id),403,"SHOP_NOT_APPROVED");assertThat(facts(f,id)).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"ANONYMOUS","INVALID","STUDENT","ADMIN","DISABLED","REVOKED","EXPIRED","OWNER_DISABLED","OTHER","NO_SHOP"})
    void allFiveOperationsEnforceCurrentMerchantAndOwnShop(String change)throws Exception{
        var f=fixture();var id=paid(f);String token=login(f.merchant().user());int status=403;String code="ROLE_FORBIDDEN";
        switch(change){case "ANONYMOUS"->{token=null;status=401;code="AUTH_REQUIRED";}case "INVALID"->{token="invalid";status=401;code="TOKEN_INVALID";}case "STUDENT","ADMIN"->token=login(user(change));case "DISABLED"->{invalidate(f.merchant().user(),change);code="ACCOUNT_DISABLED";}case "REVOKED"->{invalidate(f.merchant().user(),change);status=401;code="SESSION_REVOKED";}case "EXPIRED"->{invalidate(f.merchant().user(),change);status=401;code="SESSION_EXPIRED";}case "OWNER_DISABLED"->jdbc.update("UPDATE shop_member SET status='DISABLED' WHERE shop_id=?",f.merchant().shop());case "OTHER"->{token=login(merchant("PRINT").user());status=404;code="RESOURCE_NOT_FOUND";}case "NO_SHOP"->{token=login(user("MERCHANT"));status=404;code="RESOURCE_NOT_FOUND";}}
        var before=facts(f,id);for(String action:List.of("","/events","/accept","/reject")){expect(merchantCall(token,id,action,action.equals("/reject")?"{\"reason\":\"test\"}":null),status,code);}
        var response=call("GET","/api/v1/merchant/orders?orderType=PRINT",null,token);if(change.equals("OTHER")){assertThat(data(response).path("total").asLong()).isZero();}else{expect(response,status,code);}assertThat(facts(f,id)).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"{}","null","[]","{\"reason\":null}","{\"reason\":1}","{\"reason\":\" \"}","{\"reason\":\"test\",\"expectedVersion\":\"1\"}","TOO_LONG"})
    void rejectionStrictlyValidatesReasonWithoutWrites(String body)throws Exception{
        var f=fixture();var id=paid(f);var token=login(f.merchant().user());var before=facts(f,id);if(body.equals("TOO_LONG")){body=json.writeValueAsString(Map.of("reason","😀".repeat(301)));}expect(merchantCall(token,id,"/reject",body),400,"VALIDATION_FAILED");assertThat(facts(f,id)).isEqualTo(before);
    }
    @Test void rejectionAllowsThreeHundredUnicodeCodePoints()throws Exception{var f=fixture();var id=paid(f);var reason="😀".repeat(300);assertThat(data(reject(login(f.merchant().user()),id,reason)).path("closeNote").asString()).isEqualTo(reason);}
    @ParameterizedTest @ValueSource(strings={"{}","null"," ","[]"})
    void acceptingBodyIsInvalidBeforeAnyStateMutation(String body)throws Exception{var f=fixture();var id=paid(f);var token=login(f.merchant().user());var before=facts(f,id);expect(merchantCall(token,id,"/accept",body),400,"VALIDATION_FAILED");assertThat(facts(f,id)).isEqualTo(before);data(accept(token,id));before=facts(f,id);expect(merchantCall(token,id,"/accept",body),400,"VALIDATION_FAILED");assertThat(facts(f,id)).isEqualTo(before);}
    @ParameterizedTest @CsvSource({"ACCEPT,EXPIRY","ACCEPT,DELETING","ACCEPT,OTHER","REJECT,EXPIRY","REJECT,DELETING","REJECT,OTHER"})
    void invalidFileProtectionCannotCommitAcceptanceOrRefund(String action,String change)throws Exception{
        var f=fixture();var id=paid(f);var token=login(f.merchant().user());switch(change){case "EXPIRY"->expiryAt(f,NOW.plusSeconds(1));case "DELETING"->jdbc.update("UPDATE file_asset SET status='DELETING' WHERE id=?",f.file().getId());case "OTHER"->jdbc.update("UPDATE file_asset SET owner_id=? WHERE id=?",f.merchant().user().id(),f.file().getId());}var before=facts(f,id);expect(action.equals("ACCEPT")?accept(token,id):reject(token,id,"test"),503,"FILE_STORAGE_UNAVAILABLE");assertThat(facts(f,id)).isEqualTo(before);
    }
    @Test void repeatedAcceptanceDoesNotRecheckFirstAcceptanceFileMetadata()throws Exception{var f=fixture();var id=paid(f);var token=login(f.merchant().user());var result=data(accept(token,id));expiryAt(f,NOW.plusSeconds(1));var before=facts(f,id);assertThat(data(accept(token,id))).isEqualTo(result);assertThat(facts(f,id)).isEqualTo(before);}
    @ParameterizedTest @ValueSource(strings={"REJECT","EXPIRED_ACCEPT","EXPIRED_REJECT"})
    void gateOffRejectsEveryNewRefundAndPreservesPendingFacts(String action)throws Exception{var f=fixture();var id=paid(f);var token=login(f.merchant().user());doReturn(false).when(mockPayment).enabled();if(action.startsWith("EXPIRED")){fixed(NOW.plusSeconds(1800));}var before=facts(f,id);expect(action.equals("EXPIRED_ACCEPT")?accept(token,id):reject(token,id,"test"),503,"MOCK_PAYMENT_DISABLED");assertThat(facts(f,id)).isEqualTo(before);}
    @ParameterizedTest @ValueSource(strings={"UNPAID","STUDENT_CLOSED","ACCEPTED"})
    void unsupportedTransitionsLeaveAllFactsUnchanged(String state)throws Exception{var f=fixture();var id=order(f);var token=login(f.merchant().user());if(!state.equals("UNPAID")){data(pay(f,id));if(state.equals("ACCEPTED")){data(accept(token,id));}else{data(cancel(f,id));}}var before=facts(f,id);expect(reject(token,id,"test"),409,"ORDER_STATE_CONFLICT");if(!state.equals("ACCEPTED")){expect(accept(token,id),409,"ORDER_STATE_CONFLICT");}assertThat(facts(f,id)).isEqualTo(before);}
    @ParameterizedTest @ValueSource(strings={"ACCEPT_STATUS","ACCEPT_EVENT","ACCEPT_SESSION","REFUND","REJECT_STATUS","PROGRESS","REJECT_EVENT","RETENTION","REJECT_SESSION"})
    void failureAtEachWriteRollsBackOrderFundsEventsAndFile(String point)throws Exception{
        var f=fixture();var id=paid(f);var token=login(f.merchant().user());var before=facts(f,id);
        switch(point){
            case "ACCEPT_STATUS"->doThrow(new IllegalStateException("injected")).when(orderRows).accept(anyLong(),anyLong(),any());
            case "ACCEPT_EVENT","REJECT_EVENT"->doThrow(new IllegalStateException("injected")).when(orderEvents).append(anyLong(),eq(2L),anyString(),anyString(),anyString(),anyString(),nullable(Long.class),nullable(String.class),any());
            case "ACCEPT_SESSION"->{var real=sqlSession.getMapper(com.campuslife.order.mapper.OrderMapper.class);doAnswer(i->{int n=real.accept(i.getArgument(0),i.getArgument(1),i.getArgument(2));fixed(NOW.plusSeconds(7200));return n;}).when(orderRows).accept(anyLong(),anyLong(),any());}
            case "REFUND"->doThrow(new IllegalStateException("injected")).when(money).refundWithNote(anyLong(),anyLong(),anyString(),anyLong(),anyString(),anyString(),any());
            case "REJECT_STATUS"->doThrow(new IllegalStateException("injected")).when(orderRows).refundCloseFrom(anyLong(),anyLong(),anyString(),anyString(),anyString(),any());
            case "PROGRESS"->doThrow(new IllegalStateException("injected")).when(printRows).abort(anyLong());
            case "RETENTION"->doThrow(new IllegalStateException("injected")).when(files).expiry(anyLong(),anyLong(),nullable(LocalDateTime.class),any());
            case "REJECT_SESSION"->{var real=sqlSession.getMapper(com.campuslife.order.mapper.MoneyMapper.class);doAnswer(i->{int n=real.refundWithNote(i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(3),i.getArgument(4),i.getArgument(5),i.getArgument(6));fixed(NOW.plusSeconds(7200));return n;}).when(money).refundWithNote(anyLong(),anyLong(),anyString(),anyLong(),anyString(),anyString(),any());}
        }
        assertThat((point.startsWith("ACCEPT")?accept(token,id):reject(token,id,"test")).statusCode()).isEqualTo(point.endsWith("SESSION")?401:500);assertThat(facts(f,id)).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"ACCEPT","REJECT","FILE"})
    void exhaustedVersionsCannotPartiallyCommit(String action)throws Exception{var f=fixture();var id=paid(f);var token=login(f.merchant().user());jdbc.update(action.equals("FILE")?"UPDATE file_asset SET version=? WHERE id=?":"UPDATE service_order SET version=? WHERE id=?",Long.MAX_VALUE,action.equals("FILE")?f.file().getId():id);var before=facts(f,id);expect(action.equals("ACCEPT")?accept(token,id):reject(token,id,"test"),action.equals("FILE")?503:409,action.equals("FILE")?"FILE_STORAGE_UNAVAILABLE":"ORDER_STATE_CONFLICT");assertThat(facts(f,id)).isEqualTo(before);}
    @Test void rejectionKeepsOtherShopsReferenceAndAcceptanceKeepsFileAfterOtherOrderCloses()throws Exception{
        var f=fixture();var id=paid(f);var other=merchant("PRINT");f.body().put("serviceId",Long.toString(other.service()));var second=data(create(f,"other-shop")).path("id").asString();data(pay(f,second));var before=fileFact(f);data(reject(login(f.merchant().user()),id,"test"));assertThat(fileFact(f)).isEqualTo(before);data(accept(login(other.user()),second));retained(f,null);fixed(NOW.plusSeconds(1800));assertThat(expiry.scan()).isZero();retained(f,null);
    }
    @ParameterizedTest @ValueSource(strings={"ACCEPT_ACCEPT","ACCEPT_REJECT","REJECT_REJECT","ACCEPT_CANCEL","REJECT_CREATE","REJECT_SCAN"})
    void racingRealMysqlRequestsConvergeWithOneTerminalDecision(String race)throws Exception{
        var f=fixture();var id=paid(f);var token=login(f.merchant().user());if(race.equals("REJECT_SCAN")){fixed(NOW.plusSeconds(1800));}
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var gate=new CountDownLatch(1);var one=pool.submit(()->{await(gate);return race.startsWith("ACCEPT")?accept(token,id):reject(token,id,"test");});var two=pool.submit(()->{await(gate);return switch(race){case "ACCEPT_ACCEPT"->accept(token,id);case "ACCEPT_REJECT","REJECT_REJECT"->reject(token,id,"test");case "ACCEPT_CANCEL"->cancel(f,id);case "REJECT_CREATE"->create(f,"new-reference");default->{expiry.scan();yield null;}};});gate.countDown();var a=one.get(20,TimeUnit.SECONDS);var b=two.get(20,TimeUnit.SECONDS);assertThat(a.statusCode()).as(a.body()).isIn(200,409);if(b!=null){assertThat(b.statusCode()).as(b.body()).isIn(200,201,409);}
        }
        var o=detail(f,id);assertThat(count("payment_record",id)).isEqualTo(1);assertThat(count("order_event",id)).isEqualTo(3);if(o.path("status").asString().equals("ACCEPTED")){assertThat(count("refund_record",id)).isZero();retained(f,null);}else{assertThat(count("refund_record",id)).isEqualTo(1);assertThat(o.path("printing").path("progress").asString()).isEqualTo("ABORTED");if(race.equals("REJECT_CREATE")){retained(f,null);}else{retained(f,(race.equals("REJECT_SCAN")?NOW.plusSeconds(1800):NOW).plus(Duration.ofDays(7)));}}
    }
    @ParameterizedTest @ValueSource(strings={"ACCEPT","REJECT","CREATE","OWNER"})
    void countPageAndEligibilityUseOneSnapshotDuringConcurrentPrintChanges(String action)throws Exception{
        var f=fixture();var id=paid(f);var token=login(f.merchant().user());var gate=new CountDownLatch(1);var release=new CountDownLatch(1);var real=sqlSession.getMapper(com.campuslife.order.mapper.OrderListMapper.class);
        doAnswer(i->{long n=real.countShopType(i.getArgument(0),i.getArgument(1),i.getArgument(2));gate.countDown();await(release);return n;}).when(lists).countShopType(eq(f.merchant().shop()),eq("PRINT"),eq("PENDING_ACCEPTANCE"));
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var request=pool.submit(()->list(token,"?orderType=PRINT&status=PENDING_ACCEPTANCE"));await(gate);
            switch(action){case "ACCEPT"->data(accept(token,id));case "REJECT"->data(reject(token,id,"test"));case "CREATE"->{var next=data(create(f,"next")).path("id").asString();data(pay(f,next));}case "OWNER"->jdbc.update("UPDATE shop_member SET status='DISABLED' WHERE shop_id=?",f.merchant().shop());}
            release.countDown();var page=request.get(10,TimeUnit.SECONDS);assertThat(page.path("total").asLong()).isEqualTo(1);assertThat(page.path("items").size()).isEqualTo(1);assertThat(page.path("items").get(0).path("status").asString()).isEqualTo("PENDING_ACCEPTANCE");
            if(action.equals("OWNER")){expect(call("GET","/api/v1/merchant/orders",null,token),403,"ROLE_FORBIDDEN");}
        }finally{release.countDown();}
    }
    @ParameterizedTest @ValueSource(strings={"ACCEPT","REJECT"})
    void lockedResultDiscardsCachedLocatorWhenScannerWins(String action)throws Exception{
        var f=fixture();var id=paid(f);var token=login(f.merchant().user());fixed(NOW.plusSeconds(1800));var gate=new CountDownLatch(1);var release=new CountDownLatch(1);var once=new java.util.concurrent.atomic.AtomicBoolean(true);var real=sqlSession.getMapper(com.campuslife.order.mapper.OrderMapper.class);
        doAnswer(i->{var result=real.selectById((java.io.Serializable)i.getArgument(0));if(Objects.equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),java.sql.Connection.TRANSACTION_READ_COMMITTED)&&once.compareAndSet(true,false)){gate.countDown();await(release);}return result;}).when(orderRows).selectById((java.io.Serializable)eq(Long.parseLong(id)));
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){var request=pool.submit(()->action.equals("ACCEPT")?accept(token,id):reject(token,id,"test"));await(gate);assertThat(printTransactions.expire(Long.parseLong(id))).isTrue();release.countDown();expect(request.get(10,TimeUnit.SECONDS),409,"ACCEPTANCE_DEADLINE_PASSED");assertRefunded(f,id,"ACCEPTANCE_TIMEOUT",NOW.plusSeconds(1800));}finally{release.countDown();}
    }
    @ParameterizedTest @ValueSource(strings={"SHOP","FILE","ORDER"})
    void decisionClockIsReadAfterWaitingForBusinessLocks(String target)throws Exception{
        var f=fixture();var id=paid(f);var token=login(f.merchant().user());var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var entering=new CountDownLatch(1);var tx=new TransactionTemplate(manager);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var holding=pool.submit(()->tx.execute(s->{jdbc.queryForList(switch(target){case "SHOP"->"SELECT id FROM shop WHERE id=? FOR UPDATE";case "FILE"->"SELECT id FROM file_asset WHERE id=? FOR UPDATE";default->"SELECT id FROM service_order WHERE id=? FOR UPDATE";},switch(target){case "SHOP"->f.merchant().shop();case "FILE"->f.file().getId();default->Long.parseLong(id);});locked.countDown();await(release);return null;}));await(locked);
            if(target.equals("FILE")){var real=sqlSession.getMapper(FileAssetMapper.class);doAnswer(i->{entering.countDown();return real.lock(i.getArgument(0));}).when(files).lock(eq(f.file().getId()));}else{var real=sqlSession.getMapper(com.campuslife.order.mapper.OrderMapper.class);if(target.equals("SHOP")){doAnswer(i->{entering.countDown();return real.shareShop(i.getArgument(0));}).when(orderRows).shareShop(eq(f.merchant().shop()));}else{doAnswer(i->{entering.countDown();return real.lock(i.getArgument(0));}).when(orderRows).lock(eq(Long.parseLong(id)));}}
            var request=pool.submit(()->accept(token,id));await(entering);fixed(NOW.plusSeconds(1800));release.countDown();holding.get(10,TimeUnit.SECONDS);expect(request.get(15,TimeUnit.SECONDS),409,"ACCEPTANCE_DEADLINE_PASSED");assertRefunded(f,id,"ACCEPTANCE_TIMEOUT",NOW.plusSeconds(1800));
        }finally{release.countDown();}
    }
    @Test void noInstanceGuardOrTargetStudentAccountLockIsNeeded()throws Exception{
        var f=fixture();var id=paid(f);var token=login(f.merchant().user());var guard=storage.lock(f.file().getStorageKey());guard.lock();var held=new CountDownLatch(1);var release=new CountDownLatch(1);var tx=new TransactionTemplate(manager);clearInvocations(disk);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var holding=pool.submit(()->tx.execute(s->{jdbc.queryForList("SELECT id FROM account WHERE id=? FOR UPDATE",f.student().id());held.countDown();await(release);return null;}));await(held);expect(pool.submit(()->reject(token,id,"test")).get(5,TimeUnit.SECONDS),200,"OK");verify(disk,never()).lock(anyString());verify(disk,never()).open(anyString(),anyString(),anyLong());release.countDown();holding.get(10,TimeUnit.SECONDS);
        }finally{release.countDown();guard.unlock();}
    }
    @ParameterizedTest @ValueSource(strings={"DETAIL_ACCEPT","EVENTS_REJECT","DETAIL_OWNER","EVENTS_OWNER","DETAIL_DELETED"})
    void detailAndEventsKeepTheSameQualificationAndFactsSnapshot(String action)throws Exception{
        var f=fixture();var id=paid(f);var token=login(f.merchant().user());var gate=new CountDownLatch(1);var release=new CountDownLatch(1);var once=new java.util.concurrent.atomic.AtomicBoolean(true);var real=sqlSession.getMapper(com.campuslife.order.mapper.OrderMapper.class);
        doAnswer(i->{var row=real.ownership(i.getArgument(0),i.getArgument(1));if(once.compareAndSet(true,false)){gate.countDown();await(release);}return row;}).when(orderRows).ownership(eq(f.merchant().shop()),eq(f.merchant().user().id()));
        boolean events=action.startsWith("EVENTS");
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var request=pool.submit(()->merchantCall(token,id,events?"/events":"",null));await(gate);
            if(action.endsWith("ACCEPT")){data(accept(token,id));}else if(action.endsWith("REJECT")){data(reject(token,id,"test"));}else if(action.endsWith("OWNER")){jdbc.update("UPDATE shop_member SET status='DISABLED' WHERE shop_id=?",f.merchant().shop());}else{storage.delete(storage.storageId(),f.file().getStorageKey());jdbc.update("UPDATE file_asset SET status='DELETED',deleted_at=? WHERE id=?",FileRules.utc(NOW),f.file().getId());}
            release.countDown();var result=data(request.get(10,TimeUnit.SECONDS));if(events){assertThat(result.size()).isEqualTo(2);}else{assertThat(result.path("status").asString()).isEqualTo("PENDING_ACCEPTANCE");assertThat(result.path("refund").isNull()).isTrue();assertThat(result.path("printing").path("fileName").asString()).isEqualTo("虚构材料.pdf");}
            if(action.endsWith("OWNER")){expect(merchantCall(token,id,events?"/events":"",null),403,"ROLE_FORBIDDEN");}
        }finally{release.countDown();}
    }
    @Test void printPaginationFiltersAndDeletedFileHistoryRemainFrozen()throws Exception{
        var f=fixture();var unpaid=order(f);var waiting=data(create(f,"waiting")).path("id").asString();data(pay(f,waiting));var closed=data(create(f,"closed")).path("id").asString();data(pay(f,closed));var token=login(f.merchant().user());data(reject(token,closed,"test"));
        jdbc.update("UPDATE shop SET name='changed' WHERE id=?",f.merchant().shop());jdbc.update("UPDATE service_item SET name='changed',enabled=0 WHERE id=?",f.merchant().service());storage.delete(storage.storageId(),f.file().getStorageKey());jdbc.update("UPDATE file_asset SET status='DELETED',deleted_at=? WHERE id=?",FileRules.utc(NOW),f.file().getId());
        var page=list(token,"?orderType=PRINT&size=2");assertThat(page.path("total").asLong()).isEqualTo(3);assertThat(page.path("totalPages").asLong()).isEqualTo(2);assertThat(page.path("items").get(0).path("id").asString()).isEqualTo(closed);assertThat(page.path("items").get(1).path("id").asString()).isEqualTo(waiting);assertThat(list(token,"?page=2&size=2").path("items").get(0).path("id").asString()).isEqualTo(unpaid);assertThat(list(token,"?orderType=BOOKING").path("total").asLong()).isZero();assertThat(list(token,"?page=2147483647&size=100").path("items").size()).isZero();
        for(String state:List.of("PENDING_PAYMENT","PENDING_ACCEPTANCE","ACCEPTED","IN_SERVICE","COMPLETED","CLOSED")){assertThat(list(token,"?orderType=PRINT&status="+state).path("total").asLong()).isEqualTo(Set.of("PENDING_PAYMENT","PENDING_ACCEPTANCE","CLOSED").contains(state)?1:0);}
        var history=data(merchantCall(token,closed,"",null));assertThat(history.path("serviceName").asString()).isEqualTo("模拟打印服务");assertThat(history.path("printing").path("fileName").asString()).isEqualTo("虚构材料.pdf");assertThat(history.path("payableAmount").asLong()).isEqualTo(200);assertThat(history.has("studentId")).isFalse();assertThat(history.toString()).doesNotContain("storageKey","storageId","passwordHash",f.student().name());
    }
    @Test void acceptanceKeepsFixedDecisionTimeAndDoesNotNeedFileVersionIncrement()throws Exception{
        var f=fixture();var id=paid(f);var token=login(f.merchant().user());jdbc.update("UPDATE file_asset SET version=? WHERE id=?",Long.MAX_VALUE,f.file().getId());var before=fileFact(f);var real=sqlSession.getMapper(com.campuslife.order.mapper.OrderMapper.class);doAnswer(i->{int n=real.accept(i.getArgument(0),i.getArgument(1),i.getArgument(2));fixed(NOW.plusSeconds(1801));return n;}).when(orderRows).accept(anyLong(),anyLong(),any());data(accept(token,id));accepted(f,id);assertThat(fileFact(f)).isEqualTo(before);
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
