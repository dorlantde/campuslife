package com.campuslife;

import com.campuslife.merchant.mapper.ShopMemberMapper;
import com.campuslife.merchant.mapper.ShopCategoryMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("mysql")
class MerchantOnboardingMysqlIT {
    private static final String PASSWORD = " MerchantPass_密码1! ";
    private final HttpClient client = HttpClient.newHttpClient();
    private final List<String> names = new ArrayList<>();
    private final List<Long> categoryIds = new ArrayList<>();
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired Environment environment;
    @Autowired PasswordEncoder passwords;
    @Autowired PlatformTransactionManager manager;
    @Autowired org.mybatis.spring.SqlSessionTemplate sqlSession;
    @MockitoSpyBean ShopMemberMapper members;
    @MockitoSpyBean ShopCategoryMapper categories;

    @AfterEach
    void cleanOwnedRows() {
        reset(members, categories);
        for (String name : names) {
            var ids = jdbc.queryForList("SELECT id FROM account WHERE username = ?", Long.class, name);
            for (long id : ids) {
                var shops = jdbc.queryForList("SELECT shop_id FROM shop_member WHERE account_id = ?", Long.class, id);
                jdbc.update("DELETE FROM shop_member WHERE account_id = ?", id);
                for (long shopId : shops) { jdbc.update("DELETE FROM shop WHERE id = ?", shopId); }
                jdbc.update("DELETE FROM auth_session WHERE account_id = ?", id);
                jdbc.update("DELETE FROM account WHERE id = ?", id);
            }
        }
        for (long id : categoryIds) { jdbc.update("DELETE FROM shop_category WHERE id = ?", id); }
    }

    @Test
    void categoriesArePublicAndHideDisabledRows() throws Exception {
        long hidden = category(false);
        JsonNode items = expect(call("GET", "/api/v1/shop-categories", null, null), 200, "OK").path("data");
        assertThat(items.toString()).contains("HAIRCUT", "PRINT").doesNotContain(Long.toString(hidden));
        assertThat(items.get(0).path("id").isString()).isTrue();
        expect(call("GET", "/api/v1/shop-categories", null, "bad-token"), 401, "TOKEN_INVALID");
    }

    @Test
    void createsThreeRowsAndPendingMerchantCanLoginAndReadOwnShop() throws Exception {
        var request = request();
        request.put("username", "  " + request.get("username").toString().toUpperCase(java.util.Locale.ROOT) + "  ");
        request.put("shopName", "  星海😀理发店  ");
        request.put("description", "  虚构店铺  ");
        JsonNode created = apply(request, 201, "OK").path("data");
        assertThat(created.size()).isEqualTo(3);
        long accountId = Long.parseLong(created.path("accountId").asString());
        long shopId = Long.parseLong(created.path("shopId").asString());
        var account = jdbc.queryForMap("SELECT * FROM account WHERE id = ?", accountId);
        assertThat(account).containsEntry("role", "MERCHANT").containsEntry("status", "ACTIVE");
        String hash = account.get("password_hash").toString();
        assertThat(hash).startsWith("{bcrypt}$2b$12$");
        assertThat(passwords.matches(PASSWORD, hash)).isTrue();
        assertThat(passwords.matches(PASSWORD.strip(), hash)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_session WHERE account_id=?", Integer.class, accountId)).isZero();
        assertThat(jdbc.queryForMap("SELECT member_role, status FROM shop_member WHERE account_id=? AND shop_id=?", accountId, shopId))
                .containsEntry("member_role", "OWNER").containsEntry("status", "PENDING");
        String token = login(request.get("username").toString(), PASSWORD);
        JsonNode own = expect(call("GET", "/api/v1/merchant/shop", null, token), 200, "OK").path("data");
        assertThat(own.path("id").asString()).isEqualTo(Long.toString(shopId));
        assertThat(own.path("name").asString()).isEqualTo("星海😀理发店");
        assertThat(own.path("description").asString()).isEqualTo("虚构店铺");
        assertThat(own.path("campusCode").asString()).isEqualTo("XINGHAI_MAIN");
        assertThat(own.path("approvalStatus").asString()).isEqualTo("PENDING");
        assertThat(own.path("memberStatus").asString()).isEqualTo("PENDING");
        assertThat(own.path("businessEnabled").asBoolean()).isFalse();
        assertThat(own.path("approvalReason").isNull()).isTrue();
        assertThat(own.path("version").asString()).isEqualTo("0");
        assertThat(own.toString()).doesNotContain("password", "jti", "token", "accountId");
        JsonNode me = expect(call("GET", "/api/v1/me", null, token), 200, "OK").path("data");
        assertThat(me.path("role").asString()).isEqualTo("MERCHANT");
        // Pending applicants may read their result but cannot manage services.
        expect(call("POST", "/api/v1/merchant/services", json.writeValueAsString(Map.of(
                "serviceType", "HAIRCUT", "name", "模拟理发", "priceAmount", 3000,
                "durationMinutes", 30)), token), 403, "SHOP_NOT_APPROVED");
    }

    @ParameterizedTest
    @MethodSource("invalidFields")
    void invalidApplicationNeverLeavesAnAccountOrShop(String field, Object value) throws Exception {
        var request = request();
        request.put(field, value);
        apply(request, 400, "VALIDATION_FAILED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account WHERE username=?", Integer.class, request.get("username"))).isZero();
    }

