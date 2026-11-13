package com.campuslife;

import com.campuslife.admin.bootstrap.AdminAccountInitializer;
import com.campuslife.auth.mapper.AccountMapper;
import com.campuslife.auth.security.AuthPrincipal;
import com.campuslife.auth.vo.AccountView;
import com.campuslife.common.exception.BusinessException;
import com.campuslife.merchant.dto.ShopPatchRequest;
import com.campuslife.merchant.mapper.ShopMapper;
import com.campuslife.merchant.service.MerchantOperationService;
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
class MerchantOperationMysqlIT {
    private static final String PASSWORD = " Operation_密码1! ";
    private static final String PATH = "/api/v1/merchant/shop";
    private final HttpClient client = HttpClient.newHttpClient();
    private final List<String> names = new ArrayList<>();
    private User reviewer;
    private String reviewerToken;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired Environment environment;
    @Autowired AccountMapper accounts;
    @Autowired PasswordEncoder passwords;
    @Autowired MerchantOperationService operations;
    @Autowired PlatformTransactionManager manager;
    @Autowired org.mybatis.spring.SqlSessionTemplate sqlSession;
    @MockitoSpyBean ShopMapper shops;
    @MockitoSpyBean java.time.Clock clock;

    @AfterEach void cleanup() {
        reset(shops, clock);
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
    }

    @Test void patchesAllFieldsOnceAndOpensThenClosesWithoutChangingReviewOrIdentity() throws Exception {
        var merchant = approved(); String token = login(merchant);
        var account = jdbc.queryForMap("SELECT * FROM account WHERE id=?", merchant.id());
        var member = jdbc.queryForMap("SELECT * FROM shop_member WHERE account_id=?", merchant.id());
        var audit = audit(merchant);
        var review = jdbc.queryForMap("SELECT category_id,campus_code,approval_status,approval_reason,reviewed_by,reviewed_at FROM shop WHERE id=?", merchant.shop());
        var result = expect(patch(Map.of("expectedVersion", "1", "name", "  " + "😀".repeat(80) + "  ",
                "locationText", "  " + "位".repeat(200) + "  ", "description", "  " + "介".repeat(1000) + "  ", "businessEnabled", true), token), 200, "OK").path("data");
        assertThat(result.path("id").asString()).isEqualTo(merchant.shop());
        assertThat(result.path("name").asString()).isEqualTo("😀".repeat(80));
        assertThat(result.path("locationText").asString()).isEqualTo("位".repeat(200));
        assertThat(result.path("description").asString()).isEqualTo("介".repeat(1000));
        assertThat(result.path("version").asString()).isEqualTo("2");
        assertThat(result.path("businessEnabled").asBoolean()).isTrue();
        assertThat(result.path("approvalStatus").asString()).isEqualTo("APPROVED");
        assertThat(result.path("memberStatus").asString()).isEqualTo("ACTIVE");
        var closed = expect(patch(Map.of("expectedVersion", "2", "businessEnabled", false), token), 200, "OK").path("data");
        assertThat(closed.path("version").asString()).isEqualTo("3");
        assertThat(closed.path("businessEnabled").asBoolean()).isFalse();
        assertThat(closed.path("name")).isEqualTo(result.path("name"));
        assertThat(closed.path("description")).isEqualTo(result.path("description"));
        assertThat(jdbc.queryForMap("SELECT * FROM account WHERE id=?", merchant.id())).isEqualTo(account);
        assertThat(jdbc.queryForMap("SELECT * FROM shop_member WHERE account_id=?", merchant.id())).isEqualTo(member);
        assertThat(jdbc.queryForMap("SELECT category_id,campus_code,approval_status,approval_reason,reviewed_by,reviewed_at FROM shop WHERE id=?", merchant.shop())).isEqualTo(review);
        assertThat(audit(merchant)).isEqualTo(audit);
        assertThat(expect(call("GET", PATH, null, token), 200, "OK").path("data")).isEqualTo(closed);
        // Opening records the independent switch and does not require hours or services to be configured.
    }

