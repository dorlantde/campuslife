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
class MerchantPrintCancellationMysqlIT {
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


    private HttpResponse<String> action(String token,String id,String suffix,String body)throws Exception{return call(suffix.isEmpty()||suffix.equals("/events")?"GET":"POST","/api/v1/merchant/orders/"+id+suffix,body,token);}
    private HttpResponse<String> accept(String token,String id)throws Exception{return action(token,id,"/accept",null);}
    private HttpResponse<String> merchantCancel(String token,String id,String reason)throws Exception{return action(token,id,"/cancel",json.writeValueAsString(Map.of("reason",reason)));}
    private String accepted(Fixture f)throws Exception{var id=order(f);data(pay(f,id));data(accept(login(f.merchant().user()),id));return id;}
    private JsonNode cancelled(Fixture f,String id,Instant at)throws Exception{
        var o=detail(f,id);assertThat(o.path("status").asString()).isEqualTo("CLOSED");assertThat(o.path("paymentStatus").asString()).isEqualTo("REFUNDED");assertThat(o.path("printing").path("progress").asString()).isEqualTo("ABORTED");assertThat(o.path("closeReason").asString()).isEqualTo("MERCHANT_UNAVAILABLE");assertThat(o.path("payment").path("amount")).isEqualTo(o.path("refund").path("amount"));assertThat(o.path("acceptedAt").asString()).isEqualTo(NOW.toString());assertThat(o.path("startedAt").isNull()).isTrue();assertThat(o.path("printing").path("readyAt").isNull()).isTrue();assertThat(o.path("printing").path("pickedUpAt").isNull()).isTrue();assertThat(count("payment_record",id)).isEqualTo(1);assertThat(count("refund_record",id)).isEqualTo(1);assertThat(count("order_event",id)).isEqualTo(4);if(at!=null){retained(f,at.plus(Duration.ofDays(7)));}return o;
    }
    @Test void actualUploadPaymentAcceptanceAndCancellationPersistOneMerchantRefund()throws Exception{
        var f=fixture();var id=accepted(f);var token=login(f.merchant().user());var original=detail(f,id);var result=data(merchantCancel(token,id,"  接单后设备故障  "));assertThat(result).isEqualTo(cancelled(f,id,NOW));assertThat(result.path("closeNote").asString()).isEqualTo("接单后设备故障");assertThat(result.path("version").asString()).isEqualTo("3");assertThat(result.path("payment")).isEqualTo(original.path("payment"));assertThat(result.path("printing").path("fileName")).isEqualTo(original.path("printing").path("fileName"));
        var event=data(action(token,id,"/events",null));assertThat(event).isEqualTo(data(call("GET","/api/v1/orders/"+id+"/events",null,f.token())));var last=event.get(3);assertThat(last.path("eventSeq").asString()).isEqualTo("3");assertThat(last.path("action").asString()).isEqualTo("MERCHANT_UNAVAILABLE");assertThat(last.path("fromStatus").asString()).isEqualTo("ACCEPTED");assertThat(last.path("toStatus").asString()).isEqualTo("CLOSED");assertThat(last.path("actorType").asString()).isEqualTo("MERCHANT");assertThat(last.path("actorId").asString()).isEqualTo(Long.toString(f.merchant().user().id()));assertThat(last.path("reason").asString()).isEqualTo("接单后设备故障");assertThat(jdbc.queryForObject("SELECT reason_note FROM refund_record WHERE order_id=?",String.class,id)).isEqualTo("接单后设备故障");
        var summary=data(call("GET","/api/v1/merchant/orders?orderType=PRINT&status=CLOSED",null,token));assertThat(summary.path("total").asLong()).isEqualTo(1);assertThat(summary.path("items").get(0).path("printingProgress").asString()).isEqualTo("ABORTED");assertThat(data(action(token,id,"",null))).isEqualTo(result);assertThat(data(create(f,"pay-test"))).isEqualTo(result);var before=facts(f,id);expect(accept(token,id),409,"ORDER_STATE_CONFLICT");expect(cancel(f,id),409,"ORDER_STATE_CONFLICT");expect(pay(f,id),409,"ORDER_STATE_CONFLICT");assertThat(facts(f,id)).isEqualTo(before);
    }
    @Test void sameReasonReplayIgnoresGateExpiredRetentionAndDeletedFileButKeepsOriginalFacts()throws Exception{
        var f=fixture();var id=accepted(f);var token=login(f.merchant().user());var first=data(merchantCancel(token,id,"test"));doReturn(false).when(mockPayment).enabled();fixed(NOW.plus(Duration.ofDays(8)));token=login(f.merchant().user());storage.delete(storage.storageId(),f.file().getStorageKey());jdbc.update("UPDATE file_asset SET status='DELETED',deleted_at=? WHERE id=?",FileRules.utc(NOW.plus(Duration.ofDays(8))),f.file().getId());var before=facts(f,id);assertThat(data(merchantCancel(token,id,"  test  "))).isEqualTo(first);expect(merchantCancel(token,id,"changed"),409,"ORDER_STATE_CONFLICT");assertThat(facts(f,id)).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(longs={1799999,1800000,1800001,3600000})
    void acceptedPrintIsNotBoundToItsOldAcceptanceDeadline(long elapsed)throws Exception{var f=fixture();var id=accepted(f);var token=login(f.merchant().user());fixed(NOW.plusMillis(elapsed));data(merchantCancel(token,id,"test"));cancelled(f,id,NOW.plusMillis(elapsed));assertThat(expiry.scan()).isZero();}
    @ParameterizedTest @ValueSource(strings={"SUSPENDED","OFF","HOURS","SERVICE","PRICE","STUDENT","DISK"})
    void oldOrderResponsibilitySurvivesCurrentConfigurationOrStudentAndDiskChanges(String change)throws Exception{
        var f=fixture();var id=accepted(f);var token=login(f.merchant().user());switch(change){case "SUSPENDED"->jdbc.update("UPDATE shop SET approval_status='SUSPENDED' WHERE id=?",f.merchant().shop());case "OFF"->jdbc.update("UPDATE shop SET business_enabled=0 WHERE id=?",f.merchant().shop());case "HOURS"->jdbc.update("DELETE FROM shop_business_hour WHERE shop_id=?",f.merchant().shop());case "SERVICE"->jdbc.update("UPDATE service_item SET enabled=0,name='changed' WHERE id=?",f.merchant().service());case "PRICE"->jdbc.update("UPDATE print_price_rule SET enabled=0,price_per_impression=999 WHERE service_id=?",f.merchant().service());case "STUDENT"->jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?",f.student().id());case "DISK"->storage.delete(storage.storageId(),f.file().getStorageKey());}
        var result=data(merchantCancel(token,id,"test"));assertThat(result.path("status").asString()).isEqualTo("CLOSED");assertThat(result.path("refund").path("amount").asLong()).isEqualTo(200);retained(f,NOW.plus(Duration.ofDays(7)));assertThat(data(merchantCancel(token,id,"test"))).isEqualTo(result);
    }
    @ParameterizedTest @ValueSource(strings={"ANONYMOUS","INVALID","STUDENT","ADMIN","DISABLED","REVOKED","EXPIRED","OWNER_DISABLED","OWNER_PENDING","OTHER","NO_SHOP"})
    void currentRoleSessionMembershipAndScopeAreRequiredEvenForReplay(String change)throws Exception{
        var f=fixture();var id=accepted(f);String token=login(f.merchant().user());data(merchantCancel(token,id,"test"));int status=403;String code="ROLE_FORBIDDEN";
        switch(change){case "ANONYMOUS"->{token=null;status=401;code="AUTH_REQUIRED";}case "INVALID"->{token="invalid";status=401;code="TOKEN_INVALID";}case "STUDENT","ADMIN"->token=login(user(change));case "DISABLED"->{invalidate(f.merchant().user(),change);code="ACCOUNT_DISABLED";}case "REVOKED"->{invalidate(f.merchant().user(),change);status=401;code="SESSION_REVOKED";}case "EXPIRED"->{invalidate(f.merchant().user(),change);status=401;code="SESSION_EXPIRED";}case "OWNER_DISABLED","OWNER_PENDING"->jdbc.update("UPDATE shop_member SET status=? WHERE shop_id=?",change.equals("OWNER_PENDING")?"PENDING":"DISABLED",f.merchant().shop());case "OTHER"->{token=login(merchant("PRINT").user());status=404;code="RESOURCE_NOT_FOUND";}case "NO_SHOP"->{token=login(user("MERCHANT"));status=404;code="RESOURCE_NOT_FOUND";}}
        var before=facts(f,id);expect(merchantCancel(token,id,"test"),status,code);assertThat(facts(f,id)).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"{}","null","[]","{\"reason\":null}","{\"reason\":1}","{\"reason\":\" \"}","{\"reason\":\"x\",\"expectedVersion\":\"2\"}","LONG"})
    void strictReasonValidationCannotChangeAnAcceptedOrder(String body)throws Exception{var f=fixture();var id=accepted(f);var token=login(f.merchant().user());if(body.equals("LONG")){body=json.writeValueAsString(Map.of("reason","😀".repeat(301)));}var before=facts(f,id);expect(action(token,id,"/cancel",body),400,"VALIDATION_FAILED");assertThat(facts(f,id)).isEqualTo(before);}
    @Test void maximumUnicodeReasonIsPreservedInRefundAndEvent()throws Exception{var f=fixture();var id=accepted(f);var reason="😀".repeat(300);var result=data(merchantCancel(login(f.merchant().user()),id,reason));assertThat(result.path("closeNote").asString()).isEqualTo(reason);assertThat(jdbc.queryForObject("SELECT reason FROM order_event WHERE order_id=? AND event_seq=3",String.class,id)).isEqualTo(reason);}
    @ParameterizedTest @ValueSource(strings={"0","01","-1","+1","9223372036854775808","x"})
    void invalidOrderIdsDoNotReachBusinessWrites(String id)throws Exception{var f=fixture();var actual=accepted(f);var before=facts(f,actual);expect(merchantCancel(login(f.merchant().user()),id,"test"),400,"VALIDATION_FAILED");assertThat(facts(f,actual)).isEqualTo(before);}
    @ParameterizedTest @ValueSource(strings={"UNPAID","AWAITING","OVERDUE","STUDENT_CLOSED","REJECTED","TIMEOUT","PRINTING","READY","COMPLETED","NO_ACCEPTED_AT","STARTED_AT","READY_AT","PICKED_AT"})
    void onlyAcceptedPaidQueuedAndUnstartedCombinationCanBeCancelled(String state)throws Exception{
        var f=fixture();var id=order(f);var token=login(f.merchant().user());if(!state.equals("UNPAID")){data(pay(f,id));}
        if(state.equals("OVERDUE")){fixed(NOW.plusSeconds(1800));}else if(state.equals("STUDENT_CLOSED")){data(cancel(f,id));}else if(state.equals("REJECTED")){data(action(token,id,"/reject","{\"reason\":\"test\"}"));}else if(state.equals("TIMEOUT")){fixed(NOW.plusSeconds(1800));assertThat(printTransactions.expire(Long.parseLong(id))).isTrue();}else if(!Set.of("UNPAID","AWAITING").contains(state)){
            data(accept(token,id));switch(state){case "PRINTING","READY"->{jdbc.update("UPDATE service_order SET status='IN_SERVICE',started_at=? WHERE id=?",FileRules.utc(NOW),id);jdbc.update("UPDATE print_detail SET progress=? WHERE order_id=?",state.equals("READY")?"READY_FOR_PICKUP":"PRINTING",id);}case "COMPLETED"->{jdbc.update("UPDATE service_order SET status='COMPLETED',started_at=?,completed_at=? WHERE id=?",FileRules.utc(NOW),FileRules.utc(NOW),id);jdbc.update("UPDATE print_detail SET progress='PICKED_UP' WHERE order_id=?",id);}case "NO_ACCEPTED_AT"->jdbc.update("UPDATE service_order SET accepted_at=NULL WHERE id=?",id);case "STARTED_AT"->jdbc.update("UPDATE service_order SET started_at=? WHERE id=?",FileRules.utc(NOW),id);case "READY_AT"->jdbc.update("UPDATE print_detail SET ready_at=? WHERE order_id=?",FileRules.utc(NOW),id);case "PICKED_AT"->jdbc.update("UPDATE print_detail SET picked_up_at=? WHERE order_id=?",FileRules.utc(NOW),id);}
        }
        var before=facts(f,id);expect(merchantCancel(token,id,"test"),409,"ORDER_STATE_CONFLICT");assertThat(facts(f,id)).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"EXPIRY","DELETING","DELETED","OWNER"})
    void brokenProtectionMetadataRollsBackWithoutARefund(String change)throws Exception{var f=fixture();var id=accepted(f);var token=login(f.merchant().user());switch(change){case "EXPIRY"->expiryAt(f,NOW.plusSeconds(1));case "DELETING"->jdbc.update("UPDATE file_asset SET status='DELETING' WHERE id=?",f.file().getId());case "DELETED"->jdbc.update("UPDATE file_asset SET status='DELETED',deleted_at=? WHERE id=?",FileRules.utc(NOW),f.file().getId());case "OWNER"->jdbc.update("UPDATE file_asset SET owner_id=? WHERE id=?",f.merchant().user().id(),f.file().getId());}var before=facts(f,id);expect(merchantCancel(token,id,"test"),503,"FILE_STORAGE_UNAVAILABLE");assertThat(facts(f,id)).isEqualTo(before);}
    @Test void gateOffPreservesAcceptedOrderAndRecoveryCreatesOnlyOneRefund()throws Exception{var f=fixture();var id=accepted(f);var token=login(f.merchant().user());doReturn(false).when(mockPayment).enabled();var before=facts(f,id);expect(merchantCancel(token,id,"test"),503,"MOCK_PAYMENT_DISABLED");assertThat(facts(f,id)).isEqualTo(before);doReturn(true).when(mockPayment).enabled();var result=data(merchantCancel(token,id,"test"));doReturn(false).when(mockPayment).enabled();before=facts(f,id);assertThat(data(merchantCancel(token,id,"test"))).isEqualTo(result);assertThat(facts(f,id)).isEqualTo(before);}
    @Test void fiveBillionCentsAreFullyRefundedAfterAcceptance()throws Exception{var f=fixture();var large=row(data(upload(f.token(),"500.pdf","application/pdf",PdfSamples.pdf(500))));f.body().put("fileId",large.getId().toString());f.body().put("copies",100);f.body().put("expectedPayableAmount",5000000000L);jdbc.update("UPDATE print_price_rule SET price_per_impression=100000 WHERE service_id=?",f.merchant().service());var id=accepted(f);var result=data(merchantCancel(login(f.merchant().user()),id,"test"));assertThat(result.path("refund").path("amount").asLong()).isEqualTo(5000000000L);assertThat(result.path("payment").path("amount").asLong()).isEqualTo(5000000000L);}
    @ParameterizedTest @ValueSource(strings={"REFUND","STATUS","PROGRESS","EVENT","RETENTION","SESSION"})
    void failureAtAnyWriteOrFinalSessionCheckRollsBackEveryFact(String point)throws Exception{
        var f=fixture();var id=accepted(f);var token=login(f.merchant().user());var before=facts(f,id);
        switch(point){case "REFUND"->doThrow(new IllegalStateException("injected")).when(money).refundWithNote(anyLong(),anyLong(),anyString(),anyLong(),anyString(),anyString(),any());case "STATUS"->doThrow(new IllegalStateException("injected")).when(orderRows).refundCloseFrom(anyLong(),anyLong(),anyString(),anyString(),anyString(),any());case "PROGRESS"->doThrow(new IllegalStateException("injected")).when(printRows).abort(anyLong());case "EVENT"->doThrow(new IllegalStateException("injected")).when(orderEvents).append(anyLong(),eq(3L),eq("MERCHANT_UNAVAILABLE"),eq("ACCEPTED"),eq("CLOSED"),eq("MERCHANT"),anyLong(),anyString(),any());case "RETENTION"->doThrow(new IllegalStateException("injected")).when(files).expiry(anyLong(),anyLong(),nullable(LocalDateTime.class),any());case "SESSION"->{var real=sqlSession.getMapper(com.campuslife.order.mapper.MoneyMapper.class);doAnswer(i->{int n=real.refundWithNote(i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(3),i.getArgument(4),i.getArgument(5),i.getArgument(6));fixed(NOW.plusSeconds(7200));return n;}).when(money).refundWithNote(anyLong(),anyLong(),anyString(),anyLong(),anyString(),anyString(),any());}}
        assertThat(merchantCancel(token,id,"test").statusCode()).isEqualTo(point.equals("SESSION")?401:500);assertThat(facts(f,id)).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"ORDER","FILE","UNCHANGED_FILE"})
    void exhaustedVersionsAreOnlyNeededForActualWrites(String target)throws Exception{var f=fixture();var id=accepted(f);var token=login(f.merchant().user());if(target.equals("UNCHANGED_FILE")){data(create(f,"other"));}jdbc.update(target.equals("ORDER")?"UPDATE service_order SET version=? WHERE id=?":"UPDATE file_asset SET version=? WHERE id=?",Long.MAX_VALUE,target.equals("ORDER")?id:f.file().getId());var before=facts(f,id);var result=merchantCancel(token,id,"test");if(target.equals("UNCHANGED_FILE")){expect(result,200,"OK");retained(f,null);assertThat(fileFact(f)).isEqualTo(before.get("file"));}else{expect(result,target.equals("ORDER")?409:503,target.equals("ORDER")?"ORDER_STATE_CONFLICT":"FILE_STORAGE_UNAVAILABLE");assertThat(facts(f,id)).isEqualTo(before);}}
    @ParameterizedTest @ValueSource(strings={"MISSING","AMOUNT","PAID_AT"})
    void mismatchingPaymentCannotCommitAnyRefundOrClose(String change)throws Exception{var f=fixture();var id=accepted(f);var token=login(f.merchant().user());switch(change){case "MISSING"->jdbc.update("DELETE FROM payment_record WHERE order_id=?",id);case "AMOUNT"->jdbc.update("UPDATE payment_record SET amount=201 WHERE order_id=?",id);case "PAID_AT"->jdbc.update("UPDATE payment_record SET paid_at=? WHERE order_id=?",FileRules.utc(NOW.plusMillis(1)),id);}var before=facts(f,id);assertThat(merchantCancel(token,id,"test").statusCode()).isEqualTo(500);assertThat(facts(f,id)).isEqualTo(before);}
    @Test void crossShopReferencesKeepProtectionUntilLastActualTerminalTime()throws Exception{var f=fixture();var id=accepted(f);var token=login(f.merchant().user());var other=merchant("PRINT");f.body().put("serviceId",Long.toString(other.service()));var second=data(create(f,"other-shop")).path("id").asString();data(pay(f,second));var otherToken=login(other.user());data(accept(otherToken,second));long version=files.selectById(f.file().getId()).getVersion();data(merchantCancel(token,id,"test"));retained(f,null);assertThat(files.selectById(f.file().getId()).getVersion()).isEqualTo(version);fixed(NOW.plusSeconds(10));data(merchantCancel(otherToken,second,"test"));retained(f,NOW.plusSeconds(10).plus(Duration.ofDays(7)));}
    @ParameterizedTest @ValueSource(strings={"SAME","DIFFERENT","ACCEPT","STUDENT","SCAN","CREATE","OTHER_CLOSE"})
    void concurrentRealMysqlActionsKeepUniqueRefundAndCorrectFileProtection(String race)throws Exception{
        var f=fixture();var id=accepted(f);var token=login(f.merchant().user());String second=race.equals("OTHER_CLOSE")?data(create(f,"other")).path("id").asString():null;
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var gate=new CountDownLatch(1);var one=pool.submit(()->{await(gate);return merchantCancel(token,id,"test");});var two=pool.submit(()->{await(gate);return switch(race){case "SAME"->merchantCancel(token,id,"test");case "DIFFERENT"->merchantCancel(token,id,"other");case "ACCEPT"->accept(token,id);case "STUDENT"->cancel(f,id);case "CREATE"->create(f,"new-reference");case "OTHER_CLOSE"->cancel(f,second);default->{expiry.scan();yield null;}};});gate.countDown();var a=one.get(20,TimeUnit.SECONDS);var b=two.get(20,TimeUnit.SECONDS);assertThat(a.statusCode()).as(a.body()).isIn(200,409);if(b!=null){assertThat(b.statusCode()).as(b.body()).isIn(200,201,409);}if(race.equals("SAME")){assertThat(a.statusCode()).isEqualTo(200);assertThat(b.statusCode()).isEqualTo(200);}if(race.equals("DIFFERENT")){assertThat(List.of(a.statusCode(),b.statusCode())).containsExactlyInAnyOrder(200,409);}
        }
        cancelled(f,id,race.equals("CREATE")?null:NOW);if(race.equals("CREATE")){retained(f,null);}assertThat(expiry.scan()).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"SHOP","FILE","ORDER"})
    void cancellationReadsDecisionTimeAfterRealLockWait(String target)throws Exception{
        var f=fixture();var id=accepted(f);var token=login(f.merchant().user());var held=new CountDownLatch(1);var release=new CountDownLatch(1);var entering=new CountDownLatch(1);var tx=new TransactionTemplate(manager);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var holder=pool.submit(()->tx.execute(s->{jdbc.queryForList(switch(target){case "SHOP"->"SELECT id FROM shop WHERE id=? FOR UPDATE";case "FILE"->"SELECT id FROM file_asset WHERE id=? FOR UPDATE";default->"SELECT id FROM service_order WHERE id=? FOR UPDATE";},switch(target){case "SHOP"->f.merchant().shop();case "FILE"->f.file().getId();default->Long.parseLong(id);});held.countDown();await(release);return null;}));await(held);
            if(target.equals("FILE")){var real=sqlSession.getMapper(FileAssetMapper.class);doAnswer(i->{entering.countDown();return real.lock(i.getArgument(0));}).when(files).lock(eq(f.file().getId()));}else{var real=sqlSession.getMapper(com.campuslife.order.mapper.OrderMapper.class);if(target.equals("SHOP")){doAnswer(i->{entering.countDown();return real.shareShop(i.getArgument(0));}).when(orderRows).shareShop(eq(f.merchant().shop()));}else{doAnswer(i->{entering.countDown();return real.lock(i.getArgument(0));}).when(orderRows).lock(eq(Long.parseLong(id)));}}
            var request=pool.submit(()->merchantCancel(token,id,"test"));await(entering);fixed(NOW.plusSeconds(1801));release.countDown();holder.get(10,TimeUnit.SECONDS);var result=data(request.get(15,TimeUnit.SECONDS));assertThat(result.path("closedAt").asString()).isEqualTo(NOW.plusSeconds(1801).toString());cancelled(f,id,NOW.plusSeconds(1801));
        }finally{release.countDown();}
    }
    @Test void lockedProgressOverridesAcceptedLocatorAfterAnotherWriterCommits()throws Exception{
        var f=fixture();var id=accepted(f);var token=login(f.merchant().user());var held=new CountDownLatch(1);var release=new CountDownLatch(1);var entering=new CountDownLatch(1);var tx=new TransactionTemplate(manager);var real=sqlSession.getMapper(FileAssetMapper.class);doAnswer(i->{entering.countDown();return real.lock(i.getArgument(0));}).when(files).lock(eq(f.file().getId()));
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var writer=pool.submit(()->tx.execute(s->{jdbc.queryForList("SELECT id FROM shop WHERE id=? FOR SHARE",f.merchant().shop());jdbc.queryForList("SELECT id FROM file_asset WHERE id=? FOR UPDATE",f.file().getId());held.countDown();await(release);jdbc.update("UPDATE service_order SET status='IN_SERVICE',started_at=?,version=version+1 WHERE id=?",FileRules.utc(NOW),id);jdbc.update("UPDATE print_detail SET progress='PRINTING' WHERE order_id=?",id);return null;}));await(held);var request=pool.submit(()->merchantCancel(token,id,"test"));await(entering);release.countDown();writer.get(10,TimeUnit.SECONDS);expect(request.get(15,TimeUnit.SECONDS),409,"ORDER_STATE_CONFLICT");assertThat(count("refund_record",id)).isZero();assertThat(count("order_event",id)).isEqualTo(3);retained(f,null);
        }finally{release.countDown();}
    }
    @Test void closedAtIsFixedAcrossWritesAndIoAndTargetStudentLocksAreAbsent()throws Exception{
        var f=fixture();var id=accepted(f);var token=login(f.merchant().user());var guard=storage.lock(f.file().getStorageKey());guard.lock();var held=new CountDownLatch(1);var release=new CountDownLatch(1);var tx=new TransactionTemplate(manager);var real=sqlSession.getMapper(com.campuslife.order.mapper.MoneyMapper.class);doAnswer(i->{int n=real.refundWithNote(i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(3),i.getArgument(4),i.getArgument(5),i.getArgument(6));fixed(NOW.plusSeconds(1801));return n;}).when(money).refundWithNote(anyLong(),anyLong(),anyString(),anyLong(),anyString(),anyString(),any());clearInvocations(disk);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var holder=pool.submit(()->tx.execute(s->{jdbc.queryForList("SELECT id FROM account WHERE id=? FOR UPDATE",f.student().id());held.countDown();await(release);return null;}));await(held);var result=data(pool.submit(()->merchantCancel(token,id,"test")).get(5,TimeUnit.SECONDS));assertThat(result.path("closedAt").asString()).isEqualTo(NOW.toString());verify(disk,never()).open(anyString(),anyString(),anyLong());verify(disk,never()).lock(anyString());release.countDown();holder.get(10,TimeUnit.SECONDS);cancelled(f,id,NOW);
        }finally{release.countDown();guard.unlock();}
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
