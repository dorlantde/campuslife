package com.campuslife;

import com.campuslife.admin.bootstrap.AdminAccountInitializer;
import com.campuslife.admin.mapper.AdminShopMapper;
import com.campuslife.admin.mapper.ShopReviewMapper;
import com.campuslife.admin.service.ShopReviewService;
import com.campuslife.auth.mapper.AccountMapper;
import com.campuslife.auth.mapper.AuthSessionMapper;
import com.campuslife.auth.security.AuthPrincipal;
import com.campuslife.auth.service.SessionService;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("mysql")
class AdminShopReviewMysqlIT {
    private static final String PASSWORD = " AdminPass_密码1! ";
    private final HttpClient client = HttpClient.newHttpClient();
    private final List<String> names = new ArrayList<>();
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired Environment environment;
    @Autowired AccountMapper accounts;
    @Autowired AuthSessionMapper sessions;
    @Autowired SessionService sessionService;
    @Autowired ShopReviewService service;
    @Autowired PasswordEncoder passwords;
    @Autowired PlatformTransactionManager manager;
    @MockitoSpyBean ShopReviewMapper reviews;
    @MockitoSpyBean AdminShopMapper shops;
    @MockitoSpyBean java.time.Clock clock;
    @Autowired org.mybatis.spring.SqlSessionTemplate sqlSession;

    @AfterEach void cleanup() {
        reset(reviews,shops,clock);
        var ids=new ArrayList<Long>();
        for (String name:names) { ids.addAll(jdbc.queryForList("SELECT id FROM account WHERE username=?",Long.class,name)); }
        for (long id:ids) { jdbc.update("DELETE FROM shop_review WHERE reviewed_by=?",id); }
        for (long id:ids) {
            var shopIds=jdbc.queryForList("SELECT shop_id FROM shop_member WHERE account_id=?",Long.class,id);
            for(long shopId:shopIds) {
                jdbc.update("DELETE FROM shop_review WHERE shop_id=?",shopId);
                jdbc.update("DELETE FROM shop_member WHERE shop_id=?",shopId);
                jdbc.update("DELETE FROM shop WHERE id=?",shopId);
            }
            jdbc.update("DELETE FROM auth_session WHERE account_id=?",id);
        }
        // shop.reviewed_by can reference an admin created before the merchant. Remove every owned shop first.
        for (long id:ids) { jdbc.update("DELETE FROM account WHERE id=?",id); }
    }

    @Test void approveActivatesOwnerKeepsClosedAndWritesOneAudit() throws Exception {
        var admin=admin(); var applicant=merchant(); String token=login(admin);
        String shop=applicant.shopId();
        var result=expect(call("POST",path(shop,"approve"),Map.of("expectedVersion","0"),token),200,"OK").path("data");
        assertThat(result.path("approvalStatus").asString()).isEqualTo("APPROVED");
        assertThat(result.path("memberStatus").asString()).isEqualTo("ACTIVE");
        assertThat(result.path("businessEnabled").asBoolean()).isFalse();
        assertThat(result.path("version").asString()).isEqualTo("1");
        var audit=jdbc.queryForMap("SELECT * FROM shop_review WHERE shop_id=?",shop);
        assertThat(audit.get("reviewed_by").toString()).isEqualTo(admin.id());
        assertThat(audit.get("reviewed_version").toString()).isEqualTo("0");
        assertThat(audit.get("decision")).isEqualTo("APPROVED");
        assertThat(audit.get("reason")).isNull();
        assertThat(jdbc.queryForObject("SELECT reviewed_at FROM shop_review WHERE shop_id=?",LocalDateTime.class,shop))
                .isEqualTo(jdbc.queryForObject("SELECT reviewed_at FROM shop WHERE id=?",LocalDateTime.class,shop));
        expect(call("POST",path(shop,"approve"),Map.of("expectedVersion","0"),token),409,"CONFIG_VERSION_CONFLICT");
        expect(call("POST",path(shop,"approve"),Map.of("expectedVersion","1"),token),409,"SHOP_STATE_CONFLICT");
        assertThat(auditCount(shop)).isEqualTo(1);
        var own=expect(call("GET","/api/v1/merchant/shop",null,login(applicant)),200,"OK").path("data");
        assertThat(own.path("approvalStatus").asString()).isEqualTo("APPROVED");
    }