    @Test void omittedFieldsAndNormalizedNoOpPreserveDataVersionAndUpdatedTime() throws Exception {
        var merchant = approved(); String token = login(merchant); var before = snapshot(merchant);
        expect(patch(Map.of("expectedVersion", "1"), token), 200, "OK");
        var noOp = expect(patch(Map.of("expectedVersion", "1", "name", "  原店铺  ", "locationText", "  虚构校区  ",
                "description", "  原介绍  ", "businessEnabled", false), token), 200, "OK");
        assertThat(noOp.path("data").path("version").asString()).isEqualTo("1");
        assertThat(snapshot(merchant)).isEqualTo(before);
        verify(shops, never()).updateOperation(anyLong(), anyLong(), anyString(), anyString(), anyString(), anyBoolean());
        expect(patch(Map.of("expectedVersion", "0", "name", "原店铺"), token), 409, "CONFIG_VERSION_CONFLICT");
        expect(patch(Map.of("expectedVersion", "0"), token), 409, "CONFIG_VERSION_CONFLICT");
        var changed = expect(patch(Map.of("expectedVersion", "1", "name", "新店名"), token), 200, "OK").path("data");
        assertThat(changed.path("description").asString()).isEqualTo("原介绍");
        assertThat(changed.path("locationText").asString()).isEqualTo("虚构校区");
        assertThat(changed.path("businessEnabled").asBoolean()).isFalse();
        var cleared = expect(patch(Map.of("expectedVersion", "2", "description", "  "), token), 200, "OK").path("data");
        assertThat(cleared.path("description").asString()).isEmpty();
        assertThat(cleared.path("version").asString()).isEqualTo("3");
        expect(patch(Map.of("expectedVersion", "3", "description", ""), token), 200, "OK");
        assertThat(jdbc.queryForObject("SELECT version FROM shop WHERE id=?", Long.class, merchant.shop())).isEqualTo(3);
    }

    @ParameterizedTest @MethodSource("invalidBodies")
    void invalidFieldsCannotChangeShop(String field, Object value, boolean remove) throws Exception {
        var merchant = approved(); String token = login(merchant); var before = snapshot(merchant);
        var body = new LinkedHashMap<String, Object>(); body.put("expectedVersion", "1");
        if (remove) { body.remove(field); } else { body.put(field, value); }
        expect(patch(body, token), 400, "VALIDATION_FAILED");
        assertThat(snapshot(merchant)).isEqualTo(before);
    }
    static Stream<Arguments> invalidBodies() {
        return Stream.of(
                Arguments.of("expectedVersion", null, true), Arguments.of("expectedVersion", null, false),
                Arguments.of("expectedVersion", 1, false), Arguments.of("expectedVersion", "01", false),
                Arguments.of("expectedVersion", "-1", false), Arguments.of("expectedVersion", "+1", false),
                Arguments.of("expectedVersion", " 1", false), Arguments.of("expectedVersion", "9223372036854775808", false),
                Arguments.of("name", null, false), Arguments.of("name", 12, false), Arguments.of("name", true, false),
                Arguments.of("name", List.of("bad"), false), Arguments.of("name", Map.of("bad", "value"), false),
                Arguments.of("name", " ", false), Arguments.of("name", "😀".repeat(81), false), Arguments.of("name", "\uD800", false),
                Arguments.of("locationText", null, false), Arguments.of("locationText", false, false),
                Arguments.of("locationText", " ", false), Arguments.of("locationText", "位".repeat(201), false),
                Arguments.of("locationText", "\uD800", false), Arguments.of("description", null, false),
                Arguments.of("description", 123, false), Arguments.of("description", "介".repeat(1001), false),
                Arguments.of("description", "\uD800", false), Arguments.of("businessEnabled", null, false),
                Arguments.of("businessEnabled", "true", false), Arguments.of("businessEnabled", 1, false),
                Arguments.of("businessEnabled", 0, false), Arguments.of("businessEnabled", List.of(true), false),
                Arguments.of("shopId", "1", false), Arguments.of("accountId", "1", false),
                Arguments.of("categoryId", "1", false), Arguments.of("campusCode", "XINGHAI_MAIN", false),
                Arguments.of("approvalStatus", "APPROVED", false), Arguments.of("reviewedBy", "1", false),
                Arguments.of("role", "ADMIN", false), Arguments.of("shopName", "别名店名", false));
    }

