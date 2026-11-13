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
class MerchantPrintDownloadMysqlIT {
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
    @Autowired FileDownloadService downloads;
    @MockitoSpyBean com.campuslife.file.mapper.FileReferenceMapper references;
    @BeforeAll void prepare()throws Exception{hash=passwords.encode("Quote_Test123!");pdf=PdfSamples.pdf(5);}
    @MockitoSpyBean com.campuslife.order.mapper.MoneyMapper money; @MockitoSpyBean com.campuslife.order.service.MockPaymentPolicy mockPayment;
    @Autowired com.campuslife.order.service.PrintOrderTransactions printTransactions;
    @BeforeEach void freeze(){storage=disk;fixed(NOW);doReturn(true).when(mockPayment).enabled();}
    @AfterEach void cleanupOwned()throws Exception {
        reset(references,lists,files,prices,services,shops,sessions,clock,orderEvents,keys,printRows,disk,orderRows,money,mockPayment);
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



    private HttpResponse<String> accept(String token,String id)throws Exception{return call("POST","/api/v1/merchant/orders/"+id+"/accept",null,token);}
    private HttpResponse<String> merchantCancel(String token,String id)throws Exception{return call("POST","/api/v1/merchant/orders/"+id+"/cancel","{\"reason\":\"download test\"}",token);}
    private String accepted(Fixture f)throws Exception{var id=order(f);data(pay(f,id));data(accept(login(f.merchant().user()),id));return id;}
    private HttpResponse<byte[]> download(String file,String token)throws Exception{return http.send(request("/api/v1/files/"+file+"/content",token).GET().build(),HttpResponse.BodyHandlers.ofByteArray());}
    private HttpResponse<byte[]> download(Fixture f,String token)throws Exception{return download(f.file().getId().toString(),token);}
    private void denied(HttpResponse<byte[]> response,int status,String code){assertThat(response.statusCode()).as(new String(response.body(),StandardCharsets.UTF_8)).isEqualTo(status);assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("application/json");assertThat(json.readTree(response.body()).path("code").asString()).isEqualTo(code);assertThat(response.headers().firstValue("Content-Disposition")).isEmpty();}
    private void original(HttpResponse<byte[]> response,byte[] bytes){assertThat(response.statusCode()).as(new String(response.body(),StandardCharsets.UTF_8)).isEqualTo(200);assertThat(response.body()).isEqualTo(bytes);assertThat(response.headers().firstValue("Content-Type").orElseThrow()).isEqualTo("application/pdf");assertThat(response.headers().firstValue("Content-Length").orElseThrow()).isEqualTo(Integer.toString(bytes.length));assertThat(response.headers().firstValue("Content-Disposition").orElseThrow()).startsWith("attachment; filename=\"campuslife-file.pdf\"; filename*=UTF-8''");assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");assertThat(response.headers().firstValue("X-Content-Type-Options").orElseThrow()).isEqualTo("nosniff");}
    @ParameterizedTest @ValueSource(strings={"NONE","RANGE","ETAG","MODIFIED"})
    void merchantGetsOriginalBinaryAttachmentWithoutChangingAnyBusinessFacts(String condition)throws Exception{
        var f=fixture();var id=accepted(f);var token=login(f.merchant().user());var before=facts(f,id);var request=request("/api/v1/files/"+f.file().getId()+"/content",token);
        switch(condition){case "RANGE"->request.header("Range","bytes=0-9");case "ETAG"->request.header("If-None-Match","*");case "MODIFIED"->request.header("If-Modified-Since","Thu, 01 Jan 2099 00:00:00 GMT");}
        original(http.send(request.GET().build(),HttpResponse.BodyHandlers.ofByteArray()),pdf);original(download(f,token),pdf);assertThat(facts(f,id)).isEqualTo(before);expect(call("GET","/api/v1/files/"+f.file().getId(),null,token),403,"ROLE_FORBIDDEN");expect(upload(token,"test.pdf","application/pdf",pdf),403,"ROLE_FORBIDDEN");
    }
    @ParameterizedTest @ValueSource(strings={"ANONYMOUS","INVALID","ADMIN","OTHER_STUDENT","DISABLED","REVOKED","EXPIRED","OWNER_DISABLED","OWNER_PENDING","OTHER_SHOP","NO_SHOP"})
    void currentIdentityAndOwnShopGrantAreRequiredBeforeStorageAccess(String kind)throws Exception{
        var f=fixture();var id=accepted(f);String token=login(f.merchant().user());int status=403;String code="ROLE_FORBIDDEN";
        switch(kind){case "ANONYMOUS"->{token=null;status=401;code="AUTH_REQUIRED";}case "INVALID"->{token="invalid";status=401;code="TOKEN_INVALID";}case "ADMIN"->token=login(user("ADMIN"));case "OTHER_STUDENT"->{token=login(user("STUDENT"));status=404;code="RESOURCE_NOT_FOUND";}case "DISABLED"->{invalidate(f.merchant().user(),kind);code="ACCOUNT_DISABLED";}case "REVOKED"->{invalidate(f.merchant().user(),kind);status=401;code="SESSION_REVOKED";}case "EXPIRED"->{invalidate(f.merchant().user(),kind);status=401;code="SESSION_EXPIRED";}case "OWNER_DISABLED","OWNER_PENDING"->jdbc.update("UPDATE shop_member SET status=? WHERE shop_id=?",kind.equals("OWNER_PENDING")?"PENDING":"DISABLED",f.merchant().shop());case "OTHER_SHOP"->{token=login(merchant("PRINT").user());status=404;code="RESOURCE_NOT_FOUND";}case "NO_SHOP"->{token=login(user("MERCHANT"));status=404;code="RESOURCE_NOT_FOUND";}}
        var before=facts(f,id);clearInvocations(disk);denied(download(f,token),status,code);verify(disk,never()).lock(anyString());verify(disk,never()).open(anyString(),anyString(),anyLong());assertThat(facts(f,id)).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"NONE","UNPAID","AWAITING","USER_CANCEL","REJECTED","MERCHANT_CANCEL","TIMEOUT","PRINTING_NO_STARTED_AT","READY_MISSING_AT","COMPLETED","NO_ACCEPTED_AT","STARTED_AT","READY_AT","PICKED_AT","NOT_PAID","OWNER_MISMATCH"})
    void onlyTheApprovedAcceptedUnstartedCombinationGrantsContent(String state)throws Exception{
        var f=fixture();var token=login(f.merchant().user());String id=state.equals("NONE")?null:order(f);
        if(id!=null&&!state.equals("UNPAID")){data(pay(f,id));}
        if(state.equals("USER_CANCEL")){data(cancel(f,id));}else if(state.equals("REJECTED")){data(call("POST","/api/v1/merchant/orders/"+id+"/reject","{\"reason\":\"test\"}",token));}else if(state.equals("TIMEOUT")){fixed(NOW.plusSeconds(1800));assertThat(printTransactions.expire(Long.parseLong(id))).isTrue();}else if(!Set.of("NONE","UNPAID","AWAITING").contains(state)){
            data(accept(token,id));switch(state){case "MERCHANT_CANCEL"->data(merchantCancel(token,id));case "PRINTING_NO_STARTED_AT"->{jdbc.update("UPDATE service_order SET status='IN_SERVICE' WHERE id=?",id);jdbc.update("UPDATE print_detail SET progress='PRINTING' WHERE order_id=?",id);}case "READY_MISSING_AT"->{jdbc.update("UPDATE service_order SET status='IN_SERVICE',started_at=? WHERE id=?",FileRules.utc(NOW),id);jdbc.update("UPDATE print_detail SET progress='READY_FOR_PICKUP' WHERE order_id=?",id);}case "COMPLETED"->{jdbc.update("UPDATE service_order SET status='COMPLETED',started_at=?,completed_at=? WHERE id=?",FileRules.utc(NOW),FileRules.utc(NOW),id);jdbc.update("UPDATE print_detail SET progress='PICKED_UP' WHERE order_id=?",id);}case "NO_ACCEPTED_AT"->jdbc.update("UPDATE service_order SET accepted_at=NULL WHERE id=?",id);case "STARTED_AT"->jdbc.update("UPDATE service_order SET started_at=? WHERE id=?",FileRules.utc(NOW),id);case "READY_AT"->jdbc.update("UPDATE print_detail SET ready_at=? WHERE order_id=?",FileRules.utc(NOW),id);case "PICKED_AT"->jdbc.update("UPDATE print_detail SET picked_up_at=? WHERE order_id=?",FileRules.utc(NOW),id);case "NOT_PAID"->jdbc.update("UPDATE service_order SET payment_status='NOT_PAID' WHERE id=?",id);case "OWNER_MISMATCH"->jdbc.update("UPDATE file_asset SET owner_id=? WHERE id=?",f.merchant().user().id(),f.file().getId());}
        }
        var before=fileFact(f);clearInvocations(disk);denied(download(f,token),404,"RESOURCE_NOT_FOUND");verify(disk,never()).lock(anyString());verify(disk,never()).open(anyString(),anyString(),anyLong());assertThat(fileFact(f)).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"UPLOADING","DELETING","DELETED","FUTURE_EXPIRY","PAST_EXPIRY"})
    void authorizedButBrokenProtectionMetadataIs503AndOtherShopsStillSee404(String state)throws Exception{
        var f=fixture();var id=accepted(f);var token=login(f.merchant().user());var other=login(merchant("PRINT").user());
        if(state.endsWith("EXPIRY")){expiryAt(f,state.startsWith("FUTURE")?NOW.plusSeconds(3600):NOW);}else if(state.equals("UPLOADING")){jdbc.update("UPDATE file_asset SET status='UPLOADING',expires_at=? WHERE id=?",FileRules.utc(NOW.plusSeconds(1800)),f.file().getId());}else if(state.equals("DELETED")){jdbc.update("UPDATE file_asset SET status='DELETED',deleted_at=? WHERE id=?",FileRules.utc(NOW),f.file().getId());}else{jdbc.update("UPDATE file_asset SET status=? WHERE id=?",state,f.file().getId());}
        var before=facts(f,id);clearInvocations(disk);denied(download(f,token),503,"FILE_STORAGE_UNAVAILABLE");denied(download(f,other),404,"RESOURCE_NOT_FOUND");verify(disk,never()).open(anyString(),anyString(),anyLong());assertThat(facts(f,id)).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"SUSPENDED","OFF","HOURS","SERVICE","PRICE","STUDENT_DISABLED","MOCK_OFF","AFTER_UPLOAD_DAY"})
    void legitimateOldTasksRemainReadableAcrossSalesChangesOrOldDeadlines(String change)throws Exception{
        var f=fixture();var id=accepted(f);String token=login(f.merchant().user());switch(change){case "SUSPENDED"->jdbc.update("UPDATE shop SET approval_status='SUSPENDED' WHERE id=?",f.merchant().shop());case "OFF"->jdbc.update("UPDATE shop SET business_enabled=0 WHERE id=?",f.merchant().shop());case "HOURS"->jdbc.update("DELETE FROM shop_business_hour WHERE shop_id=?",f.merchant().shop());case "SERVICE"->jdbc.update("UPDATE service_item SET enabled=0 WHERE id=?",f.merchant().service());case "PRICE"->jdbc.update("UPDATE print_price_rule SET enabled=0 WHERE service_id=?",f.merchant().service());case "STUDENT_DISABLED"->jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?",f.student().id());case "MOCK_OFF"->doReturn(false).when(mockPayment).enabled();case "AFTER_UPLOAD_DAY"->{fixed(NOW.plusSeconds(86401));token=login(f.merchant().user());}}
        var before=facts(f,id);original(download(f,token),pdf);assertThat(facts(f,id)).isEqualTo(before);
    }
    @Test void sameFileNeedsOneEligibleOwnTaskAndRetentionNeverGrantsMerchantAccess()throws Exception{
        var f=fixture();var first=accepted(f);var token=login(f.merchant().user());var second=data(create(f,"own-second")).path("id").asString();data(pay(f,second));data(accept(token,second));var other=merchant("PRINT");var otherToken=login(other.user());f.body().put("serviceId",Long.toString(other.service()));var third=data(create(f,"cross-shop")).path("id").asString();data(pay(f,third));data(accept(otherToken,third));
        data(merchantCancel(token,first));original(download(f,token),pdf);data(merchantCancel(token,second));denied(download(f,token),404,"RESOURCE_NOT_FOUND");original(download(f,otherToken),pdf);retained(f,null);data(merchantCancel(otherToken,third));retained(f,NOW.plus(Duration.ofDays(7)));denied(download(f,otherToken),404,"RESOURCE_NOT_FOUND");original(download(f,f.token()),pdf);
        fixed(NOW.plus(Duration.ofDays(8)));denied(download(f,login(f.merchant().user())),404,"RESOURCE_NOT_FOUND");denied(download(f,login(f.student())),410,"FILE_EXPIRED");cleanup.runOnce();assertThat(files.selectById(f.file().getId()).getStatus()).isEqualTo("DELETED");
    }
    @Test void owningTheFileAfterRoleChangeDoesNotBypassMerchantTaskAuthorization()throws Exception{var f=fixture();jdbc.update("UPDATE account SET role='MERCHANT' WHERE id=?",f.student().id());jdbc.update("UPDATE shop_member SET account_id=? WHERE shop_id=?",f.student().id(),f.merchant().shop());denied(download(f,login(f.student())),404,"RESOURCE_NOT_FOUND");}
    @ParameterizedTest @ValueSource(strings={"DISABLED","REVOKED","EXPIRED","ROLE_CHANGED","SESSION_REMOVED","OWNER_DISABLED","OWNER_PENDING","CANCEL","STARTED","REASSIGNED","FILE_EXPIRY","ROOT"})
    void waitingForGuardRequiresNewIdentityMembershipAndTaskSnapshot(String change)throws Exception{
        var f=fixture();var id=accepted(f);var token=login(f.merchant().user());var guard=storage.lock(f.file().getStorageKey());var waiting=new CountDownLatch(1);doAnswer(i->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();waiting.countDown();return guard;}).when(disk).lock(eq(f.file().getStorageKey()));guard.lock();clearInvocations(disk);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var future=pool.submit(()->download(f,token));await(waiting);
            try{switch(change){case "DISABLED","REVOKED","EXPIRED"->invalidate(f.merchant().user(),change);case "ROLE_CHANGED"->jdbc.update("UPDATE account SET role='STUDENT' WHERE id=?",f.merchant().user().id());case "SESSION_REMOVED"->jdbc.update("DELETE FROM auth_session WHERE account_id=?",f.merchant().user().id());case "OWNER_DISABLED","OWNER_PENDING"->jdbc.update("UPDATE shop_member SET status=? WHERE shop_id=?",change.equals("OWNER_PENDING")?"PENDING":"DISABLED",f.merchant().shop());case "CANCEL"->data(merchantCancel(token,id));case "STARTED"->{jdbc.update("UPDATE service_order SET status='IN_SERVICE',started_at=? WHERE id=?",FileRules.utc(NOW),id);jdbc.update("UPDATE print_detail SET progress='PRINTING' WHERE order_id=?",id);}case "REASSIGNED"->jdbc.update("UPDATE shop_member SET account_id=? WHERE shop_id=?",user("MERCHANT").id(),f.merchant().shop());case "FILE_EXPIRY"->expiryAt(f,NOW);case "ROOT"->jdbc.update("UPDATE file_asset SET storage_id=? WHERE id=?",UUID.randomUUID().toString(),f.file().getId());}}finally{guard.unlock();}
            int status=switch(change){case "REVOKED","EXPIRED","SESSION_REMOVED"->401;case "CANCEL","STARTED","REASSIGNED"->404;case "FILE_EXPIRY","ROOT"->503;default->403;};String code=switch(change){case "DISABLED"->"ACCOUNT_DISABLED";case "REVOKED"->"SESSION_REVOKED";case "EXPIRED"->"SESSION_EXPIRED";case "SESSION_REMOVED"->"TOKEN_INVALID";case "CANCEL","STARTED","REASSIGNED"->"RESOURCE_NOT_FOUND";case "FILE_EXPIRY","ROOT"->"FILE_STORAGE_UNAVAILABLE";default->"ROLE_FORBIDDEN";};if(change.equals("STARTED")){original(future.get(15,TimeUnit.SECONDS),pdf);}else{denied(future.get(15,TimeUnit.SECONDS),status,code);verify(disk,never()).open(anyString(),anyString(),anyLong());}
        }finally{if(guard.isHeldByCurrentThread()){guard.unlock();}}
    }
    @ParameterizedTest @ValueSource(strings={"CANCEL","OWNER_DISABLED"})
    void postGuardQualificationAndGrantUseTheSameRealMysqlSnapshot(String change)throws Exception{
        var f=fixture();var id=accepted(f);var token=login(f.merchant().user());var gate=new CountDownLatch(1);var release=new CountDownLatch(1);var reads=new java.util.concurrent.atomic.AtomicInteger();var real=sqlSession.getMapper(com.campuslife.file.mapper.FileReferenceMapper.class);
        doAnswer(i->{var result=real.downloadOwner(i.getArgument(0));if(reads.incrementAndGet()==2){gate.countDown();await(release);}return result;}).when(references).downloadOwner(eq(f.merchant().user().id()));
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var request=pool.submit(()->download(f,token));await(gate);if(change.equals("CANCEL")){data(merchantCancel(token,id));}else{jdbc.update("UPDATE shop_member SET status='DISABLED' WHERE shop_id=?",f.merchant().shop());}release.countDown();original(request.get(15,TimeUnit.SECONDS),pdf);denied(download(f,token),change.equals("CANCEL")?404:403,change.equals("CANCEL")?"RESOURCE_NOT_FOUND":"ROLE_FORBIDDEN");
        }finally{release.countDown();}
    }
    @Test void refundCommitsDuringAuthorizedTransferAndCleanupWaitsForStreamGuard()throws Exception{
        var f=fixture();byte[] large=PdfSamples.sized(32768);var file=row(data(upload(f.token(),"stream.pdf","application/pdf",large)));f.body().put("fileId",file.getId().toString());f.body().put("expectedPayableAmount",40L);var id=accepted(f);var token=login(f.merchant().user());var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(i->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return new FilterInputStream((InputStream)i.callRealMethod()){int reads;@Override public int read(byte[] b,int off,int len)throws IOException{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();if(reads++==1){entered.countDown();await(release);}return super.read(b,off,len);}};}).when(disk).open(eq(file.getStorageId()),eq(file.getStorageKey()),anyLong());
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var request=pool.submit(()->download(file.getId().toString(),token));await(entered);var closed=data(pool.submit(()->merchantCancel(token,id)).get(5,TimeUnit.SECONDS));assertThat(closed.path("paymentStatus").asString()).isEqualTo("REFUNDED");fixed(NOW.plus(Duration.ofDays(8)));var current=login(f.merchant().user());denied(download(file.getId().toString(),current),404,"RESOURCE_NOT_FOUND");cleanup.runOnce();assertThat(files.selectById(file.getId()).getStatus()).isEqualTo("AVAILABLE");assertThat(object(file)).exists();release.countDown();original(request.get(15,TimeUnit.SECONDS),large);cleanup.runOnce();assertThat(files.selectById(file.getId()).getStatus()).isEqualTo("DELETED");assertThat(object(file)).doesNotExist();denied(download(file.getId().toString(),login(f.student())),410,"FILE_EXPIRED");
        }finally{release.countDown();}
    }
    @ParameterizedTest @ValueSource(strings={"REVOKED","OWNER_DISABLED"})
    void authorizedTransferFinishesAfterIdentityOrMembershipIsRevoked(String change)throws Exception{
        var f=fixture();var id=accepted(f);var token=login(f.merchant().user());var entered=new CountDownLatch(1);var release=new CountDownLatch(1);doAnswer(i->{entered.countDown();await(release);return i.callRealMethod();}).when(disk).open(anyString(),anyString(),anyLong());
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var request=pool.submit(()->download(f,token));await(entered);pool.submit(()->{if(change.equals("REVOKED")){invalidate(f.merchant().user(),change);}else{jdbc.update("UPDATE shop_member SET status='DISABLED' WHERE shop_id=?",f.merchant().shop());}}).get(5,TimeUnit.SECONDS);release.countDown();original(request.get(15,TimeUnit.SECONDS),pdf);denied(download(f,token),change.equals("REVOKED")?401:403,change.equals("REVOKED")?"SESSION_REVOKED":"ROLE_FORBIDDEN");assertThat(count("refund_record",id)).isZero();
        }finally{release.countDown();}
    }
    @Test void downloadDoesNotTakeBusinessRowLocksAndOpensOutsideTransactions()throws Exception{
        var f=fixture();var id=accepted(f);var token=login(f.merchant().user());var held=new CountDownLatch(1);var release=new CountDownLatch(1);var tx=new TransactionTemplate(manager);doAnswer(i->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return i.callRealMethod();}).when(disk).open(anyString(),anyString(),anyLong());
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var holder=pool.submit(()->tx.execute(s->{jdbc.queryForList("SELECT id FROM account WHERE id IN (?,?) ORDER BY id FOR UPDATE",f.merchant().user().id(),f.student().id());jdbc.queryForList("SELECT account_id FROM auth_session WHERE account_id=? FOR UPDATE",f.merchant().user().id());jdbc.queryForList("SELECT id FROM shop WHERE id=? FOR UPDATE",f.merchant().shop());jdbc.queryForList("SELECT id FROM file_asset WHERE id=? FOR UPDATE",f.file().getId());jdbc.queryForList("SELECT id FROM service_order WHERE id=? FOR UPDATE",id);jdbc.queryForList("SELECT order_id FROM print_detail WHERE order_id=? FOR UPDATE",id);held.countDown();await(release);return null;}));await(held);original(pool.submit(()->download(f,token)).get(5,TimeUnit.SECONDS),pdf);release.countDown();holder.get(10,TimeUnit.SECONDS);
        }finally{release.countDown();}
    }
    @ParameterizedTest @ValueSource(strings={"MISSING","LENGTH","ROOT","DIRECTORY","FIRST_READ"})
    void storageFailuresKeepJsonAndDoNotChangeAuthorizedOrder(String fault)throws Exception{
        var f=fixture();var id=accepted(f);var token=login(f.merchant().user());var path=object(f.file());switch(fault){case "MISSING"->storage.delete(storage.storageId(),f.file().getStorageKey());case "LENGTH"->Files.write(path,new byte[]{1});case "ROOT"->jdbc.update("UPDATE file_asset SET storage_id=? WHERE id=?",UUID.randomUUID().toString(),f.file().getId());case "DIRECTORY"->{Files.delete(path);Files.createDirectory(path);}case "FIRST_READ"->doAnswer(i->new FilterInputStream((InputStream)i.callRealMethod()){@Override public int read(byte[] b,int off,int len)throws IOException{throw new IOException("private storage failure");}}).when(disk).open(anyString(),anyString(),anyLong());}var before=facts(f,id);
        try{var response=download(f,token);denied(response,503,"FILE_STORAGE_UNAVAILABLE");assertThat(new String(response.body(),StandardCharsets.UTF_8)).doesNotContain(storage.root().toString(),f.file().getStorageKey(),"private storage failure");assertThat(facts(f,id)).isEqualTo(before);}finally{if(fault.equals("DIRECTORY")){Files.delete(path);}}
    }
    @ParameterizedTest @ValueSource(ints={1,2})
    void transientReadErrorsBeforeOrAfterGuardReleaseTheGuardAndAreRetryable(int attempt)throws Exception{
        var f=fixture();var id=accepted(f);var token=login(f.merchant().user());var count=new java.util.concurrent.atomic.AtomicInteger();var real=sqlSession.getMapper(com.campuslife.file.mapper.FileReferenceMapper.class);doAnswer(i->{if(count.incrementAndGet()==attempt){throw new org.springframework.dao.CannotAcquireLockException("injected");}return real.merchantDownloadAllowed(i.getArgument(0),i.getArgument(1));}).when(references).merchantDownloadAllowed(anyLong(),anyLong());var before=facts(f,id);denied(download(f,token),503,"SERVICE_TEMPORARILY_UNAVAILABLE");original(download(f,token),pdf);assertThat(facts(f,id)).isEqualTo(before);
    }
    @Test void committedMerchantStreamFailureAbortsWithoutJsonAndReleasesResources()throws Exception{
        var f=fixture();byte[] large=PdfSamples.sized(32768);var file=row(data(upload(f.token(),"broken.pdf","application/pdf",large)));f.body().put("fileId",file.getId().toString());f.body().put("expectedPayableAmount",40L);var id=accepted(f);var token=login(f.merchant().user());var closed=new java.util.concurrent.atomic.AtomicBoolean();doAnswer(i->new FilterInputStream((InputStream)i.callRealMethod()){int reads;@Override public int read(byte[] b,int off,int len)throws IOException{if(reads++==2){throw new IOException("injected");}return super.read(b,off,len);}@Override public void close()throws IOException{closed.set(true);super.close();}}).when(disk).open(anyString(),anyString(),anyLong());
        var response=http.send(request("/api/v1/files/"+file.getId()+"/content",token).GET().build(),HttpResponse.BodyHandlers.ofInputStream());var received=new ByteArrayOutputStream();try(var input=response.body()){byte[] buffer=new byte[4096];int n;try{while((n=input.read(buffer))!=-1){received.write(buffer,0,n);}}catch(IOException expected){}}assertThat(response.statusCode()).isEqualTo(200);assertThat(received.size()).isBetween(1,large.length-1);assertThat(received.toByteArray()).isEqualTo(Arrays.copyOf(large,received.size()));assertThat(closed).isTrue();reset(disk);original(download(file.getId().toString(),token),large);assertThat(count("refund_record",id)).isZero();
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