    @Test void rejectRecordsTrimmedReasonAndMerchantCanSeeIt() throws Exception {
        var admin=admin(); var merchant=merchant(); String reason="😀".repeat(300);
        expect(call("POST",path(merchant.shopId(),"reject"),Map.of("expectedVersion","0","reason","  "+reason+"  "),login(admin)),200,"OK");
        var own=expect(call("GET","/api/v1/merchant/shop",null,login(merchant)),200,"OK").path("data");
        assertThat(own.path("approvalReason").asString()).isEqualTo(reason);
        assertThat(own.path("memberStatus").asString()).isEqualTo("PENDING");
        assertThat(own.path("approvalStatus").asString()).isEqualTo("REJECTED");
        assertThat(own.path("businessEnabled").asBoolean()).isFalse();
        assertThat(auditCount(merchant.shopId())).isEqualTo(1);
    }

    @ParameterizedTest @MethodSource("badBodies")
    void rejectsInvalidReviewBodiesWithoutSideEffects(String action,String body) throws Exception {
        var merchant=merchant();
        expect(raw("POST",path(merchant.shopId(),action),body,login(admin())),400,"VALIDATION_FAILED");
        unchanged(merchant.shopId());
    }
    static Stream<Arguments> badBodies() {
        return Stream.of(Arguments.of("approve","{}"),Arguments.of("approve","{\"expectedVersion\":0}"),
                Arguments.of("approve","{\"expectedVersion\":null}"),Arguments.of("approve","{\"expectedVersion\":\"01\"}"),
                Arguments.of("approve","{\"expectedVersion\":\"9223372036854775808\"}"),
                Arguments.of("approve","{\"expectedVersion\":\"0\",\"role\":\"ADMIN\"}"),
                Arguments.of("reject","{\"expectedVersion\":\"0\"}"),Arguments.of("reject","{\"expectedVersion\":\"0\",\"reason\":null}"),
                Arguments.of("reject","{\"expectedVersion\":\"0\",\"reason\":1}"),
                Arguments.of("reject","{\"expectedVersion\":\"0\",\"reason\":\" \"}"),
                Arguments.of("reject","{\"expectedVersion\":\"0\",\"reason\":\""+"中".repeat(301)+"\"}"),
                Arguments.of("reject","{\"expectedVersion\":\"0\",\"reason\":\"\\uD800\"}"));
    }

    @Test void adminListPaginationDetailAndValidation() throws Exception {
        var a=merchant(); var b=merchant(); var admin=admin(); String token=login(admin);
        var page=expect(call("GET","/api/v1/admin/shops?approvalStatus=PENDING&page=1&size=1",null,token),200,"OK").path("data");
        assertThat(page.path("size").asInt()).isEqualTo(1);
        assertThat(page.path("total").asLong()).isGreaterThanOrEqualTo(2);
        assertThat(page.path("items").get(0).path("id").asString()).isEqualTo(b.shopId());
        var detail=expect(call("GET","/api/v1/admin/shops/"+a.shopId(),null,token),200,"OK").path("data");
        assertThat(detail.toString()).doesNotContain("password", "token", "jti");
        for(String query:List.of("page=0","size=101","size=0","approvalStatus=pending","page=bad")) {
            expect(call("GET","/api/v1/admin/shops?"+query,null,token),400,"VALIDATION_FAILED");
        }
        expect(call("GET","/api/v1/admin/shops/01",null,token),400,"VALIDATION_FAILED");
        expect(call("GET","/api/v1/admin/shops/9223372036854775807",null,token),404,"RESOURCE_NOT_FOUND");
        var empty=expect(call("GET","/api/v1/admin/shops?page=2147483647&size=100",null,token),200,"OK");
        assertThat(empty.path("data").path("items").isEmpty()).isTrue();
    }

