package com.campuslife;

import com.campuslife.admin.bootstrap.AdminAccountInitializer;
import com.campuslife.auth.security.AuthPrincipal;
import com.campuslife.auth.vo.AccountView;
import com.campuslife.common.exception.BusinessException;
import com.campuslife.merchant.dto.MerchantResubmitRequest;
import com.campuslife.merchant.mapper.ShopMapper;
import com.campuslife.merchant.mapper.ShopCategoryMapper;
import com.campuslife.merchant.service.MerchantResubmissionService;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
class MerchantResubmissionMysqlIT {
    private static final String PASSWORD = " Resubmit_密码1! ";
    private static final String PATH = "/api/v1/merchant/shop/resubmit";
    private final HttpClient client = HttpClient.newHttpClient();
    private final List<String> names = new ArrayList<>();
    private final List<Long> categoryIds = new ArrayList<>();
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired Environment environment;
    @Autowired com.campuslife.auth.mapper.AccountMapper accounts;
    @Autowired PasswordEncoder passwords;
    @Autowired MerchantResubmissionService service;
    @Autowired PlatformTransactionManager manager;
    @Autowired org.mybatis.spring.SqlSessionTemplate sqlSession;
    @MockitoSpyBean ShopMapper shops;
    @MockitoSpyBean ShopCategoryMapper categories;
    @MockitoSpyBean java.time.Clock clock;

    @AfterEach void cleanup() {
        reset(shops, categories, clock);
        var ids = new ArrayList<Long>();
        for (String name : names) { ids.addAll(jdbc.queryForList("SELECT id FROM account WHERE username=?", Long.class, name)); }
        for (long id : ids) {
            for (long shop : jdbc.queryForList("SELECT shop_id FROM shop_member WHERE account_id=?", Long.class, id)) {
                jdbc.update("DELETE FROM shop_review WHERE shop_id=?", shop);
                jdbc.update("DELETE FROM shop_member WHERE shop_id=?", shop);
                jdbc.update("DELETE FROM shop WHERE id=?", shop);
            }
            jdbc.update("DELETE FROM auth_session WHERE account_id=?", id);
        }
        for (long id : ids) { jdbc.update("DELETE FROM account WHERE id=?", id); }
        for (long id : categoryIds) { jdbc.update("DELETE FROM shop_category WHERE id=?", id); }
    }

