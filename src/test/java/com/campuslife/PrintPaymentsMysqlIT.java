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
class PrintPaymentsMysqlIT {
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
    @MockitoSpyBean com.campuslife.order.mapper.MoneyMapper money; @MockitoSpyBean com.campuslife.order.service.MockPaymentPolicy mockPayment;
    @Autowired com.campuslife.order.service.PrintOrderTransactions printTransactions;
    @BeforeEach void freeze(){storage=disk;fixed(NOW);doReturn(true).when(mockPayment).enabled();}
    @AfterEach void cleanupOwned()throws Exception {
        reset(files,prices,services,shops,sessions,clock,orderEvents,keys,printRows,disk,orderRows,money,mockPayment);
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
    @Test void payCancelReplayListAndEventsShowOnlyDurableMoneyFacts()throws Exception{
        var f=fixture();var id=order(f);var fileBefore=fileFact(f);var first=data(pay(f,id));assertPaid(f,id);
        assertThat(first.path("amount").asLong()).isEqualTo(200);assertThat(first.path("paidAt").asString()).isEqualTo(NOW.toString());assertThat(first.path("acceptanceDeadline").asString()).isEqualTo(NOW.plusSeconds(1800).toString());assertThat(fileFact(f)).isEqualTo(fileBefore);var paid=facts(f,id);
        fixed(NOW.plusSeconds(30));assertThat(data(pay(f,id))).isEqualTo(first);assertThat(facts(f,id)).isEqualTo(paid);assertThat(data(create(f,"pay-test"))).isEqualTo(detail(f,id));
        var list=data(call("GET","/api/v1/orders?orderType=PRINT&status=PENDING_ACCEPTANCE",null,f.token()));assertThat(list.path("total").asLong()).isEqualTo(1);assertThat(list.path("items").get(0).path("printingProgress").asString()).isEqualTo("QUEUED");
        var result=data(cancel(f,id));assertRefunded(f,id,"USER_CANCEL",NOW.plusSeconds(30));var closed=facts(f,id);assertThat(data(cancel(f,id))).isEqualTo(result);assertThat(data(create(f,"pay-test"))).isEqualTo(result);assertThat(facts(f,id)).isEqualTo(closed);expect(pay(f,id),409,"ORDER_STATE_CONFLICT");
        var ev=data(call("GET","/api/v1/orders/"+id+"/events",null,f.token()));assertThat(ev.size()).isEqualTo(3);assertThat(ev.get(1).path("action").asString()).isEqualTo("PAYMENT");assertThat(ev.get(2).path("eventSeq").asString()).isEqualTo("2");assertThat(ev.get(2).path("actorType").asString()).isEqualTo("STUDENT");assertThat(ev.get(2).path("reason").asString()).isEqualTo("USER_CANCEL");
    }
    @ParameterizedTest @ValueSource(strings={"OFF","REST","SERVICE_OFF","PRICE_OFF","CHANGED_PRICE","DISK_MISSING"})
    void originalSnapshotRemainsPayableWithoutBusinessHoursPriceOrDiskRecheck(String change)throws Exception{
        var f=fixture();var id=order(f);
        switch(change){case "OFF"->jdbc.update("UPDATE shop SET business_enabled=0 WHERE id=?",f.merchant().shop());case "REST"->jdbc.update("DELETE FROM shop_business_hour WHERE shop_id=?",f.merchant().shop());case "SERVICE_OFF"->jdbc.update("UPDATE service_item SET enabled=0 WHERE id=?",f.merchant().service());case "PRICE_OFF"->jdbc.update("UPDATE print_price_rule SET enabled=0 WHERE service_id=?",f.merchant().service());case "CHANGED_PRICE"->jdbc.update("UPDATE print_price_rule SET price_per_impression=99,version=version+1 WHERE service_id=?",f.merchant().service());case "DISK_MISSING"->storage.delete(storage.storageId(),f.file().getStorageKey());}
        clearInvocations(disk);var p=data(pay(f,id));assertThat(p.path("amount").asLong()).isEqualTo(200);verify(disk,never()).open(anyString(),anyString(),anyLong());assertPaid(f,id);data(cancel(f,id));assertRefunded(f,id,"USER_CANCEL",NOW);
    }
    @ParameterizedTest @ValueSource(strings={"SUSPENDED","OWNER_DISABLED","ACCOUNT_DISABLED","ROLE_CHANGED","CATEGORY"})
    void firstPaymentRequiresCurrentPublicPrintEligibility(String change)throws Exception{
        var f=fixture();var id=order(f);
        switch(change){case "SUSPENDED"->jdbc.update("UPDATE shop SET approval_status='SUSPENDED' WHERE id=?",f.merchant().shop());case "OWNER_DISABLED"->jdbc.update("UPDATE shop_member SET status='DISABLED' WHERE shop_id=?",f.merchant().shop());case "ACCOUNT_DISABLED"->jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?",f.merchant().user().id());case "ROLE_CHANGED"->jdbc.update("UPDATE account SET role='STUDENT' WHERE id=?",f.merchant().user().id());case "CATEGORY"->jdbc.update("UPDATE shop SET category_id=(SELECT id FROM shop_category WHERE code='HAIRCUT') WHERE id=?",f.merchant().shop());}
        var before=facts(f,id);expect(pay(f,id),409,"SHOP_UNAVAILABLE");assertThat(facts(f,id)).isEqualTo(before);data(cancel(f,id));assertThat(detail(f,id).path("paymentStatus").asString()).isEqualTo("NOT_PAID");
    }
    @ParameterizedTest @ValueSource(strings={"EXPIRY","DELETING","DELETED","OTHER"})
    void paymentRejectsBrokenProtectionMetadataWithoutFunds(String change)throws Exception{
        var f=fixture();var id=order(f);
        switch(change){case "EXPIRY"->expiryAt(f,NOW.plusSeconds(3600));case "DELETING"->jdbc.update("UPDATE file_asset SET status='DELETING' WHERE id=?",f.file().getId());case "DELETED"->jdbc.update("UPDATE file_asset SET status='DELETED',deleted_at=? WHERE id=?",FileRules.utc(NOW),f.file().getId());case "OTHER"->jdbc.update("UPDATE file_asset SET owner_id=? WHERE id=?",f.merchant().user().id(),f.file().getId());}
        var before=facts(f,id);expect(pay(f,id),503,"FILE_STORAGE_UNAVAILABLE");assertThat(facts(f,id)).isEqualTo(before);
    }
    @Test void fiveBillionCentsSurvivePaymentAndFullRefund()throws Exception{
        var f=fixture();var large=row(data(upload(f.token(),"500.pdf","application/pdf",PdfSamples.pdf(500))));f.body().put("fileId",large.getId().toString());f.body().put("copies",100);f.body().put("expectedPayableAmount",5000000000L);jdbc.update("UPDATE print_price_rule SET price_per_impression=100000 WHERE service_id=?",f.merchant().service());
        var id=order(f);assertThat(data(pay(f,id)).path("amount").asLong()).isEqualTo(5000000000L);var o=data(cancel(f,id));assertThat(o.path("refund").path("amount").asLong()).isEqualTo(5000000000L);assertThat(o.path("payment").path("amount").asLong()).isEqualTo(5000000000L);
    }
    @ParameterizedTest @ValueSource(strings={"ANONYMOUS","INVALID","MERCHANT","ADMIN","DISABLED","REVOKED","EXPIRED","OTHER"})
    void bothMoneyActionsRespectCurrentIdentityAndOwnership(String change)throws Exception{
        var f=fixture();var id=order(f);String token=f.token();int status=403;String code="ROLE_FORBIDDEN";
        switch(change){case "ANONYMOUS"->{token=null;status=401;code="AUTH_REQUIRED";}case "INVALID"->{token="invalid";status=401;code="TOKEN_INVALID";}case "MERCHANT","ADMIN"->token=login(user(change));case "DISABLED"->{invalidate(f.student(),change);code="ACCOUNT_DISABLED";}case "REVOKED"->{invalidate(f.student(),change);status=401;code="SESSION_REVOKED";}case "EXPIRED"->{invalidate(f.student(),change);status=401;code="SESSION_EXPIRED";}case "OTHER"->{token=login(user("STUDENT"));status=404;code="RESOURCE_NOT_FOUND";}}
        var before=facts(f,id);expect(call("POST","/api/v1/orders/"+id+"/payments/mock",null,token),status,code);expect(call("POST","/api/v1/orders/"+id+"/cancel",null,token),status,code);assertThat(facts(f,id)).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"{}","null"," ","{\"amount\":1}","[]"})
    void paymentHasNoBusinessBodyEvenForReplay(String body)throws Exception{
        var f=fixture();var id=order(f);var before=facts(f,id);expect(call("POST","/api/v1/orders/"+id+"/payments/mock",body,f.token()),400,"VALIDATION_FAILED");assertThat(facts(f,id)).isEqualTo(before);data(pay(f,id));before=facts(f,id);expect(call("POST","/api/v1/orders/"+id+"/payments/mock",body,f.token()),400,"VALIDATION_FAILED");assertThat(facts(f,id)).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(longs={899999,900000,900001})
    void exactPaymentDeadlineUsesLockDecisionAndCommitsTimeoutBefore409(long elapsed)throws Exception{
        var f=fixture();var id=order(f);fixed(NOW.plusMillis(elapsed));var p=pay(f,id);
        if(elapsed<900000){expect(p,200,"OK");assertPaid(f,id);}else{expect(p,409,"PAYMENT_DEADLINE_PASSED");assertThat(detail(f,id).path("closeReason").asString()).isEqualTo("PAYMENT_TIMEOUT");assertThat(count("payment_record",id)).isZero();assertThat(count("order_event",id)).isEqualTo(2);retained(f,NOW.plusMillis(elapsed).plus(Duration.ofDays(7)));expect(pay(f,id),409,"PAYMENT_DEADLINE_PASSED");}
    }
    @ParameterizedTest @ValueSource(longs={1799999,1800000,1800001})
    void exactAcceptanceDeadlineCommitsRefundBefore409(long elapsed)throws Exception{
        var f=fixture();var id=order(f);data(pay(f,id));fixed(NOW.plusMillis(elapsed));var c=cancel(f,id);expect(c,elapsed<1800000?200:409,elapsed<1800000?"OK":"ACCEPTANCE_DEADLINE_PASSED");assertRefunded(f,id,elapsed<1800000?"USER_CANCEL":"ACCEPTANCE_TIMEOUT",NOW.plusMillis(elapsed));var before=facts(f,id);fixed(NOW.plusMillis(elapsed+1));expect(cancel(f,id),elapsed<1800000?200:409,elapsed<1800000?"OK":"ACCEPTANCE_DEADLINE_PASSED");assertThat(expiry.scan()).isZero();assertThat(facts(f,id)).isEqualTo(before);
    }
    @Test void existingPaidReplayIgnoresGateShopAndFileAndDoesNotAutoRefundOverdue()throws Exception{
        var f=fixture();var id=order(f);var first=data(pay(f,id));jdbc.update("UPDATE shop SET approval_status='SUSPENDED' WHERE id=?",f.merchant().shop());jdbc.update("UPDATE file_asset SET status='DELETING' WHERE id=?",f.file().getId());doReturn(false).when(mockPayment).enabled();fixed(NOW.plusSeconds(1801));var before=facts(f,id);assertThat(data(pay(f,id))).isEqualTo(first);assertThat(facts(f,id)).isEqualTo(before);
    }
    @Test void defaultOffAndRecoveryKeepOriginalDeadlinesAndProtectedFile()throws Exception{
        var f=fixture();var id=order(f);doReturn(false).when(mockPayment).enabled();var before=facts(f,id);expect(pay(f,id),503,"MOCK_PAYMENT_DISABLED");assertThat(facts(f,id)).isEqualTo(before);doReturn(true).when(mockPayment).enabled();var paid=data(pay(f,id));doReturn(false).when(mockPayment).enabled();
        var second=data(create(f,"unpaid")).path("id").asString();fixed(NOW.plusSeconds(1800));before=facts(f,id);expect(cancel(f,id),503,"MOCK_PAYMENT_DISABLED");assertThat(facts(f,id)).isEqualTo(before);assertThat(expiry.scan()).isEqualTo(1);assertThat(detail(f,second).path("closeReason").asString()).isEqualTo("PAYMENT_TIMEOUT");retained(f,null);assertThat(data(pay(f,id))).isEqualTo(paid);
        doReturn(true).when(mockPayment).enabled();fixed(NOW.plusSeconds(1810));assertThat(expiry.scan()).isEqualTo(1);assertRefunded(f,id,"ACCEPTANCE_TIMEOUT",NOW.plusSeconds(1810));doReturn(false).when(mockPayment).enabled();var closed=facts(f,id);expect(cancel(f,id),409,"ACCEPTANCE_DEADLINE_PASSED");assertThat(expiry.scan()).isZero();assertThat(facts(f,id)).isEqualTo(closed);
    }
    @Test void paidUserCancellationReplayStillWorksWithGateOff()throws Exception{var f=fixture();var id=order(f);data(pay(f,id));var result=data(cancel(f,id));doReturn(false).when(mockPayment).enabled();assertThat(data(cancel(f,id))).isEqualTo(result);expect(pay(f,id),409,"ORDER_STATE_CONFLICT");}
    @Test void paidCancellationDoesNotRequireLiveShopOrDiskAndSystemIgnoresDisabledStudent()throws Exception{
        var f=fixture();var first=order(f);data(pay(f,first));var second=data(create(f,"second")).path("id").asString();data(pay(f,second));jdbc.update("UPDATE shop SET approval_status='SUSPENDED' WHERE id=?",f.merchant().shop());jdbc.update("UPDATE shop_member SET status='DISABLED' WHERE shop_id=?",f.merchant().shop());jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?",f.merchant().user().id());storage.delete(storage.storageId(),f.file().getStorageKey());data(cancel(f,first));retained(f,null);
        jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?",f.student().id());fixed(NOW.plusSeconds(1800));assertThat(expiry.scan()).isEqualTo(1);jdbc.update("UPDATE account SET status='ACTIVE' WHERE id=?",f.student().id());assertRefunded(f,second,"ACCEPTANCE_TIMEOUT",NOW.plusSeconds(1800));
    }
    @Test void mixedPaidAndUnpaidAcrossShopsOnlyLastTerminalStartsRetention()throws Exception{
        var f=fixture();var paid=order(f);data(pay(f,paid));var m=merchant("PRINT");f.body().put("serviceId",Long.toString(m.service()));var unpaid=data(create(f,"other-shop")).path("id").asString();long version=files.selectById(f.file().getId()).getVersion();data(cancel(f,paid));retained(f,null);assertThat(files.selectById(f.file().getId()).getVersion()).isEqualTo(version);fixed(NOW.plusSeconds(10));data(cancel(f,unpaid));retained(f,NOW.plusSeconds(10).plus(Duration.ofDays(7)));
    }
    @ParameterizedTest @ValueSource(strings={"PAYMENT","STATUS","EVENT","SESSION"})
    void paymentFailuresRollbackAllFundsOrderAndEvents(String point)throws Exception{
        var f=fixture();var id=order(f);var before=facts(f,id);
        switch(point){case "PAYMENT"->doThrow(new IllegalStateException("injected")).when(money).pay(anyLong(),anyString(),anyLong(),any());case "STATUS"->doThrow(new IllegalStateException("injected")).when(orderRows).pay(anyLong(),anyLong(),any(),any());case "EVENT"->doThrow(new IllegalStateException("injected")).when(orderEvents).append(anyLong(),eq(1L),eq("PAYMENT"),anyString(),anyString(),anyString(),anyLong(),isNull(),any());case "SESSION"->{var real=sqlSession.getMapper(com.campuslife.order.mapper.MoneyMapper.class);doAnswer(i->{int n=real.pay(i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(3));fixed(NOW.plusSeconds(7200));return n;}).when(money).pay(anyLong(),anyString(),anyLong(),any());}}
        assertThat(pay(f,id).statusCode()).isEqualTo(point.equals("SESSION")?401:500);assertThat(facts(f,id)).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"REFUND","STATUS","PROGRESS","EVENT","RETENTION","SESSION"})
    void refundFailuresRollbackEveryFactAndRetention(String point)throws Exception{
        var f=fixture();var id=order(f);data(pay(f,id));var before=facts(f,id);
        switch(point){case "REFUND"->doThrow(new IllegalStateException("injected")).when(money).refund(anyLong(),anyLong(),anyString(),anyLong(),anyString(),any());case "STATUS"->doThrow(new IllegalStateException("injected")).when(orderRows).refundClose(anyLong(),anyLong(),anyString(),any());case "PROGRESS"->doThrow(new IllegalStateException("injected")).when(printRows).abort(anyLong());case "EVENT"->doThrow(new IllegalStateException("injected")).when(orderEvents).append(anyLong(),eq(2L),anyString(),anyString(),anyString(),anyString(),nullable(Long.class),nullable(String.class),any());case "RETENTION"->doThrow(new IllegalStateException("injected")).when(files).expiry(anyLong(),anyLong(),nullable(LocalDateTime.class),any());case "SESSION"->{var real=sqlSession.getMapper(com.campuslife.order.mapper.MoneyMapper.class);doAnswer(i->{int n=real.refund(i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(3),i.getArgument(4),i.getArgument(5));fixed(NOW.plusSeconds(7200));return n;}).when(money).refund(anyLong(),anyLong(),anyString(),anyLong(),anyString(),any());}}
        assertThat(cancel(f,id).statusCode()).isEqualTo(point.equals("SESSION")?401:500);assertThat(facts(f,id)).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"ORDER_PAY","ORDER_REFUND","FILE_REFUND","FILE_PAY"})
    void versionOverflowCannotLeaveHalfMoneyFacts(String point)throws Exception{
        var f=fixture();var id=order(f);if(point.endsWith("REFUND")){data(pay(f,id));}jdbc.update(point.startsWith("ORDER")?"UPDATE service_order SET version=? WHERE id=?":"UPDATE file_asset SET version=? WHERE id=?",Long.MAX_VALUE,point.startsWith("ORDER")?id:f.file().getId());var before=facts(f,id);
        if(point.equals("FILE_PAY")){expect(pay(f,id),200,"OK");assertPaid(f,id);}else{var response=point.endsWith("REFUND")?cancel(f,id):pay(f,id);expect(response,point.startsWith("FILE")?503:409,point.startsWith("FILE")?"FILE_STORAGE_UNAVAILABLE":"ORDER_STATE_CONFLICT");assertThat(facts(f,id)).isEqualTo(before);}
    }
    @ParameterizedTest @ValueSource(strings={"PAY_PAY","PAY_CANCEL","PAY_SCAN","REFUND_REFUND","REFUND_SCAN","REFUND_CREATE"})
    void concurrentRealMysqlTransitionsConvergeToUniqueMoneyAndCorrectReferences(String race)throws Exception{
        var f=fixture();var id=order(f);if(race.startsWith("REFUND")){data(pay(f,id));}if(race.endsWith("SCAN")){fixed(NOW.plusSeconds(race.startsWith("REFUND")?1800:900));}
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var start=new CountDownLatch(1);var one=pool.submit(()->{await(start);return race.startsWith("REFUND")?cancel(f,id):pay(f,id);});var two=pool.submit(()->{await(start);return switch(race){case "PAY_PAY"->pay(f,id);case "PAY_CANCEL","REFUND_REFUND"->cancel(f,id);case "REFUND_CREATE"->create(f,"new-reference");default->{expiry.scan();yield null;}};});start.countDown();var a=one.get(20,TimeUnit.SECONDS);var b=two.get(20,TimeUnit.SECONDS);assertThat(a.statusCode()).as(a.body()).isIn(200,409);if(b!=null){assertThat(b.statusCode()).as(b.body()).isIn(200,201,409);}
        }
        if(race.equals("PAY_PAY")){assertPaid(f,id);assertThat(count("order_event",id)).isEqualTo(2);}else if(race.equals("PAY_SCAN")){assertThat(count("payment_record",id)).isZero();assertThat(detail(f,id).path("closeReason").asString()).isEqualTo("PAYMENT_TIMEOUT");}else if(race.equals("PAY_CANCEL")){var o=detail(f,id);assertThat(o.path("status").asString()).isEqualTo("CLOSED");long p=count("payment_record",id);assertThat(count("refund_record",id)).isEqualTo(p);assertThat(count("order_event",id)).isEqualTo(p==1?3:2);}else{assertRefunded(f,id,race.endsWith("SCAN")?"ACCEPTANCE_TIMEOUT":"USER_CANCEL",race.endsWith("CREATE")?null:race.endsWith("SCAN")?NOW.plusSeconds(1800):NOW);if(race.endsWith("CREATE")){retained(f,null);}}
    }
    @Test void paymentsAndRefundsDoNotWaitForInstanceFileGuardOrDoIo()throws Exception{
        var f=fixture();var id=order(f);var guard=storage.lock(f.file().getStorageKey());guard.lock();clearInvocations(disk);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            expect(pool.submit(()->pay(f,id)).get(5,TimeUnit.SECONDS),200,"OK");expect(pool.submit(()->cancel(f,id)).get(5,TimeUnit.SECONDS),200,"OK");verify(disk,never()).open(anyString(),anyString(),anyLong());verify(disk,never()).lock(anyString());
        }finally{guard.unlock();}
    }
    @ParameterizedTest @ValueSource(strings={"SHOP","FILE","ORDER"})
    void acquiringBusinessLocksAfterDeadlineCannotCharge(String target)throws Exception{
        var f=fixture();var id=order(f);var atLock=new CountDownLatch(1);var release=new CountDownLatch(1);var tx=new TransactionTemplate(manager);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var holding=pool.submit(()->tx.execute(s->{switch(target){case "SHOP"->jdbc.queryForList("SELECT id FROM shop WHERE id=? FOR UPDATE",f.merchant().shop());case "FILE"->jdbc.queryForList("SELECT id FROM file_asset WHERE id=? FOR UPDATE",f.file().getId());default->jdbc.queryForList("SELECT id FROM service_order WHERE id=? FOR UPDATE",id);}atLock.countDown();await(release);return null;}));await(atLock);
            var entering=new CountDownLatch(1);
            if(target.equals("SHOP")){var real=sqlSession.getMapper(com.campuslife.order.mapper.OrderMapper.class);doAnswer(i->{entering.countDown();return real.shareShop(i.getArgument(0));}).when(orderRows).shareShop(eq(f.merchant().shop()));}
            else if(target.equals("FILE")){var real=sqlSession.getMapper(FileAssetMapper.class);doAnswer(i->{entering.countDown();return real.lock(i.getArgument(0));}).when(files).lock(eq(f.file().getId()));}
            else{var real=sqlSession.getMapper(com.campuslife.order.mapper.OrderMapper.class);doAnswer(i->{entering.countDown();return real.lock(i.getArgument(0));}).when(orderRows).lock(eq(Long.parseLong(id)));}
            var paying=pool.submit(()->pay(f,id));await(entering);fixed(NOW.plusSeconds(900));release.countDown();holding.get(10,TimeUnit.SECONDS);expect(paying.get(15,TimeUnit.SECONDS),409,"PAYMENT_DEADLINE_PASSED");assertThat(count("payment_record",id)).isZero();
        }finally{release.countDown();}
    }
    @Test void paidDecisionInstantDoesNotMoveDuringWrites()throws Exception{
        var f=fixture();var id=order(f);var real=sqlSession.getMapper(com.campuslife.order.mapper.MoneyMapper.class);doAnswer(i->{int n=real.pay(i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(3));fixed(NOW.plusSeconds(901));return n;}).when(money).pay(anyLong(),anyString(),anyLong(),any());var p=data(pay(f,id));assertThat(p.path("paidAt").asString()).isEqualTo(NOW.toString());assertThat(p.path("acceptanceDeadline").asString()).isEqualTo(NOW.plusSeconds(1800).toString());
    }
    private String cloneOrder(Fixture f,String source){
        jdbc.update("INSERT INTO service_order(order_no,student_id,shop_id,order_type,status,payment_status,original_amount,discount_amount,payable_amount,currency,shop_name_snapshot,created_at,payment_deadline,acceptance_deadline,paid_at,version) SELECT ?,student_id,shop_id,order_type,status,payment_status,original_amount,discount_amount,payable_amount,currency,shop_name_snapshot,created_at,payment_deadline,acceptance_deadline,paid_at,version FROM service_order WHERE id=?",UUID.randomUUID().toString().replace("-",""),source);
        var id=jdbc.queryForObject("SELECT MAX(id) FROM service_order WHERE student_id=?",Long.class,f.student().id()).toString();
        jdbc.update("INSERT INTO print_detail(order_id,service_id,file_id,price_rule_id,service_name_snapshot,file_name_snapshot,page_count,copies,paper_size,color_mode,side_mode,sheet_count,unit_price_amount,source_price_version,progress) SELECT ?,service_id,file_id,price_rule_id,service_name_snapshot,file_name_snapshot,page_count,copies,paper_size,color_mode,side_mode,sheet_count,unit_price_amount,source_price_version,progress FROM print_detail WHERE order_id=?",id,source);
        jdbc.update("INSERT INTO order_event(order_id,event_seq,action,from_status,to_status,actor_type,actor_id,reason,occurred_at) SELECT ?,event_seq,action,from_status,to_status,actor_type,actor_id,reason,occurred_at FROM order_event WHERE order_id=?",id,source);
        jdbc.update("INSERT INTO payment_record(order_id,payment_no,amount,channel,status,paid_at) SELECT ?,?,amount,channel,status,paid_at FROM payment_record WHERE order_id=?",id,UUID.randomUUID().toString().replace("-",""),source);return id;
    }
    @Test void separatePaidAndUnpaidHundredItemQuotasEventuallyCloseAllReferences()throws Exception{
        var f=fixture();var paid=order(f);data(pay(f,paid));var unpaid=data(create(f,"batch-unpaid")).path("id").asString();for(int n=0;n<100;n++){cloneOrder(f,paid);cloneOrder(f,unpaid);}fixed(NOW.plusSeconds(1800));
        assertThat(expiry.scan()).isEqualTo(200);retained(f,null);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refund_record r JOIN service_order o ON o.id=r.order_id WHERE o.student_id=?",Long.class,f.student().id())).isEqualTo(100);assertThat(expiry.scan()).isEqualTo(2);retained(f,NOW.plusSeconds(1800).plus(Duration.ofDays(7)));assertThat(expiry.scan()).isZero();
    }
    @Test void twoShopSystemRefundsSerializeOnSharedFileWithoutStudentAccountLock()throws Exception{
        var f=fixture();var first=order(f);data(pay(f,first));var other=merchant("PRINT");f.body().put("serviceId",Long.toString(other.service()));var second=data(create(f,"second-shop")).path("id").asString();data(pay(f,second));fixed(NOW.plusSeconds(1800));
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var start=new CountDownLatch(1);var one=pool.submit(()->{await(start);return printTransactions.expire(Long.parseLong(first));});var two=pool.submit(()->{await(start);return printTransactions.expire(Long.parseLong(second));});start.countDown();assertThat(one.get(10,TimeUnit.SECONDS)).isTrue();assertThat(two.get(10,TimeUnit.SECONDS)).isTrue();
        }assertRefunded(f,first,"ACCEPTANCE_TIMEOUT",NOW.plusSeconds(1800));assertRefunded(f,second,"ACCEPTANCE_TIMEOUT",NOW.plusSeconds(1800));
    }
    @Test void newJarProcessRecoversPaidTimeoutAndLeavesOneRefund()throws Exception{
        var f=fixture();var id=order(f);data(pay(f,id));int port;try(var socket=new ServerSocket(0,0,InetAddress.getLoopbackAddress())){port=socket.getLocalPort();}
        var output=Path.of(".cache/print-payment-recovery-"+UUID.randomUUID()+".log").toAbsolutePath();
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"-jar",Path.of("target/campuslife-0.0.1-SNAPSHOT.jar").toAbsolutePath().toString(),"--server.address=127.0.0.1","--server.port="+port,"--campuslife.files.root="+storage.root(),"--campuslife.files.cleanup-enabled=false","--campuslife.booking.expiry-enabled=false","--campuslife.print.expiry-enabled=true","--campuslife.mock-payment.enabled=true").redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try{
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);boolean closed=false;while(System.nanoTime()<deadline&&process.isAlive()){if("CLOSED".equals(jdbc.queryForObject("SELECT status FROM service_order WHERE id=?",String.class,id))){closed=true;break;}Thread.sleep(100);}assertThat(closed).as("startup recovery diagnostics %s",output).isTrue();
            assertRefunded(f,id,"ACCEPTANCE_TIMEOUT",null);var actual=jdbc.queryForObject("SELECT closed_at FROM service_order WHERE id=?",LocalDateTime.class,id);assertThat(files.selectById(f.file().getId()).getExpiresAt()).isEqualTo(actual.plusDays(7));assertThat(jdbc.queryForObject("SELECT acceptance_deadline FROM service_order WHERE id=?",LocalDateTime.class,id)).isEqualTo(FileRules.utc(NOW.plusSeconds(1800)));assertThat(expiry.scan()).isZero();
        }finally{process.destroy();if(!process.waitFor(10,TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor(10,TimeUnit.SECONDS);}}
    }
    @ParameterizedTest @ValueSource(strings={"CANCEL","CREATE_REPLAY"})
    void lockedResponseBypassesCachedLocatorAfterScannerCommits(String action)throws Exception{
        var f=fixture();var id=order(f);data(pay(f,id));fixed(NOW.plusSeconds(1800));var read=new CountDownLatch(1);var release=new CountDownLatch(1);var once=new java.util.concurrent.atomic.AtomicBoolean(true);
        if(action.equals("CANCEL")){
            var real=sqlSession.getMapper(com.campuslife.order.mapper.OrderMapper.class);
            doAnswer(i->{var result=real.selectById((java.io.Serializable)i.getArgument(0));if(Objects.equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),java.sql.Connection.TRANSACTION_READ_COMMITTED)&&once.compareAndSet(true,false)){read.countDown();await(release);}return result;}).when(orderRows).selectById((java.io.Serializable)eq(Long.parseLong(id)));
        }else{
            var real=sqlSession.getMapper(com.campuslife.order.mapper.PrintOrderMapper.class);
            doAnswer(i->{var result=real.selectById((java.io.Serializable)i.getArgument(0));if(once.compareAndSet(true,false)){read.countDown();await(release);}return result;}).when(printRows).selectById((java.io.Serializable)eq(Long.parseLong(id)));
        }
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var request=pool.submit(()->action.equals("CANCEL")?cancel(f,id):create(f,"pay-test"));await(read);assertThat(printTransactions.expire(Long.parseLong(id))).isTrue();release.countDown();var response=request.get(10,TimeUnit.SECONDS);
            if(action.equals("CANCEL")){expect(response,409,"ACCEPTANCE_DEADLINE_PASSED");}else{expect(response,200,"OK");assertThat(data(response).path("status").asString()).isEqualTo("CLOSED");assertThat(data(response).path("printing").path("progress").asString()).isEqualTo("ABORTED");assertThat(data(response).path("refund").isNull()).isFalse();}
            assertRefunded(f,id,"ACCEPTANCE_TIMEOUT",NOW.plusSeconds(1800));
        }finally{release.countDown();}
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