    @Test void studentsMerchantsAnonymousRevokedAndDisabledAccountsCannotReview() throws Exception {
        var merchant=merchant();
        expect(call("GET","/api/v1/admin/shops",null,null),401,"AUTH_REQUIRED");
        expect(call("POST",path(merchant.shopId(),"approve"),Map.of("expectedVersion","0"),login(merchant)),403,"ROLE_FORBIDDEN");
        String student=name();
        expect(call("POST","/api/v1/auth/register",Map.of("username",student,"password",PASSWORD,"nickname","学生"),null),201,"OK");
        String st=login(new User("",student,null));
        expect(call("GET","/api/v1/admin/shops",null,st),403,"ROLE_FORBIDDEN");
        var admin=admin(); String token=login(admin);
        jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?",admin.id());
        expect(call("POST",path(merchant.shopId(),"approve"),Map.of("expectedVersion","0"),token),403,"ACCOUNT_DISABLED");
        jdbc.update("UPDATE account SET status='ACTIVE' WHERE id=?",admin.id());
        expect(call("POST","/api/v1/auth/logout",null,token),200,"OK");
        expect(call("POST",path(merchant.shopId(),"approve"),Map.of("expectedVersion","0"),token),401,"SESSION_REVOKED");
        unchanged(merchant.shopId());
    }

