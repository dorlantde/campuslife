package com.campuslife;

import com.campuslife.admin.bootstrap.AdminAccountInitializer;
import com.campuslife.admin.mapper.AdminAccountMapper;
import com.campuslife.admin.service.AdminAccountService;
import com.campuslife.auth.entity.AccountEntity;
import com.campuslife.auth.mapper.AccountMapper;
import com.campuslife.auth.mapper.AuthSessionMapper;
import com.campuslife.auth.security.*;
import com.campuslife.auth.service.SessionService;
import com.campuslife.common.exception.BusinessException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import tools.jackson.databind.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="campuslife.files.cleanup-enabled=false")
@ActiveProfiles("mysql")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminAccountListMysqlIT {
    private static final Instant NOW=Instant.parse("2026-10-26T01:00:00Z");
    private static final String PASSWORD="List_Test123!";
    private final HttpClient http=HttpClient.newHttpClient();
    private final List<Long> owned=new ArrayList<>();private String hash;
    @Autowired JdbcTemplate jdbc;@Autowired Environment env;@Autowired ObjectMapper json;
    @Autowired PasswordEncoder passwords;@Autowired AdminAccountService service;
    @Autowired SessionService authentication;@Autowired TokenService tokens;
    @Autowired PlatformTransactionManager manager;@Autowired org.mybatis.spring.SqlSessionTemplate sql;
    @MockitoSpyBean AccountMapper accounts;@MockitoSpyBean AuthSessionMapper sessions;
    @MockitoSpyBean AdminAccountMapper lists;@MockitoSpyBean Clock clock;
    @BeforeAll void prepare(){hash=passwords.encode(PASSWORD);}
    @BeforeEach void freeze(){fixed(NOW);}
    @AfterEach void cleanup(){
        reset(lists,sessions,accounts,clock);
        for(long id:owned){
            for(long shop:jdbc.queryForList("SELECT shop_id FROM shop_member WHERE account_id=?",Long.class,id)){
                jdbc.update("DELETE FROM shop_review WHERE shop_id=?",shop);jdbc.update("DELETE FROM shop_member WHERE shop_id=?",shop);jdbc.update("DELETE FROM shop WHERE id=?",shop);
            }
            jdbc.update("DELETE FROM auth_session WHERE account_id=?",id);
        }
        for(long id:owned){jdbc.update("DELETE FROM account WHERE id=?",id);}owned.clear();
    }
    private record User(long id,String name){}
    private String name(){return "list_"+UUID.randomUUID().toString().replace("-","").substring(0,20);}
    private User user(String role,String status){var a=new AccountEntity();a.setUsername(name());a.setNickname("虚构账号😀");a.setPasswordHash(hash);a.setRole(role);a.setStatus(status);a.setVersion(0L);accounts.insert(a);owned.add(a.getId());return new User(a.getId(),a.getUsername());}
    private User admin(){return user("ADMIN","ACTIVE");}
    private String login(User a)throws Exception{return data(call("POST","/api/v1/auth/login",json.writeValueAsString(Map.of("username",a.name(),"password",PASSWORD)),null)).path("accessToken").asString();}
    private HttpResponse<String> list(String token,String query)throws Exception{return call("GET","/api/v1/admin/accounts"+query,null,token);}
    private JsonNode page(String token,String query)throws Exception{return data(list(token,query));}
    private JsonNode row(JsonNode page,long id){for(var v:page.path("items")){if(v.path("id").asString().equals(Long.toString(id))){return v;}}throw new AssertionError("Missing owned account "+id);}
    private void fixed(Instant now){doReturn(now).when(clock).instant();}
    private long total(){return jdbc.queryForObject("SELECT COUNT(*) FROM account",Long.class);}
    private AuthPrincipal principal(User a,String t){return authentication.authenticate(tokens.verify(t),false);}

    @Test void actualRegistrationApplicationAndControlledAdminAreVisibleWithOnlySixFields()throws Exception{
        var n=name();var registered=data(call("POST","/api/v1/auth/register",json.writeValueAsString(Map.of("username",n,"password",PASSWORD,"nickname","真实注册样本")),null));
        long student=Long.parseLong(registered.path("id").asString());owned.add(student);
        var merchantName=name();var category=jdbc.queryForObject("SELECT id FROM shop_category WHERE code='PRINT'",Long.class);
        data(call("POST","/api/v1/merchant-applications",json.writeValueAsString(Map.of("username",merchantName,"password",PASSWORD,"nickname","模拟店主","categoryId",category.toString(),"shopName","虚构列表样本店","locationText","虚构校园")),null));
        long merchant=jdbc.queryForObject("SELECT id FROM account WHERE username=?",Long.class,merchantName);owned.add(merchant);
        var adminName=name();long admin=Long.parseLong(new AdminAccountInitializer(accounts,passwords).create(adminName,"受控管理员",PASSWORD));owned.add(admin);
        var disabled=user("STUDENT","DISABLED");var other=admin();var token=login(new User(admin,adminName));var before=allFacts();clearInvocations(accounts,sessions,lists);
        var p=page(token,"");assertThat(p.path("page").asInt()).isEqualTo(1);assertThat(p.path("size").asInt()).isEqualTo(20);assertThat(p.path("total").asLong()).isEqualTo(total());
        assertThat(row(p,student).path("role").asString()).isEqualTo("STUDENT");assertThat(row(p,merchant).path("role").asString()).isEqualTo("MERCHANT");assertThat(row(p,admin).path("role").asString()).isEqualTo("ADMIN");row(p,other.id());assertThat(row(p,disabled.id()).path("status").asString()).isEqualTo("DISABLED");
        assertThat(p.propertyNames()).containsExactlyInAnyOrder("items","page","size","total","totalPages");
        for(var v:p.path("items")){assertThat(v.propertyNames()).containsExactlyInAnyOrder("id","username","nickname","role","status","version");assertThat(v.path("id").isString()).isTrue();assertThat(v.path("version").isString()).isTrue();}
        assertThat(p.toString()).doesNotContain("password","hash","jti","accessToken","expiresAt","revokedAt");assertThat(allFacts()).isEqualTo(before);
        verify(lists).count(null,null);verify(lists).page(null,null,20,0);verify(accounts,never()).lockById(anyLong());verify(sessions,never()).lockOwnSession(anyLong(),anyString());
    }
    @ParameterizedTest @CsvSource({"STUDENT,ACTIVE","STUDENT,DISABLED","MERCHANT,ACTIVE","MERCHANT,DISABLED","ADMIN,ACTIVE","ADMIN,DISABLED"})
    void roleAndStatusFiltersIntersect(String role,String status)throws Exception{
        var a=admin();var token=login(a);for(String r:List.of("STUDENT","MERCHANT","ADMIN")){for(String s:List.of("ACTIVE","DISABLED")){user(r,s);}}
        var p=page(token,"?role="+role+"&status="+status);assertThat(p.path("items").size()).isEqualTo(role.equals("ADMIN")&&status.equals("ACTIVE")?2:1);
        assertThat(p.path("total").asLong()).isEqualTo(p.path("items").size());for(var item:p.path("items")){assertThat(item.path("role").asString()).isEqualTo(role);assertThat(item.path("status").asString()).isEqualTo(status);}
        var roles=page(token,"?role="+role);for(var item:roles.path("items")){assertThat(item.path("role").asString()).isEqualTo(role);}
        var states=page(token,"?status="+status);for(var item:states.path("items")){assertThat(item.path("status").asString()).isEqualTo(status);}
    }
    @Test void emptyAndBeyondLastPagesKeepRealTotalsAndLongOffsets()throws Exception{
        var token=login(admin());var empty=page(token,"?role=MERCHANT&status=DISABLED");assertThat(empty.path("total").asLong()).isZero();assertThat(empty.path("totalPages").asLong()).isZero();assertThat(empty.path("items").size()).isZero();
        var p=page(token,"?page=2147483647&size=100");assertThat(p.path("items").size()).isZero();assertThat(p.path("total").asLong()).isEqualTo(total());verify(lists).page(null,null,100,214748364600L);
        var leading=page(token,"?page=0001&size=001");assertThat(leading.path("page").asInt()).isEqualTo(1);assertThat(leading.path("size").asInt()).isEqualTo(1);assertThat(leading.path("totalPages").asLong()).isEqualTo(total());page(token,"?size=100");
    }
    @Test void creationTimeThenIdDescendingIsStableAcrossPages()throws Exception{
        var a=admin();var token=login(a);var one=user("STUDENT","ACTIVE");var two=user("MERCHANT","ACTIVE");var three=user("STUDENT","DISABLED");
        jdbc.update("UPDATE account SET created_at=? WHERE id=?",utc(NOW.minusSeconds(10)),a.id());
        for(var u:List.of(one,two,three)){jdbc.update("UPDATE account SET created_at=? WHERE id=?",utc(NOW),u.id());}
        assertThat(page(token,"?size=2").path("items").get(0).path("id").asString()).isEqualTo(Long.toString(three.id()));
        assertThat(page(token,"?size=2").path("items").get(1).path("id").asString()).isEqualTo(Long.toString(two.id()));
        var p=page(token,"?page=2&size=2");assertThat(p.path("items").get(0).path("id").asString()).isEqualTo(Long.toString(one.id()));assertThat(p.path("items").get(1).path("id").asString()).isEqualTo(Long.toString(a.id()));assertThat(p.path("totalPages").asLong()).isEqualTo(2);
    }
    @Test void largeIdsAndVersionsAreExactStrings()throws Exception{var t=login(admin());long id=9007199254741099L;var n=name();jdbc.update("INSERT INTO account(id,username,password_hash,nickname,role,status,version) VALUES (?,?,?,'大ID测试','STUDENT','ACTIVE',?)",id,n,hash,Long.MAX_VALUE);owned.add(id);var v=row(page(t,"?role=STUDENT"),id);assertThat(v.path("id").asString()).isEqualTo("9007199254741099");assertThat(v.path("version").asString()).isEqualTo("9223372036854775807");assertThat(v.path("id").isString()).isTrue();assertThat(v.path("version").isString()).isTrue();}
    static Stream<String> invalidQuery(){return Stream.of("role=","role=student","role=UNKNOWN","role=%20ADMIN","role=ADMIN%20","status=","status=active","status=OTHER","status=%20","page=","page=0","page=-1","page=%2B1","page=1.0","page=1e1","page=%201","page=2147483648","page=999999999999999999999","size=","size=0","size=-1","size=%2B1","size=1.0","size=1e1","size=101","size=%20","size=2147483648");}
    @ParameterizedTest @MethodSource("invalidQuery")
    void invalidFiltersAndPaginationAreRejectedBeforeListSql(String query)throws Exception{var t=login(admin());clearInvocations(lists);expect(list(t,"?"+query),400,"VALIDATION_FAILED");verifyNoInteractions(lists);}
    @ParameterizedTest @ValueSource(strings={"STUDENT","MERCHANT","NONE","INVALID","DISABLED","REVOKED","EXPIRED","ROLE_CHANGED","SESSION_MISSING"})
    void transportRequiresCurrentAdminAndValidSession(String kind)throws Exception{var a=user(kind.equals("STUDENT")||kind.equals("MERCHANT")?kind:"ADMIN","ACTIVE");var t=login(a);int status=403;String code="ROLE_FORBIDDEN";switch(kind){case "NONE"->{t=null;status=401;code="AUTH_REQUIRED";}case "INVALID"->{t="invalid";status=401;code="TOKEN_INVALID";}case "DISABLED"->{jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?",a.id());code="ACCOUNT_DISABLED";}case "ROLE_CHANGED"->jdbc.update("UPDATE account SET role='STUDENT' WHERE id=?",a.id());case "REVOKED"->{jdbc.update("UPDATE auth_session SET revoked_at=? WHERE account_id=?",utc(NOW),a.id());status=401;code="SESSION_REVOKED";}case "EXPIRED"->{fixed(NOW.plusSeconds(7200));status=401;code="SESSION_EXPIRED";}case "SESSION_MISSING"->{jdbc.update("DELETE FROM auth_session WHERE account_id=?",a.id());status=401;code="TOKEN_INVALID";}}clearInvocations(lists);expect(list(t,""),status,code);verifyNoInteractions(lists);}
    @ParameterizedTest @ValueSource(strings={"DISABLED","ROLE_CHANGED","REVOKED","EXPIRED","SESSION_MISSING"})
    void transactionRechecksDatabaseEvenForPreviouslyAuthorizedPrincipal(String kind)throws Exception{var a=admin();var t=login(a);var p=principal(a,t);String code;HttpStatus status;switch(kind){case "DISABLED"->{jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?",a.id());code="ACCOUNT_DISABLED";status=HttpStatus.FORBIDDEN;}case "ROLE_CHANGED"->{jdbc.update("UPDATE account SET role='MERCHANT' WHERE id=?",a.id());code="ROLE_FORBIDDEN";status=HttpStatus.FORBIDDEN;}case "REVOKED"->{jdbc.update("UPDATE auth_session SET revoked_at=? WHERE account_id=?",utc(NOW),a.id());code="SESSION_REVOKED";status=HttpStatus.UNAUTHORIZED;}case "EXPIRED"->{fixed(NOW.plusSeconds(7200));code="SESSION_EXPIRED";status=HttpStatus.UNAUTHORIZED;}default->{jdbc.update("DELETE FROM auth_session WHERE account_id=?",a.id());code="TOKEN_INVALID";status=HttpStatus.UNAUTHORIZED;}}clearInvocations(lists);var ex=catchThrowable(()->service.list(p,null,null,null,null));assertThat(ex).isInstanceOf(BusinessException.class);assertThat(((BusinessException)ex).code()).isEqualTo(code);assertThat(((BusinessException)ex).status()).isEqualTo(status);verifyNoInteractions(lists);}
    @Test void expiryWhileFetchingPageIsRejectedBeforeResponse()throws Exception{var t=login(admin());var real=sql.getMapper(AdminAccountMapper.class);var before=allFacts();doAnswer(i->{var result=real.page(i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(3));fixed(NOW.plusSeconds(7200));return result;}).when(lists).page(any(),any(),anyInt(),anyLong());expect(list(t,""),401,"SESSION_EXPIRED");assertThat(allFacts()).isEqualTo(before);}
    @ParameterizedTest @ValueSource(strings={"COUNT_TRANSIENT","PAGE_TRANSIENT","COUNT_INTERNAL","PAGE_INTERNAL"})
    void databaseFailureNeverBecomesSuccessfulEmptyPage(String fault)throws Exception{var t=login(admin());var before=allFacts();RuntimeException ex=fault.endsWith("TRANSIENT")?new org.springframework.dao.CannotAcquireLockException("injected"):new IllegalStateException("injected");if(fault.startsWith("COUNT")){doThrow(ex).when(lists).count(any(),any());}else{doThrow(ex).when(lists).page(any(),any(),anyInt(),anyLong());}var response=list(t,"");expect(response,fault.endsWith("TRANSIENT")?503:500,fault.endsWith("TRANSIENT")?"SERVICE_TEMPORARILY_UNAVAILABLE":"INTERNAL_ERROR");assertThat(json.readTree(response.body()).path("data").isNull()).isTrue();assertThat(allFacts()).isEqualTo(before);}
    @ParameterizedTest @ValueSource(strings={"INSERT","NICKNAME","TARGET_ROLE","TARGET_STATUS","ACTOR_DISABLED","ACTOR_ROLE","REVOKED"})
    void realRrSnapshotKeepsQualificationCountAndItemsTogether(String change)throws Exception{
        var a=admin();var t=login(a);var target=user("STUDENT","ACTIVE");var before=total();var reached=new CountDownLatch(1);var release=new CountDownLatch(1);var real=sql.getMapper(AdminAccountMapper.class);
        doAnswer(i->{assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();assertThat(jdbc.queryForObject("SELECT @@transaction_isolation",String.class)).isEqualTo("REPEATABLE-READ");reached.countDown();await(release);return real.page(i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(3));}).when(lists).page(any(),any(),anyInt(),anyLong());
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var old=pool.submit(()->list(t,"?role=STUDENT&status=ACTIVE"));await(reached);
            switch(change){case "INSERT"->user("STUDENT","ACTIVE");case "NICKNAME"->jdbc.update("UPDATE account SET nickname='新昵称',version=version+1 WHERE id=?",target.id());case "TARGET_ROLE"->jdbc.update("UPDATE account SET role='MERCHANT' WHERE id=?",target.id());case "TARGET_STATUS"->jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?",target.id());case "ACTOR_DISABLED"->jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?",a.id());case "ACTOR_ROLE"->jdbc.update("UPDATE account SET role='STUDENT' WHERE id=?",a.id());case "REVOKED"->jdbc.update("UPDATE auth_session SET revoked_at=? WHERE account_id=?",utc(NOW),a.id());}
            release.countDown();var p=data(old.get(10,TimeUnit.SECONDS));assertThat(p.path("total").asLong()).isEqualTo(1);assertThat(p.path("items").size()).isEqualTo(1);var v=row(p,target.id());assertThat(v.path("nickname").asString()).isEqualTo("虚构账号😀");assertThat(v.path("role").asString()).isEqualTo("STUDENT");assertThat(v.path("status").asString()).isEqualTo("ACTIVE");assertThat(v.path("version").asString()).isEqualTo("0");
        }finally{release.countDown();}reset(lists);
        if(change.equals("ACTOR_DISABLED")){expect(list(t,""),403,"ACCOUNT_DISABLED");}else if(change.equals("ACTOR_ROLE")){expect(list(t,""),403,"ROLE_FORBIDDEN");}else if(change.equals("REVOKED")){expect(list(t,""),401,"SESSION_REVOKED");}else{var next=page(t,"?role=STUDENT&status=ACTIVE");assertThat(next.path("total").asLong()).isEqualTo(change.equals("INSERT")?2:change.equals("NICKNAME")?1:0);if(change.equals("NICKNAME")){assertThat(row(next,target.id()).path("nickname").asString()).isEqualTo("新昵称");}}
        assertThat(total()).isEqualTo(before+(change.equals("INSERT")?1:0));
    }
    @Test void sessionChangeBeforeServiceSnapshotIsNotHiddenByTransportAuthentication()throws Exception{var a=admin();var token=login(a);var calls=new java.util.concurrent.atomic.AtomicInteger();var real=sql.getMapper(AuthSessionMapper.class);var writer=new TransactionTemplate(manager);writer.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);doAnswer(i->{if(calls.incrementAndGet()==2){writer.execute(s->{jdbc.update("UPDATE auth_session SET revoked_at=? WHERE account_id=?",utc(NOW),a.id());return null;});}return real.find(i.getArgument(0),i.getArgument(1));}).when(sessions).find(eq(a.id()),anyString());expect(list(token,""),401,"SESSION_REVOKED");verifyNoInteractions(lists);}
    @Test void plainReadCompletesUnderAccountAndSessionWriteLocks()throws Exception{var a=admin();var t=login(a);var target=user("STUDENT","ACTIVE");var held=new CountDownLatch(1);var release=new CountDownLatch(1);var tx=new TransactionTemplate(manager);clearInvocations(accounts,sessions);try(var pool=Executors.newVirtualThreadPerTaskExecutor()){var holder=pool.submit(()->tx.execute(s->{jdbc.queryForList("SELECT id FROM account WHERE id=? FOR UPDATE",a.id());jdbc.queryForList("SELECT id FROM account WHERE id=? FOR UPDATE",target.id());jdbc.queryForList("SELECT id FROM auth_session WHERE account_id=? FOR UPDATE",a.id());held.countDown();await(release);return null;}));await(held);var p=data(pool.submit(()->list(t,"")).get(5,TimeUnit.SECONDS));row(p,target.id());verify(accounts,never()).lockById(anyLong());verify(sessions,never()).lockOwnSession(anyLong(),anyString());release.countDown();holder.get(10,TimeUnit.SECONDS);}finally{release.countDown();}}
    @Test void noAccountCreationOrDetailRouteIsExposed()throws Exception{var t=login(admin());assertThat(call("POST","/api/v1/admin/accounts","{}",t).statusCode()).isEqualTo(405);assertThat(call("GET","/api/v1/admin/accounts/1",null,t).statusCode()).isEqualTo(404);}

    private Map<String,Object> allFacts(){var rows=new ArrayList<Object>();var sessionRows=new ArrayList<Object>();for(long id:owned){rows.add(jdbc.queryForMap("SELECT * FROM account WHERE id=?",id));sessionRows.addAll(jdbc.queryForList("SELECT * FROM auth_session WHERE account_id=? ORDER BY id",id));}return Map.of("accounts",rows,"sessions",sessionRows);}
    private static LocalDateTime utc(Instant t){return LocalDateTime.ofInstant(t,ZoneOffset.UTC);}
    private HttpResponse<String> call(String method,String path,String body,String token)throws Exception{var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+env.getRequiredProperty("local.server.port")+path)).timeout(Duration.ofSeconds(30));if(token!=null){b.header("Authorization","Bearer "+token);}if(body!=null){b.header("Content-Type","application/json");}return http.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));}
    private JsonNode data(HttpResponse<String> r){assertThat(r.statusCode()).as(r.body()).isIn(200,201);return json.readTree(r.body()).path("data");}
    private void expect(HttpResponse<String> r,int status,String code){assertThat(r.statusCode()).as(r.body()).isEqualTo(status);assertThat(json.readTree(r.body()).path("code").asString()).isEqualTo(code);}
    private static void await(CountDownLatch gate){try{if(!gate.await(15,TimeUnit.SECONDS)){throw new IllegalStateException("gate timeout");}}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
}