    @Test void rejectResubmitAndApproveReusesIdentityAndPreservesEveryReview() throws Exception {
        var merchant = merchant(); String admin = login(admin()); String token = login(merchant);
        var account = jdbc.queryForMap("SELECT * FROM account WHERE id=?", merchant.id());
        var member = jdbc.queryForMap("SELECT * FROM shop_member WHERE account_id=?", merchant.id());
        reject(merchant, "0", admin);
        var firstAudit = jdbc.queryForList("SELECT * FROM shop_review WHERE shop_id=?", merchant.shop());
        long category = category(true);
        var body = body("1"); body.put("categoryId", Long.toString(category));
        body.put("shopName", "  " + "😀".repeat(80) + "  ");
        body.put("locationText", "  " + "位".repeat(200) + "  ");
        body.put("description", "  " + "介".repeat(1000) + "  ");
        var pending = expect(call("POST", PATH, body, token), 200, "OK").path("data");
        assertThat(pending.path("id").asString()).isEqualTo(merchant.shop());
        assertThat(pending.path("version").asString()).isEqualTo("2");
        assertThat(pending.path("approvalStatus").asString()).isEqualTo("PENDING");
        assertThat(pending.path("memberStatus").asString()).isEqualTo("PENDING");
        assertThat(pending.path("businessEnabled").asBoolean()).isFalse();
        assertThat(pending.path("approvalReason").isNull()).isTrue();
        assertThat(pending.path("name").asString()).isEqualTo("😀".repeat(80));
        assertThat(pending.path("locationText").asString()).isEqualTo("位".repeat(200));
        assertThat(pending.path("description").asString()).isEqualTo("介".repeat(1000));
        assertThat(pending.path("category").path("id").asString()).isEqualTo(Long.toString(category));
        assertThat(jdbc.queryForMap("SELECT reviewed_by,reviewed_at,approval_reason FROM shop WHERE id=?", merchant.shop()).values()).containsOnlyNulls();
        assertThat(jdbc.queryForList("SELECT * FROM shop_review WHERE shop_id=?", merchant.shop())).isEqualTo(firstAudit);
        assertThat(jdbc.queryForMap("SELECT * FROM account WHERE id=?", merchant.id())).isEqualTo(account);
        assertThat(jdbc.queryForMap("SELECT * FROM shop_member WHERE account_id=?", merchant.id())).isEqualTo(member);
        expect(call("POST", PATH, body, token), 409, "CONFIG_VERSION_CONFLICT");
        expect(call("POST", PATH, body("2"), token), 409, "SHOP_STATE_CONFLICT");
        reject(merchant, "2", admin);
        var noDescription = expect(call("POST", PATH, body("3"), token), 200, "OK").path("data");
        assertThat(noDescription.path("description").asString()).isEmpty();
        var approved = expect(call("POST", review(merchant, "approve"), Map.of("expectedVersion", "4"), admin), 200, "OK").path("data");
        assertThat(approved.path("version").asString()).isEqualTo("5");
        assertThat(approved.path("memberStatus").asString()).isEqualTo("ACTIVE");
        assertThat(approved.path("businessEnabled").asBoolean()).isFalse();
        assertThat(jdbc.queryForList("SELECT reviewed_version FROM shop_review WHERE shop_id=? ORDER BY id", Long.class, merchant.shop())).containsExactly(0L, 2L, 4L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shop_member WHERE account_id=?", Integer.class, merchant.id())).isEqualTo(1);
        expect(call("GET", "/api/v1/merchant/shop", null, token), 200, "OK");
    }

    @ParameterizedTest @MethodSource("invalidBodies")
    void invalidInputCannotChangeRejectedShop(String field, Object value, boolean remove) throws Exception {
        var merchant = rejected(); String token = login(merchant);
        var before = snapshot(merchant);
        var body = body("1");
        if (remove) { body.remove(field); } else { body.put(field, value); }
        expect(call("POST", PATH, body, token), 400, "VALIDATION_FAILED");
        assertThat(snapshot(merchant)).isEqualTo(before);
    }
    static Stream<Arguments> invalidBodies() {
        return Stream.of(
                Arguments.of("expectedVersion", null, true), Arguments.of("expectedVersion", null, false),
                Arguments.of("expectedVersion", 1, false), Arguments.of("expectedVersion", "01", false),
                Arguments.of("expectedVersion", "-1", false), Arguments.of("expectedVersion", "9223372036854775808", false),
                Arguments.of("categoryId", null, true), Arguments.of("categoryId", null, false),
                Arguments.of("categoryId", 1, false), Arguments.of("categoryId", "0", false),
                Arguments.of("categoryId", "01", false), Arguments.of("categoryId", "9223372036854775808", false),
                Arguments.of("shopName", null, true), Arguments.of("shopName", null, false),
                Arguments.of("shopName", "  ", false), Arguments.of("shopName", "😀".repeat(81), false),
                Arguments.of("shopName", 123, false), Arguments.of("shopName", "\uD800", false),
                Arguments.of("locationText", null, true), Arguments.of("locationText", "  ", false),
                Arguments.of("locationText", false, false), Arguments.of("description", 123, false),
                Arguments.of("locationText", "位".repeat(201), false), Arguments.of("description", "介".repeat(1001), false),
                Arguments.of("description", List.of("bad"), false), Arguments.of("description", "\uD800", false),
                Arguments.of("shopId", "1", false), Arguments.of("accountId", "1", false),
                Arguments.of("approvalStatus", "APPROVED", false), Arguments.of("role", "ADMIN", false));
    }

    @Test void rejectsMissingOrDisabledCategoryAndKeepsOriginalApplication() throws Exception {
        var merchant = rejected(); String token = login(merchant); var before = snapshot(merchant);
        for (long category : List.of(Long.MAX_VALUE, category(false))) {
            var body = body("1"); body.put("categoryId", Long.toString(category));
            expect(call("POST", PATH, body, token), 400, "VALIDATION_FAILED");
            assertThat(snapshot(merchant)).isEqualTo(before);
        }
        var nullableDescription = body("1"); nullableDescription.put("description", null);
        assertThat(expect(call("POST", PATH, nullableDescription, token), 200, "OK").path("data").path("description").asString()).isEmpty();
    }

    @Test void onlyRejectedOwnShopWithPendingOwnerCanBeResubmitted() throws Exception {
        var merchant = merchant(); String token = login(merchant);
        for (String state : List.of("PENDING", "APPROVED", "SUSPENDED")) {
            jdbc.update("UPDATE shop SET approval_status=? WHERE id=?", state, merchant.shop());
            var before = snapshot(merchant);
            expect(call("POST", PATH, body("0"), token), 409, "SHOP_STATE_CONFLICT");
            assertThat(snapshot(merchant)).isEqualTo(before);
        }
        jdbc.update("UPDATE shop SET approval_status='REJECTED' WHERE id=?", merchant.shop());
        for (String state : List.of("ACTIVE", "DISABLED")) {
            jdbc.update("UPDATE shop_member SET status=? WHERE account_id=?", state, merchant.id());
            expect(call("POST", PATH, body("0"), token), 409, "SHOP_STATE_CONFLICT");
        }
        jdbc.update("UPDATE shop_member SET status='PENDING' WHERE account_id=?", merchant.id());
        jdbc.update("UPDATE shop SET version=? WHERE id=?", Long.MAX_VALUE, merchant.shop());
        expect(call("POST", PATH, body(Long.toString(Long.MAX_VALUE)), token), 409, "CONFIG_VERSION_CONFLICT");
        assertThat(jdbc.queryForObject("SELECT version FROM shop WHERE id=?", Long.class, merchant.shop())).isEqualTo(Long.MAX_VALUE);
    }

    @Test void rolesSessionsOwnershipAndMissingShopAreEnforced() throws Exception {
        var merchant = rejected(); var other = rejected(); var before = snapshot(other); String token = login(merchant);
        expect(call("POST", PATH, body("1"), null), 401, "AUTH_REQUIRED");
        expect(call("POST", PATH, body("1"), login(admin())), 403, "ROLE_FORBIDDEN");
        String name = name();
        expect(call("POST", "/api/v1/auth/register", Map.of("username", name, "password", PASSWORD, "nickname", "学生"), null), 201, "OK");
        expect(call("POST", PATH, body("1"), login(new User("", name, null))), 403, "ROLE_FORBIDDEN");
        jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?", merchant.id());
        expect(call("POST", PATH, body("1"), token), 403, "ACCOUNT_DISABLED");
        jdbc.update("UPDATE account SET status='ACTIVE' WHERE id=?", merchant.id());
        expect(call("POST", "/api/v1/auth/logout", null, token), 200, "OK");
        expect(call("POST", PATH, body("1"), token), 401, "SESSION_REVOKED");
        expect(call("POST", PATH, body("1"), login(merchant)), 200, "OK");
        assertThat(snapshot(other)).isEqualTo(before);
        jdbc.update("DELETE FROM shop_member WHERE account_id=?", merchant.id());
        // Restore membership in finally so fixture cleanup can still find the shop and audit.
        try { expect(call("POST", PATH, body("2"), login(merchant)), 404, "RESOURCE_NOT_FOUND"); }
        finally { jdbc.update("INSERT INTO shop_member (shop_id,account_id) VALUES (?,?)", merchant.shop(), merchant.id()); }
    }

    @Test void freshAccountRoleAndSessionAreCheckedInsideTransaction() throws Exception {
        var merchant = rejected(); String token = login(merchant);
        String jti = json.readTree(new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8)).path("jti").asString();
        var principal = new AuthPrincipal(Long.parseLong(merchant.id()), jti,
                new AccountView(merchant.id(), merchant.name(), "店主", "MERCHANT", "ACTIVE", "0"));
        var request = json.convertValue(body("1"), MerchantResubmitRequest.class); var before = snapshot(merchant);
        jdbc.update("UPDATE account SET role='STUDENT' WHERE id=?", merchant.id());
        assertBusiness(() -> service.resubmit(principal, request), "ROLE_FORBIDDEN");
        jdbc.update("UPDATE account SET role='MERCHANT',status='DISABLED' WHERE id=?", merchant.id());
        assertBusiness(() -> service.resubmit(principal, request), "ACCOUNT_DISABLED");
        jdbc.update("UPDATE account SET status='ACTIVE' WHERE id=?", merchant.id());
        jdbc.update("UPDATE auth_session SET revoked_at=created_at WHERE account_id=? AND jti=?", merchant.id(), jti);
        assertBusiness(() -> service.resubmit(principal, request), "SESSION_REVOKED");
        assertThat(snapshot(merchant)).isEqualTo(before);
    }