    static Stream<Arguments> invalidFields() {
        return Stream.of(Arguments.of("shopName", " "), Arguments.of("shopName", "😀".repeat(81)),
                Arguments.of("locationText", "x".repeat(201)), Arguments.of("description", "x".repeat(1001)),
                Arguments.of("shopName", "\uD800"), Arguments.of("categoryId", 1), Arguments.of("categoryId", "01"),
                Arguments.of("categoryId", "0"), Arguments.of("categoryId", "9223372036854775808"),
                Arguments.of("categoryId", "9223372036854775807"), Arguments.of("password", "short"),
                Arguments.of("role", "ADMIN"), Arguments.of("approvalStatus", "APPROVED"),
                Arguments.of("businessEnabled", true), Arguments.of("accountId", "1"));
    }

    @Test
    void disabledCategoryIsRejectedAndUnicodeLimitsAndMissingDescriptionAreSupported() throws Exception {
        var invalid = request(); invalid.put("categoryId", Long.toString(category(false)));
        apply(invalid, 400, "VALIDATION_FAILED");
        var valid = request(); valid.put("shopName", "😀".repeat(80)); valid.put("locationText", "中".repeat(200));
        valid.remove("description");
        var result = apply(valid, 201, "OK").path("data");
        assertThat(jdbc.queryForObject("SELECT description FROM shop WHERE id=?", String.class, result.path("shopId").asString())).isEmpty();
    }

    @Test
    void duplicateUsernameAcrossStudentAndMerchantDoesNotChangeExistingAccount() throws Exception {
        var request = request();
        expect(call("POST", "/api/v1/auth/register", json.writeValueAsString(Map.of("username",request.get("username"),"password",PASSWORD,"nickname","学生")), null), 201,"OK");
        apply(request, 409, "USERNAME_EXISTS");
        assertThat(jdbc.queryForObject("SELECT role FROM account WHERE username=?",String.class,request.get("username"))).isEqualTo("STUDENT");
        String token = login(request.get("username").toString(), PASSWORD);
        expect(call("GET", "/api/v1/merchant/shop", null, token),403,"ROLE_FORBIDDEN");
    }