    @Test void simultaneousAdministratorsOnlyCommitOneDecision() throws Exception {
        var merchant=merchant(); String a=login(admin()); String b=login(admin());
        CountDownLatch start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var x=pool.submit(()->{start.await();return call("POST",path(merchant.shopId(),"approve"),Map.of("expectedVersion","0"),a);});
            var y=pool.submit(()->{start.await();return call("POST",path(merchant.shopId(),"reject"),Map.of("expectedVersion","0","reason","资料需补充"),b);});
            start.countDown();
            assertThat(List.of(x.get(30,TimeUnit.SECONDS).statusCode(),y.get(30,TimeUnit.SECONDS).statusCode())).containsExactlyInAnyOrder(200,409);
        }
        assertThat(auditCount(merchant.shopId())).isEqualTo(1);
        var state=jdbc.queryForObject("SELECT approval_status FROM shop WHERE id=?",String.class,merchant.shopId());
        assertThat(jdbc.queryForObject("SELECT status FROM shop_member WHERE shop_id=?",String.class,merchant.shopId()))
                .isEqualTo("APPROVED".equals(state)?"ACTIVE":"PENDING");
    }

    @Test void auditForeignKeyFailureRollsBackShopAndMember() throws Exception {
        var merchant=merchant(); String token=login(admin());
        doAnswer(inv->jdbc.update("INSERT INTO shop_review (shop_id,reviewed_version,decision,reviewed_by,reviewed_at) VALUES (?,0,'APPROVED',?,UTC_TIMESTAMP(3))",inv.getArgument(0),Long.MAX_VALUE))
                .when(reviews).append(anyLong(),anyLong(),anyString(),nullable(String.class),anyLong(),any(LocalDateTime.class));
        expect(call("POST",path(merchant.shopId(),"approve"),Map.of("expectedVersion","0"),token),500,"INTERNAL_ERROR");
        unchanged(merchant.shopId());
    }

    @Test void lockedAccountRechecksRoleAndSessionRatherThanTrustingPrincipalSnapshot() throws Exception {
        var admin=admin(); var merchant=merchant();
        String token=login(admin);
        String jti=json.readTree(new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]),StandardCharsets.UTF_8)).path("jti").asString();
        AuthPrincipal principal=new AuthPrincipal(Long.parseLong(admin.id()),jti,
                new com.campuslife.auth.vo.AccountView(admin.id(),admin.username(),"管理员","ADMIN","ACTIVE","0"));
        jdbc.update("UPDATE account SET role='STUDENT' WHERE id=?",admin.id());
        assertThatThrownBy(()->service.decide(principal,merchant.shopId(),json.readTree("\"0\""),true,null))
                .isInstanceOf(com.campuslife.common.exception.BusinessException.class)
                .satisfies(e->assertThat(((com.campuslife.common.exception.BusinessException)e).code()).isEqualTo("ROLE_FORBIDDEN"));
        jdbc.update("UPDATE account SET role='ADMIN' WHERE id=?",admin.id());
        jdbc.update("UPDATE auth_session SET revoked_at=created_at WHERE account_id=? AND jti=?",admin.id(),jti);
        assertThatThrownBy(()->service.decide(principal,merchant.shopId(),json.readTree("\"0\""),true,null))
                .satisfies(e->assertThat(((com.campuslife.common.exception.BusinessException)e).code()).isEqualTo("SESSION_REVOKED"));
        unchanged(merchant.shopId());
    }

    @Test void versionCannotOverflowAndDatabaseRejectsDuplicateAudit() throws Exception {
        var admin=admin(); var merchant=merchant(); String token=login(admin);
        jdbc.update("UPDATE shop SET version=? WHERE id=?",Long.MAX_VALUE,merchant.shopId());
        expect(call("POST",path(merchant.shopId(),"approve"),Map.of("expectedVersion",Long.toString(Long.MAX_VALUE)),token),409,"CONFIG_VERSION_CONFLICT");
        assertThat(auditCount(merchant.shopId())).isZero();
        jdbc.update("UPDATE shop SET version=0 WHERE id=?",merchant.shopId());
        expect(call("POST",path(merchant.shopId(),"approve"),Map.of("expectedVersion","0"),token),200,"OK");
        assertThatThrownBy(()->jdbc.update("INSERT INTO shop_review (shop_id,reviewed_version,decision,reviewed_by,reviewed_at) VALUES (?,0,'APPROVED',?,UTC_TIMESTAMP(3))",merchant.shopId(),admin.id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class).satisfies(e->{
                    var cause=((org.springframework.dao.DataAccessException)e).getMostSpecificCause();
                    assertThat(((SQLException)cause).getErrorCode()).isEqualTo(1062);
                    assertThat(cause.getMessage()).contains("uk_shop_review_version");
                });
    }

    @Test void sessionExpiringWhileWaitingForShopLockCannotCommitReview() throws Exception {
        var merchant=merchant(); String token=login(admin());
        long shopId=Long.parseLong(merchant.shopId());
        CountDownLatch locked=new CountDownLatch(1), release=new CountDownLatch(1);
        var real=sqlSession.getMapper(AdminShopMapper.class);
        doAnswer(inv->{ var row=real.lock(shopId); locked.countDown();
            if(!release.await(15,TimeUnit.SECONDS)) throw new IllegalStateException("Test release timed out");
            return row;
        }).when(shops).lock(shopId);
        try(var pool=Executors.newSingleThreadExecutor()) {
            var response=pool.submit(()->call("POST",path(merchant.shopId(),"approve"),Map.of("expectedVersion","0"),token));
            try {
                assertThat(locked.await(15,TimeUnit.SECONDS)).isTrue();
                doReturn(java.time.Instant.now().plusSeconds(7201)).when(clock).instant();
            } finally { release.countDown(); }
            expect(response.get(20,TimeUnit.SECONDS),401,"SESSION_EXPIRED");
        }
        unchanged(merchant.shopId());
    }

    @Test void explicitPackagedBootstrapCreatesAdminWithoutHttpOrJwtAndDoesNotOverwrite() throws Exception {
        String username=name();
        var output=bootstrap(username,PASSWORD);
        assertThat(output.exit()).isZero();
        assertThat(output.text()).contains("Created ACTIVE ADMIN").doesNotContain("Tomcat",PASSWORD);
        String hash=jdbc.queryForObject("SELECT password_hash FROM account WHERE username=?",String.class,username);
        assertThat(passwords.matches(PASSWORD,hash)).isTrue();
        assertThat(jdbc.queryForObject("SELECT role FROM account WHERE username=?",String.class,username)).isEqualTo("ADMIN");
        var repeated=bootstrap(username,"AnotherPass_123!");
        assertThat(repeated.exit()).isNotZero();
        assertThat(repeated.text()).contains("USERNAME_EXISTS").doesNotContain("AnotherPass_123!");
        assertThat(jdbc.queryForObject("SELECT password_hash FROM account WHERE username=?",String.class,username)).isEqualTo(hash);
    }

    @Test void initializerDoesNotPromoteStudentOnNameCollision() throws Exception {
        String username=name();
        expect(call("POST","/api/v1/auth/register",Map.of("username",username,"password",PASSWORD,"nickname","学生"),null),201,"OK");
        var initializer=new AdminAccountInitializer(accounts,passwords);
        assertThatThrownBy(()->initializer.create(username,"管理员",PASSWORD)).isInstanceOf(com.campuslife.common.exception.BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT role FROM account WHERE username=?",String.class,username)).isEqualTo("STUDENT");
    }

    private record Child(int exit,String text) {}
    private Child bootstrap(String username,String password) throws Exception {
        String java=Path.of(System.getProperty("java.home"),"bin","java.exe").toString();
        var builder=new ProcessBuilder(java,"-Dloader.main=com.campuslife.admin.bootstrap.AdminBootstrap","-cp",Path.of("target/campuslife-0.0.1-SNAPSHOT.jar").toAbsolutePath().toString(),"org.springframework.boot.loader.launch.PropertiesLauncher");
        builder.redirectErrorStream(true);
        builder.environment().put("CAMPUSLIFE_ADMIN_USERNAME",username);
        builder.environment().put("CAMPUSLIFE_ADMIN_NICKNAME","平台管理员");
        builder.environment().put("CAMPUSLIFE_ADMIN_PASSWORD",password);
        builder.environment().remove("JWT_SECRET_BASE64");
        var process=builder.start();
        var reader=Executors.newSingleThreadExecutor();
        try {
            var text=reader.submit(()->new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8));
            if(!process.waitFor(45,TimeUnit.SECONDS)) { throw new AssertionError("Admin bootstrap timed out"); }
            return new Child(process.exitValue(),text.get(5,TimeUnit.SECONDS));
        } finally { if(process.isAlive()) {process.destroyForcibly();process.waitFor();} reader.shutdownNow(); }
    }
    private record User(String id,String username,String shopId) {}
    private User admin() { String name=name(); return new User(new AdminAccountInitializer(accounts,passwords).create(name,"管理员",PASSWORD),name,null); }
    private User merchant() throws Exception {
        String name=name();
        String category=jdbc.queryForObject("SELECT id FROM shop_category WHERE code='HAIRCUT'",String.class);
        var data=expect(call("POST","/api/v1/merchant-applications",Map.of("username",name,"password",PASSWORD,"nickname","店主","categoryId",category,"shopName","审核店铺_"+name,"locationText","虚构校区"),null),201,"OK").path("data");
        return new User(data.path("accountId").asString(),name,data.path("shopId").asString());
    }
    private String name() { String name="adm_"+UUID.randomUUID().toString().replace("-","").substring(0,20); names.add(name); return name; }
    private String login(User user) throws Exception { return expect(call("POST","/api/v1/auth/login",Map.of("username",user.username(),"password",PASSWORD),null),200,"OK").path("data").path("accessToken").asString(); }
    private String path(String id,String action) { return "/api/v1/admin/shops/"+id+"/"+action; }
    private int auditCount(String shop) { return jdbc.queryForObject("SELECT COUNT(*) FROM shop_review WHERE shop_id=?",Integer.class,shop); }
    private void unchanged(String id) {
        assertThat(jdbc.queryForObject("SELECT approval_status FROM shop WHERE id=?",String.class,id)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT status FROM shop_member WHERE shop_id=?",String.class,id)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT version FROM shop WHERE id=?",Long.class,id)).isZero();
        assertThat(auditCount(id)).isZero();
    }
    private JsonNode expect(HttpResponse<String> response,int status,String code) throws Exception { assertThat(response.statusCode()).isEqualTo(status);var body=json.readTree(response.body());assertThat(body.path("code").asString()).isEqualTo(code);return body; }
    private HttpResponse<String> call(String method,String path,Object body,String token) throws Exception { return raw(method,path,body==null?null:json.writeValueAsString(body),token); }
    private HttpResponse<String> raw(String method,String path,String body,String token) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+environment.getRequiredProperty("local.server.port")+path)).timeout(Duration.ofSeconds(25));
        if(token!=null) request.header("Authorization","Bearer "+token);
        if(body!=null) request.header("Content-Type","application/json");
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8));
        return client.send(request.build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