    @Test void pendingRejectedSuspendedAndInactiveMembersCannotChangeOrUseNoOp() throws Exception {
        var merchant = merchant(); String token = login(merchant);
        for (String state : List.of("PENDING", "REJECTED", "SUSPENDED")) {
            jdbc.update("UPDATE shop SET approval_status=? WHERE id=?", state, merchant.shop());
            var before = snapshot(merchant);
            expect(patch(Map.of("expectedVersion", "0", "businessEnabled", true), token), 403, "SHOP_NOT_APPROVED");
            expect(patch(Map.of("expectedVersion", "0"), token), 403, "SHOP_NOT_APPROVED");
            assertThat(snapshot(merchant)).isEqualTo(before);
        }
        jdbc.update("UPDATE shop SET approval_status='APPROVED' WHERE id=?", merchant.shop());
        for (String member : List.of("PENDING", "DISABLED")) {
            jdbc.update("UPDATE shop_member SET status=? WHERE account_id=?", member, merchant.id());
            var before = snapshot(merchant);
            expect(patch(Map.of("expectedVersion", "0", "businessEnabled", false), token), 403, "ROLE_FORBIDDEN");
            expect(patch(Map.of("expectedVersion", "0"), token), 403, "ROLE_FORBIDDEN");
            assertThat(snapshot(merchant)).isEqualTo(before);
        }
    }

    @Test void authenticationRolesAndOwnershipAreEnforced() throws Exception {
        var merchant = approved(); var other = approved(); String token = login(merchant); var otherBefore = snapshot(other);
        expect(patch(Map.of("expectedVersion", "1"), null), 401, "AUTH_REQUIRED");
        expect(patch(Map.of("expectedVersion", "1"), adminToken()), 403, "ROLE_FORBIDDEN");
        String student = name();
        expect(call("POST", "/api/v1/auth/register", Map.of("username", student, "password", PASSWORD, "nickname", "学生"), null), 201, "OK");
        expect(patch(Map.of("expectedVersion", "1"), login(new User("", student, null))), 403, "ROLE_FORBIDDEN");
        jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?", merchant.id());
        expect(patch(Map.of("expectedVersion", "1"), token), 403, "ACCOUNT_DISABLED");
        jdbc.update("UPDATE account SET status='ACTIVE' WHERE id=?", merchant.id());
        expect(call("POST", "/api/v1/auth/logout", null, token), 200, "OK");
        expect(patch(Map.of("expectedVersion", "1"), token), 401, "SESSION_REVOKED");
        expect(patch(Map.of("expectedVersion", "1", "name", "本人店铺新名称"), login(merchant)), 200, "OK");
        assertThat(snapshot(other)).isEqualTo(otherBefore);
        jdbc.update("DELETE FROM shop_member WHERE account_id=?", merchant.id());
        try { expect(patch(Map.of("expectedVersion", "2"), login(merchant)), 404, "RESOURCE_NOT_FOUND"); }
        finally { jdbc.update("INSERT INTO shop_member (shop_id,account_id,status) VALUES (?,?,'ACTIVE')", merchant.shop(), merchant.id()); }
    }