    @Test
    void simultaneousApplicationsShareTheUsernameUniqueConstraint() throws Exception {
        var request = request();
        String body = json.writeValueAsString(request);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(); return call("POST", "/api/v1/merchant-applications",body,null); });
            var second = pool.submit(() -> { start.await(); return call("POST", "/api/v1/merchant-applications",body,null); });
            start.countDown();
            assertThat(List.of(first.get(30,TimeUnit.SECONDS).statusCode(),second.get(30,TimeUnit.SECONDS).statusCode())).containsExactlyInAnyOrder(201,409);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shop_member m JOIN account a ON a.id=m.account_id WHERE a.username=?",Integer.class,request.get("username"))).isEqualTo(1);
    }

    @Test
    void failureOfLastInsertRollsBackAccountAndShopInRealMysql() throws Exception {
        var request = request();
        doAnswer(invocation -> jdbc.update("INSERT INTO shop_member (shop_id, account_id) VALUES (?, ?)", invocation.getArgument(0), Long.MAX_VALUE))
                .when(members).createOwner(anyLong(), anyLong());
        var response = call("POST", "/api/v1/merchant-applications",json.writeValueAsString(request),null);
        expect(response,500,"INTERNAL_ERROR");
        assertThat(response.body()).doesNotContain("INSERT", "foreign", "password", "SQL");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account WHERE username=?",Integer.class,request.get("username"))).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shop WHERE name=?",Integer.class,request.get("shopName"))).isZero();
    }

    @Test
    void categoryDisableCommittedBeforeLockIsObservedByWaitingApplication() throws Exception {
        long categoryId = category(true);
        var request = request(); request.put("categoryId",Long.toString(categoryId));
        String body = json.writeValueAsString(request);
        CountDownLatch reading = new CountDownLatch(1);
        // Obtain the real MyBatis proxy, not a Mockito abstract-method invocation.
        var realCategories = sqlSession.getMapper(ShopCategoryMapper.class);
        doAnswer(invocation -> { reading.countDown(); return realCategories.lockEnabled(categoryId); })
                .when(categories).lockEnabled(categoryId);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var future = new TransactionTemplate(manager).execute(status -> {
                jdbc.queryForObject("SELECT id FROM shop_category WHERE id=? FOR UPDATE",Long.class,categoryId);
                var response = pool.submit(() -> call("POST","/api/v1/merchant-applications",body,null));
                try {
                    assertThat(reading.await(15, TimeUnit.SECONDS)).isTrue();
                    assertThatThrownBy(() -> response.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
                } catch (InterruptedException exception) { throw new IllegalStateException(exception); }
                jdbc.update("UPDATE shop_category SET enabled=FALSE WHERE id=?",categoryId);
                return response;
            });
            expect(future.get(30,TimeUnit.SECONDS),400,"VALIDATION_FAILED");
        }
    }

    @Test
    void databaseRejectsInvalidEnumsForeignKeysAndSecondOwner() throws Exception {
        var first = apply(request(),201,"OK").path("data");
        long shopId = Long.parseLong(first.path("shopId").asString());
        violation(() -> jdbc.update("UPDATE shop SET approval_status='approved' WHERE id=?",shopId),"ck_shop_approval",3819);
        violation(() -> jdbc.update("UPDATE shop SET category_id=? WHERE id=?",Long.MAX_VALUE,shopId),"foreign key constraint",1216,1452);
        violation(() -> jdbc.update("UPDATE shop SET business_enabled=2 WHERE id=?",shopId),"ck_shop_business",3819);
        String second = request().get("username").toString();
        jdbc.update("INSERT INTO account (username,password_hash,nickname,role) VALUES (?, 'test-digest', '独立店主', 'MERCHANT')",second);
        long id=jdbc.queryForObject("SELECT id FROM account WHERE username=?",Long.class,second);
        violation(() -> jdbc.update("INSERT INTO shop_member (shop_id,account_id) VALUES (?,?)",shopId,id),"uk_shop_member_owner",1062);
    }

    @Test
    void identityOwnershipRevocationAndAccountStatusAreEnforced() throws Exception {
        expect(call("GET","/api/v1/merchant/shop",null,null),401,"AUTH_REQUIRED");
        var a = request(); var b = request();
        var first = apply(a,201,"OK").path("data"); var second = apply(b,201,"OK").path("data");
        String token = login(a.get("username").toString(),PASSWORD);
        var own = expect(call("GET","/api/v1/merchant/shop?shopId="+second.path("shopId").asString(),null,token),200,"OK").path("data");
        assertThat(own.path("id").asString()).isEqualTo(first.path("shopId").asString());
        jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?",first.path("accountId").asString());
        expect(call("GET","/api/v1/merchant/shop",null,token),403,"ACCOUNT_DISABLED");
        jdbc.update("UPDATE account SET status='ACTIVE' WHERE id=?",first.path("accountId").asString());
        expect(call("POST","/api/v1/auth/logout",null,token),200,"OK");
        expect(call("GET","/api/v1/merchant/shop",null,token),401,"SESSION_REVOKED");
    }

    private Map<String,Object> request() {
        String name = "mer_"+UUID.randomUUID().toString().replace("-", "").substring(0,20); names.add(name);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("username",name); result.put("password",PASSWORD); result.put("nickname","店主");
        result.put("categoryId",jdbc.queryForObject("SELECT id FROM shop_category WHERE code='HAIRCUT'",String.class));
        result.put("shopName","商店_"+name); result.put("locationText","虚构校区"); result.put("description"," ");
        return result;
    }
    private void violation(Runnable operation,String message,int... codes) {
        assertThatThrownBy(operation::run).isInstanceOf(org.springframework.dao.DataAccessException.class).satisfies(error -> {
            var cause=((org.springframework.dao.DataAccessException)error).getMostSpecificCause();
            assertThat(cause).isInstanceOf(java.sql.SQLException.class);
            assertThat(codes).contains(((java.sql.SQLException)cause).getErrorCode());
            assertThat(cause.getMessage()).contains(message);
        });
    }
    private long category(boolean enabled) {
        String code = "IT_"+UUID.randomUUID().toString().replace("-", "").substring(0,20);
        jdbc.update("INSERT INTO shop_category (code,name,enabled) VALUES (?, '临时分类', ?)",code,enabled);
        long id = jdbc.queryForObject("SELECT id FROM shop_category WHERE code=?",Long.class,code); categoryIds.add(id); return id;
    }
    private String login(String name,String password) throws Exception {
        return expect(call("POST","/api/v1/auth/login",json.writeValueAsString(Map.of("username",name,"password",password)),null),200,"OK")
                .path("data").path("accessToken").asString();
    }
    private JsonNode apply(Map<String,Object> request,int status,String code) throws Exception {
        // Escape an invalid surrogate explicitly; UTF-8 HTTP encoding otherwise replaces it before reaching the server.
        return expect(call("POST","/api/v1/merchant-applications",json.writeValueAsString(request).replace("\uD800", "\\uD800"),null),status,code);
    }
    private JsonNode expect(HttpResponse<String> response,int status,String code) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        var body=json.readTree(response.body()); assertThat(body.path("code").asString()).isEqualTo(code);
        assertThat(response.headers().firstValue("X-Trace-Id")).contains(body.path("traceId").asString());
        return body;
    }
    private HttpResponse<String> call(String method,String path,String body,String token) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+environment.getRequiredProperty("local.server.port")+path)).timeout(Duration.ofSeconds(25));
        if (token!=null) { request.header("Authorization","Bearer "+token); }
        if (body!=null) { request.header("Content-Type","application/json"); }
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8));
        return client.send(request.build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