    @Test void concurrentResubmissionsFromTwoSessionsOnlyIncrementOnce() throws Exception {
        var merchant = rejected(); String a = login(merchant), b = login(merchant);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var x = pool.submit(() -> { start.await(); return call("POST", PATH, body("1"), a); });
            var y = pool.submit(() -> { start.await(); var body = body("1"); body.put("shopName", "另一次修改"); return call("POST", PATH, body, b); });
            start.countDown();
            assertThat(List.of(x.get(30, TimeUnit.SECONDS).statusCode(), y.get(30, TimeUnit.SECONDS).statusCode())).containsExactlyInAnyOrder(200, 409);
        }
        assertThat(jdbc.queryForObject("SELECT version FROM shop WHERE id=?", Long.class, merchant.shop())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shop_review WHERE shop_id=?", Integer.class, merchant.shop())).isEqualTo(1);
    }

    @Test void staleAdministratorReviewCannotOverwriteResubmittedApplication() throws Exception {
        var merchant = rejected(); String owner = login(merchant), admin = login(admin());
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var x = pool.submit(() -> { start.await(); return call("POST", PATH, body("1"), owner); });
            var y = pool.submit(() -> { start.await(); return call("POST", review(merchant, "approve"), Map.of("expectedVersion", "1"), admin); });
            start.countDown();
            expect(x.get(30, TimeUnit.SECONDS), 200, "OK");
            assertThat(y.get(30, TimeUnit.SECONDS).statusCode()).isEqualTo(409);
        }
        assertThat(jdbc.queryForObject("SELECT approval_status FROM shop WHERE id=?", String.class, merchant.shop())).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shop_review WHERE shop_id=?", Integer.class, merchant.shop())).isEqualTo(1);
    }

    @Test void failureAfterActualSqlUpdateRollsBackAllCurrentFieldsAndPreservesAudit() throws Exception {
        var merchant = rejected(); String token = login(merchant); var before = snapshot(merchant);
        var real = sqlSession.getMapper(ShopMapper.class);
        doAnswer(inv -> {
            assertThat(real.resubmit(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), inv.getArgument(3), inv.getArgument(4), inv.getArgument(5))).isEqualTo(1);
            throw new IllegalStateException("Injected after actual SQL update");
        }).when(shops).resubmit(anyLong(), anyLong(), anyLong(), anyString(), anyString(), anyString());
        expect(call("POST", PATH, body("1"), token), 500, "INTERNAL_ERROR");
        assertThat(snapshot(merchant)).isEqualTo(before);
    }

    @Test void sessionExpiringDuringLockWaitCannotResubmit() throws Exception {
        var merchant = rejected(); String token = login(merchant); var before = snapshot(merchant);
        long shopId = Long.parseLong(merchant.shop());
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
        var real = sqlSession.getMapper(ShopMapper.class);
        doAnswer(inv -> {
            var row = real.lock(shopId); locked.countDown();
            if (!release.await(15, TimeUnit.SECONDS)) { throw new IllegalStateException("Test gate timeout"); }
            return row;
        }).when(shops).lock(shopId);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var response = pool.submit(() -> call("POST", PATH, body("1"), token));
            try { assertThat(locked.await(15, TimeUnit.SECONDS)).isTrue(); doReturn(java.time.Instant.now().plusSeconds(7201)).when(clock).instant(); }
            finally { release.countDown(); }
            expect(response.get(20, TimeUnit.SECONDS), 401, "SESSION_EXPIRED");
        }
        assertThat(snapshot(merchant)).isEqualTo(before);
    }

    @Test void categorySharedLockPreventsDisableUntilResubmissionCommits() throws Exception {
        var merchant = rejected(); String token = login(merchant); long category = category(true);
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1), updating = new CountDownLatch(1);
        var real = sqlSession.getMapper(ShopCategoryMapper.class);
        doAnswer(inv -> { var id = real.lockEnabled(category); locked.countDown();
            if (!release.await(15, TimeUnit.SECONDS)) { throw new IllegalStateException("Test gate timeout"); } return id;
        }).when(categories).lockEnabled(category);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var body = body("1"); body.put("categoryId", Long.toString(category));
            var response = pool.submit(() -> call("POST", PATH, body, token));
            Future<?> disable = null;
            try {
                assertThat(locked.await(15, TimeUnit.SECONDS)).isTrue();
                disable = pool.submit(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
                    updating.countDown(); jdbc.update("UPDATE shop_category SET enabled=FALSE WHERE id=?", category);
                }));
                assertThat(updating.await(5, TimeUnit.SECONDS)).isTrue();
                Future<?> waiting = disable;
                assertThatThrownBy(() -> waiting.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            } finally { release.countDown(); }
            expect(response.get(20, TimeUnit.SECONDS), 200, "OK");
            if (disable != null) { disable.get(10, TimeUnit.SECONDS); }
        }
        assertThat(jdbc.queryForObject("SELECT category_id FROM shop WHERE id=?", Long.class, merchant.shop())).isEqualTo(category);
        assertThat(jdbc.queryForObject("SELECT enabled FROM shop_category WHERE id=?", Boolean.class, category)).isFalse();
    }

    private void assertBusiness(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String code) {
        assertThatThrownBy(action).isInstanceOf(BusinessException.class).satisfies(e -> assertThat(((BusinessException) e).code()).isEqualTo(code));
    }
    private record User(String id, String name, String shop) {}
    private User admin() { String name = name(); return new User(new AdminAccountInitializer(accounts, passwords).create(name, "管理员", PASSWORD), name, null); }
    private User merchant() throws Exception {
        String name = name();
        var data = expect(call("POST", "/api/v1/merchant-applications", Map.of("username", name, "password", PASSWORD, "nickname", "店主", "categoryId", haircut(), "shopName", "原店铺", "locationText", "虚构校区", "description", "旧介绍"), null), 201, "OK").path("data");
        return new User(data.path("accountId").asString(), name, data.path("shopId").asString());
    }
    private User rejected() throws Exception { var merchant = merchant(); reject(merchant, "0", login(admin())); return merchant; }
    private void reject(User user, String version, String token) throws Exception { expect(call("POST", review(user, "reject"), Map.of("expectedVersion", version, "reason", "请补全位置"), token), 200, "OK"); }
    private String review(User user, String action) { return "/api/v1/admin/shops/" + user.shop() + "/" + action; }
    private String haircut() { return jdbc.queryForObject("SELECT id FROM shop_category WHERE code='HAIRCUT'", String.class); }
    private long category(boolean enabled) {
        String code = "R_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        jdbc.update("INSERT INTO shop_category (code,name,enabled) VALUES (?,'重提分类',?)", code, enabled);
        long id = jdbc.queryForObject("SELECT id FROM shop_category WHERE code=?", Long.class, code); categoryIds.add(id); return id;
    }
    private Map<String, Object> body(String version) {
        var body = new LinkedHashMap<String, Object>(); body.put("expectedVersion", version); body.put("categoryId", haircut());
        body.put("shopName", " 修改后的店铺 "); body.put("locationText", " 服务中心 102 "); return body;
    }
    private List<?> snapshot(User user) {
        return List.of(jdbc.queryForMap("SELECT * FROM shop WHERE id=?", user.shop()),
                jdbc.queryForList("SELECT * FROM shop_review WHERE shop_id=? ORDER BY id", user.shop()),
                jdbc.queryForMap("SELECT * FROM shop_member WHERE account_id=?", user.id()));
    }
    private String name() { String name = "res_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20); names.add(name); return name; }
    private String login(User user) throws Exception { return expect(call("POST", "/api/v1/auth/login", Map.of("username", user.name(), "password", PASSWORD), null), 200, "OK").path("data").path("accessToken").asString(); }
    private JsonNode expect(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        var body = json.readTree(response.body()); assertThat(body.path("code").asString()).isEqualTo(code); return body;
    }
    private HttpResponse<String> call(String method, String path, Object body, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + environment.getRequiredProperty("local.server.port") + path)).timeout(Duration.ofSeconds(25));
        if (token != null) { request.header("Authorization", "Bearer " + token); }
        if (body != null) { request.header("Content-Type", "application/json"); }
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(
                json.writeValueAsString(body).replace("\uD800", "\\uD800"), StandardCharsets.UTF_8));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