    @Test void lockedAccountAndSessionOverrideStalePrincipal() throws Exception {
        var merchant = approved(); String token = login(merchant); var before = snapshot(merchant);
        String jti = json.readTree(new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8)).path("jti").asString();
        var principal = new AuthPrincipal(Long.parseLong(merchant.id()), jti, new AccountView(merchant.id(), merchant.name(), "店主", "MERCHANT", "ACTIVE", "0"));
        var request = json.convertValue(Map.of("expectedVersion", "1", "businessEnabled", true), ShopPatchRequest.class);
        jdbc.update("UPDATE account SET role='STUDENT' WHERE id=?", merchant.id());
        assertBusiness(() -> operations.patch(principal, request), "ROLE_FORBIDDEN");
        jdbc.update("UPDATE account SET role='MERCHANT',status='DISABLED' WHERE id=?", merchant.id());
        assertBusiness(() -> operations.patch(principal, request), "ACCOUNT_DISABLED");
        jdbc.update("UPDATE account SET status='ACTIVE' WHERE id=?", merchant.id());
        jdbc.update("UPDATE auth_session SET revoked_at=created_at WHERE account_id=? AND jti=?", merchant.id(), jti);
        assertBusiness(() -> operations.patch(principal, request), "SESSION_REVOKED");
        assertThat(snapshot(merchant)).isEqualTo(before);
    }

    @Test void versionLimitAllowsNoOpButRejectsActualMutation() throws Exception {
        var merchant = approved(); String token = login(merchant);
        jdbc.update("UPDATE shop SET version=? WHERE id=?", Long.MAX_VALUE, merchant.shop()); var before = snapshot(merchant);
        String max = Long.toString(Long.MAX_VALUE);
        assertThat(expect(patch(Map.of("expectedVersion", max, "name", " 原店铺 "), token), 200, "OK").path("data").path("version").asString()).isEqualTo(max);
        expect(patch(Map.of("expectedVersion", max, "businessEnabled", true), token), 409, "CONFIG_VERSION_CONFLICT");
        assertThat(snapshot(merchant)).isEqualTo(before);
    }

    @Test void concurrentMutationsOnlyCommitOneVersionAndOldNoOpStillConflicts() throws Exception {
        var merchant = approved(); String a = login(merchant), b = login(merchant); var audit = audit(merchant);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var x = pool.submit(() -> { start.await(); return patch(Map.of("expectedVersion", "1", "name", "修改 A", "businessEnabled", true), a); });
            var y = pool.submit(() -> { start.await(); return patch(Map.of("expectedVersion", "1", "name", "修改 B", "description", "修改 B 介绍"), b); });
            start.countDown();
            var rx = x.get(30, TimeUnit.SECONDS); var ry = y.get(30, TimeUnit.SECONDS);
            assertThat(List.of(rx.statusCode(), ry.statusCode())).containsExactlyInAnyOrder(200, 409);
            var row = jdbc.queryForMap("SELECT name,description,business_enabled,version FROM shop WHERE id=?", merchant.shop());
            assertThat(row.get("version").toString()).isEqualTo("2");
            if (rx.statusCode() == 200) {
                assertThat(row.get("name")).isEqualTo("修改 A"); assertThat(row.get("description")).isEqualTo("原介绍"); assertThat(row.get("business_enabled")).isEqualTo(true);
            } else {
                assertThat(row.get("name")).isEqualTo("修改 B"); assertThat(row.get("description")).isEqualTo("修改 B 介绍"); assertThat(row.get("business_enabled")).isEqualTo(false);
            }
        }
        assertThat(audit(merchant)).isEqualTo(audit);
        expect(patch(Map.of("expectedVersion", "1"), a), 409, "CONFIG_VERSION_CONFLICT");
    }

    @Test void concurrentNoOpsDoNotCreateFalseVersionConflict() throws Exception {
        var merchant = approved(); String a = login(merchant), b = login(merchant); var before = snapshot(merchant);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var x = pool.submit(() -> { start.await(); return patch(Map.of("expectedVersion", "1", "businessEnabled", false), a); });
            var y = pool.submit(() -> { start.await(); return patch(Map.of("expectedVersion", "1"), b); });
            start.countDown(); expect(x.get(30, TimeUnit.SECONDS), 200, "OK"); expect(y.get(30, TimeUnit.SECONDS), 200, "OK");
        }
        assertThat(snapshot(merchant)).isEqualTo(before);
    }

    @Test void realUpdateThenFailureRollsBackEveryFieldAndVersion() throws Exception {
        var merchant = approved(); String token = login(merchant); var before = snapshot(merchant);
        var real = sqlSession.getMapper(ShopMapper.class);
        doAnswer(inv -> {
            assertThat(real.updateOperation(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), inv.getArgument(3), inv.getArgument(4), inv.getArgument(5))).isEqualTo(1);
            throw new IllegalStateException("Injected after actual shop update");
        }).when(shops).updateOperation(anyLong(), anyLong(), anyString(), anyString(), anyString(), anyBoolean());
        expect(patch(Map.of("expectedVersion", "1", "name", "事务回滚店名", "locationText", "新位置", "description", "新介绍", "businessEnabled", true), token), 500, "INTERNAL_ERROR");
        assertThat(snapshot(merchant)).isEqualTo(before);
    }

    @Test void expiredSessionAtWriteBoundaryCannotChangeShop() throws Exception {
        var merchant = approved(); String token = login(merchant); var before = snapshot(merchant); long id = Long.parseLong(merchant.shop());
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1); var real = sqlSession.getMapper(ShopMapper.class);
        doAnswer(inv -> { var row = real.lock(id); locked.countDown();
            if (!release.await(15, TimeUnit.SECONDS)) { throw new IllegalStateException("Test gate timeout"); } return row;
        }).when(shops).lock(id);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var response = pool.submit(() -> patch(Map.of("expectedVersion", "1", "businessEnabled", true), token));
            try { assertThat(locked.await(15, TimeUnit.SECONDS)).isTrue(); doReturn(java.time.Instant.now().plusSeconds(7201)).when(clock).instant(); }
            finally { release.countDown(); }
            expect(response.get(20, TimeUnit.SECONDS), 401, "SESSION_EXPIRED");
        }
        assertThat(snapshot(merchant)).isEqualTo(before);
    }

    @Test void noOpReturnsLockedCurrentStateAfterConcurrentApprovalInsteadOfOldSnapshot() throws Exception {
        var merchant = merchant(); String token = login(merchant); String admin = adminToken(); long id = Long.parseLong(merchant.shop());
        CountDownLatch reading = new CountDownLatch(1), release = new CountDownLatch(1); var real = sqlSession.getMapper(ShopMapper.class);
        doAnswer(inv -> { reading.countDown();
            if (!release.await(15, TimeUnit.SECONDS)) { throw new IllegalStateException("Test gate timeout"); } return real.lock(id);
        }).when(shops).lock(id);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var response = pool.submit(() -> patch(Map.of("expectedVersion", "1"), token));
            try {
                assertThat(reading.await(15, TimeUnit.SECONDS)).isTrue();
                approve(merchant, admin);
            } finally { release.countDown(); }
            var data = expect(response.get(20, TimeUnit.SECONDS), 200, "OK").path("data");
            assertThat(data.path("version").asString()).isEqualTo("1");
            assertThat(data.path("approvalStatus").asString()).isEqualTo("APPROVED");
            assertThat(data.path("memberStatus").asString()).isEqualTo("ACTIVE");
            assertThat(data.path("businessEnabled").asBoolean()).isFalse();
        }
        verify(shops, never()).updateOperation(anyLong(), anyLong(), anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test void actualShopLockWaitRechecksSuspendedStateRatherThanSnapshot() throws Exception {
        var merchant = approved(); String token = login(merchant); long id = Long.parseLong(merchant.shop());
        CountDownLatch held = new CountDownLatch(1), ownershipRead = new CountDownLatch(1), release = new CountDownLatch(1);
        var real = sqlSession.getMapper(ShopMapper.class);
        doAnswer(inv -> { var row = real.findOwn(Long.parseLong(merchant.id())); ownershipRead.countDown(); return row; })
                .when(shops).findOwn(Long.parseLong(merchant.id()));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var suspension = pool.submit(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM shop WHERE id=? FOR UPDATE", Long.class, id); held.countDown();
                try { if (!release.await(15, TimeUnit.SECONDS)) { throw new IllegalStateException("Test gate timeout"); } }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
                jdbc.update("UPDATE shop SET approval_status='SUSPENDED',version=version+1 WHERE id=?", id);
            }));
            try {
                assertThat(held.await(15, TimeUnit.SECONDS)).isTrue();
                var response = pool.submit(() -> patch(Map.of("expectedVersion", "2", "businessEnabled", true), token));
                try {
                    assertThat(ownershipRead.await(15, TimeUnit.SECONDS)).isTrue();
                    assertThatThrownBy(() -> response.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                } finally { release.countDown(); }
                suspension.get(20, TimeUnit.SECONDS);
                expect(response.get(20, TimeUnit.SECONDS), 403, "SHOP_NOT_APPROVED");
            } finally { release.countDown(); }
        }
        assertThat(jdbc.queryForObject("SELECT business_enabled FROM shop WHERE id=?", Boolean.class, id)).isFalse();
        assertThat(jdbc.queryForObject("SELECT version FROM shop WHERE id=?", Long.class, id)).isEqualTo(2);
    }

    private void assertBusiness(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String code) {
        assertThatThrownBy(action).isInstanceOf(BusinessException.class).satisfies(e -> assertThat(((BusinessException) e).code()).isEqualTo(code));
    }
    private record User(String id, String name, String shop) {}
    private String adminToken() throws Exception {
        if (reviewer == null) { String name = name(); reviewer = new User(new AdminAccountInitializer(accounts, passwords).create(name, "管理员", PASSWORD), name, null); reviewerToken = login(reviewer); }
        return reviewerToken;
    }
    private User merchant() throws Exception {
        String name = name(); String category = jdbc.queryForObject("SELECT id FROM shop_category WHERE code='HAIRCUT'", String.class);
        var data = expect(call("POST", "/api/v1/merchant-applications", Map.of("username", name, "password", PASSWORD, "nickname", "店主", "categoryId", category,
                "shopName", "原店铺", "locationText", "虚构校区", "description", "原介绍"), null), 201, "OK").path("data");
        return new User(data.path("accountId").asString(), name, data.path("shopId").asString());
    }
    private User approved() throws Exception { var merchant = merchant(); approve(merchant, adminToken()); return merchant; }
    private void approve(User merchant, String token) throws Exception { expect(call("POST", "/api/v1/admin/shops/" + merchant.shop() + "/approve", Map.of("expectedVersion", "0"), token), 200, "OK"); }
    private List<?> audit(User merchant) { return jdbc.queryForList("SELECT * FROM shop_review WHERE shop_id=? ORDER BY id", merchant.shop()); }
    private List<?> snapshot(User merchant) { return List.of(jdbc.queryForMap("SELECT * FROM shop WHERE id=?", merchant.shop()), jdbc.queryForMap("SELECT * FROM shop_member WHERE account_id=?", merchant.id()), audit(merchant)); }
    private String name() { String name = "op_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20); names.add(name); return name; }
    private String login(User user) throws Exception { return expect(call("POST", "/api/v1/auth/login", Map.of("username", user.name(), "password", PASSWORD), null), 200, "OK").path("data").path("accessToken").asString(); }
    private HttpResponse<String> patch(Object body, String token) throws Exception { return call("PATCH", PATH, body, token); }
    private JsonNode expect(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status); var body = json.readTree(response.body()); assertThat(body.path("code").asString()).isEqualTo(code); return body;
    }
    private HttpResponse<String> call(String method, String path, Object body, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + environment.getRequiredProperty("local.server.port") + path)).timeout(Duration.ofSeconds(25));
        if (token != null) { request.header("Authorization", "Bearer " + token); }
        if (body != null) { request.header("Content-Type", "application/json"); }
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body).replace("\uD800", "\\uD800"), StandardCharsets.UTF_8));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
