package com.campuslife;

import com.campuslife.auth.entity.AccountEntity;
import com.campuslife.auth.mapper.AccountMapper;
import com.campuslife.auth.security.AuthPrincipal;
import com.campuslife.auth.vo.AccountView;
import com.campuslife.common.exception.BusinessException;
import com.campuslife.merchant.dto.PrintPricesRequest;
import com.campuslife.merchant.entity.PrintPriceRuleEntity;
import com.campuslife.merchant.entity.ServiceItemEntity;
import com.campuslife.merchant.entity.ShopEntity;
import com.campuslife.merchant.mapper.PrintPriceRuleMapper;
import com.campuslife.merchant.mapper.ServiceItemMapper;
import com.campuslife.merchant.mapper.ShopMapper;
import com.campuslife.merchant.service.PrintPriceService;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
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
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PrintPricesMysqlIT {
    private static final String PASSWORD = " Price_密码1! ";
    private final HttpClient client = HttpClient.newHttpClient();
    private final List<Long> accountIds = new ArrayList<>(), shopIds = new ArrayList<>();
    private String hash;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired Environment environment;
    @Autowired PasswordEncoder passwords;
    @Autowired ServiceItemMapper services;
    @Autowired PrintPriceService pricing;
    @Autowired PlatformTransactionManager manager;
    @Autowired org.mybatis.spring.SqlSessionTemplate sqlSession;
    @MockitoSpyBean AccountMapper accounts;
    @MockitoSpyBean ShopMapper shops;
    @MockitoSpyBean PrintPriceRuleMapper prices;
    @MockitoSpyBean Clock clock;

    @BeforeAll void prepareSyntheticHash() { hash = passwords.encode(PASSWORD); }
    @AfterEach void cleanup() {
        reset(accounts, shops, prices, clock);
        for (long shop : shopIds) {
            jdbc.update("DELETE p FROM print_price_rule p JOIN service_item s ON s.id=p.service_id WHERE s.shop_id=?", shop);
            jdbc.update("DELETE FROM service_item WHERE shop_id=?", shop);
            jdbc.update("DELETE FROM shop_business_hour WHERE shop_id=?", shop);
            jdbc.update("DELETE FROM shop_review WHERE shop_id=?", shop);
            jdbc.update("DELETE FROM shop_member WHERE shop_id=?", shop);
            jdbc.update("DELETE FROM shop WHERE id=?", shop);
        }
        for (long id : accountIds) { jdbc.update("DELETE FROM auth_session WHERE account_id=?", id); }
        for (long id : accountIds) { jdbc.update("DELETE FROM account WHERE id=?", id); }
        accountIds.clear(); shopIds.clear();
    }

    @Test void createsFourSortedSpecsWhileShopClosedAndServiceDisabled() throws Exception {
        var user = merchant("PRINT"); String token = login(user); var parent = parentSnapshot(user);
        assertThat(expect(get(user, token), 200, "OK").path("data").isEmpty()).isTrue();
        var response = put(user, List.of(rule("COLOR", "DOUBLE", 100000, false, null),
                rule("BW", "DOUBLE", 20, true, null), rule("COLOR", "SINGLE", 50, true, null),
                rule("BW", "SINGLE", 1, true, null)), token);
        var data = expect(response, 200, "OK").path("data");
        assertThat(data.size()).isEqualTo(4);
        assertThat(specs(data)).containsExactly("BW/SINGLE", "BW/DOUBLE", "COLOR/SINGLE", "COLOR/DOUBLE");
        assertThat(data.get(0).path("pricePerImpression").asLong()).isEqualTo(1);
        assertThat(data.get(3).path("pricePerImpression").asLong()).isEqualTo(100000);
        assertThat(data.get(3).path("enabled").asBoolean()).isFalse();
        for (var row : data) {
            assertThat(row.path("id").isString()).isTrue();
            assertThat(row.path("version").asString()).isEqualTo("0");
            assertThat(row.size()).isEqualTo(7);
        }
        assertThat(expect(get(user, token), 200, "OK").path("data")).isEqualTo(data);
        assertThat(parentSnapshot(user)).isEqualTo(parent);
        expect(call("POST", path(user), Map.of("rules", List.of(rule(null))), token), 405, "METHOD_NOT_ALLOWED");
        expect(call("DELETE", path(user), null, token), 405, "METHOD_NOT_ALLOWED");
    }

    @Test void updatesOneSpecRetainsOthersAndNoopDoesNotWriteOrBumpParents() throws Exception {
        var user = merchant("PRINT"); String token = login(user); var parent = parentSnapshot(user);
        putOk(user, List.of(rule(null), rule("COLOR", "DOUBLE", 80, false, null)), token);
        var untouched = rows(user).get(1);
        var data = expect(put(user, List.of(rule("BW", "SINGLE", 30, false, "0")), token), 200, "OK").path("data");
        assertThat(data.size()).isEqualTo(2);
        assertThat(data.get(0).path("version").asString()).isEqualTo("1");
        assertThat(data.get(0).path("enabled").asBoolean()).isFalse();
        assertThat(rows(user).get(1)).isEqualTo(untouched);
        var before = rows(user); reset(prices);
        putOk(user, List.of(rule("BW", "SINGLE", 30, false, "1")), token);
        verify(prices, never()).updateOwn(anyLong(), anyLong(), anyLong(), anyLong(), anyBoolean());
        verify(prices, never()).insert(any(PrintPriceRuleEntity.class));
        assertThat(rows(user)).isEqualTo(before);
        expect(put(user, List.of(rule("BW", "SINGLE", 30, false, "0")), token), 409, "CONFIG_VERSION_CONFLICT");
        assertThat(parentSnapshot(user)).isEqualTo(parent);
    }

    @Test void newExistingMissingAndMaxVersionsHaveExplicitConflictSemantics() throws Exception {
        var user = merchant("PRINT"); String token = login(user);
        expect(put(user, List.of(rule("0")), token), 409, "CONFIG_VERSION_CONFLICT");
        assertThat(rows(user)).isEmpty(); putOk(user, List.of(rule(null)), token);
        var before = rows(user);
        expect(put(user, List.of(rule(null)), token), 409, "CONFIG_VERSION_CONFLICT");
        assertThat(rows(user)).isEqualTo(before);
        jdbc.update("UPDATE print_price_rule SET version=? WHERE service_id=?", Long.MAX_VALUE, user.service());
        var max = rows(user);
        putOk(user, List.of(rule(Long.toString(Long.MAX_VALUE))), token);
        assertThat(rows(user)).isEqualTo(max);
        expect(put(user, List.of(rule("BW", "SINGLE", 21, true, Long.toString(Long.MAX_VALUE)),
                rule("COLOR", "DOUBLE", 80, true, null)), token), 409, "CONFIG_VERSION_CONFLICT");
        assertThat(rows(user)).isEqualTo(max);
    }

    @Test void checksAllVersionsBeforeWritingMixedBatch() throws Exception {
        var user = merchant("PRINT"); String token = login(user);
        putOk(user, List.of(rule(null), rule("COLOR", "DOUBLE", 80, true, null)), token);
        var before = rows(user); reset(prices);
        expect(put(user, List.of(rule("BW", "SINGLE", 40, false, "0"),
                rule("BW", "DOUBLE", 25, true, null), rule("COLOR", "DOUBLE", 90, false, "9")), token), 409, "CONFIG_VERSION_CONFLICT");
        assertThat(rows(user)).isEqualTo(before);
        verify(prices, never()).insert(any(PrintPriceRuleEntity.class));
        verify(prices, never()).updateOwn(anyLong(), anyLong(), anyLong(), anyLong(), anyBoolean());
    }

    static Stream<Arguments> invalidRules() {
        var cases = new ArrayList<Arguments>();
        for (String field : rule(null).keySet()) {
            var omitted = rule(null); omitted.remove(field); cases.add(Arguments.of("missing " + field, omitted));
            if (!field.equals("expectedVersion")) {
                var nulled = rule(null); nulled.put(field, null); cases.add(Arguments.of("null " + field, nulled));
            }
        }
        Map<String, List<Object>> invalid = Map.of(
                "paperSize", List.of("A3", "a4", "A4 ", 4, List.of("A4")),
                "colorMode", List.of("bw", " BW", "GRAY", true),
                "sideMode", List.of("double", "DUPLEX", 2),
                "pricePerImpression", List.of(0, -1, 100001, Long.MAX_VALUE, 20.0, "20", true, List.of(20), Map.of("x", 20)),
                "enabled", List.of("true", 1, 0, List.of(true)),
                "expectedVersion", List.of(0, false, "-1", "00", " 0", "1.0", "9223372036854775808", Map.of("v", 0)));
        invalid.forEach((field, values) -> values.forEach(value -> {
            var body = rule(null); body.put(field, value); cases.add(Arguments.of(field + "=" + value, body));
        }));
        for (String field : List.of("id", "shopId", "serviceId", "version", "extra")) {
            var body = rule(null); body.put(field, "1"); cases.add(Arguments.of("unknown " + field, body));
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}") @MethodSource("invalidRules")
    void rejectsInvalidRuleWithoutPersistingAnyPart(String description, Map<String, Object> bad) throws Exception {
        var user = merchant("PRINT"); String token = login(user);
        expect(put(user, List.of(rule("COLOR", "DOUBLE", 80, true, null), bad), token), 400, "VALIDATION_FAILED");
        assertThat(rows(user)).isEmpty();
    }

    static Stream<Arguments> invalidBodies() {
        return Stream.of(Arguments.of("missing", Map.of()), Arguments.of("null rules", Collections.singletonMap("rules", null)),
                Arguments.of("empty", Map.of("rules", List.of())), Arguments.of("object", Map.of("rules", rule(null))),
                Arguments.of("string", Map.of("rules", "rules")), Arguments.of("null entry", Map.of("rules", Arrays.asList((Object) null))),
                Arguments.of("number entry", Map.of("rules", List.of(1))), Arguments.of("root array", List.of(rule(null))),
                Arguments.of("extra root", Map.of("rules", List.of(rule(null)), "shopId", "1")),
                Arguments.of("five", Map.of("rules", Collections.nCopies(5, rule(null)))),
                Arguments.of("duplicate", Map.of("rules", List.of(rule(null), rule("BW", "SINGLE", 99, false, null)))));
    }

    @ParameterizedTest(name = "{0}") @MethodSource("invalidBodies")
    void rejectsInvalidBatchShape(String description, Object body) throws Exception {
        var user = merchant("PRINT"); String token = login(user);
        expect(call("PUT", path(user), body, token), 400, "VALIDATION_FAILED"); assertThat(rows(user)).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings = {"0", "01", "-1", "abc", "9223372036854775808", "99999999999999999999"})
    void rejectsMalformedServiceIds(String id) throws Exception {
        var user = merchant("PRINT"); String token = login(user); String path = "/api/v1/merchant/services/" + id + "/print-prices";
        expect(call("GET", path, null, token), 400, "VALIDATION_FAILED");
        expect(call("PUT", path, Map.of("rules", List.of(rule(null))), token), 400, "VALIDATION_FAILED");
    }

    @Test void scopesReadsAndWritesToApprovedOwnerAndOwnPrintService() throws Exception {
        var user = merchant("PRINT"); String token = login(user); var foreign = merchant("PRINT"); var hair = merchant("HAIRCUT");
        putOk(foreign, List.of(rule(null)), login(foreign));
        for (String method : List.of("GET", "PUT")) {
            Object body = method.equals("PUT") ? Map.of("rules", List.of(rule(null))) : null;
            expect(call(method, path(user), body, null), 401, "AUTH_REQUIRED");
            for (String role : List.of("STUDENT", "ADMIN")) {
                expect(call(method, path(user), body, login(account(role))), 403, "ROLE_FORBIDDEN");
            }
            expect(call(method, path(user), body, login(account("MERCHANT"))), 404, "RESOURCE_NOT_FOUND");
            expect(call(method, path(foreign), body, token), 404, "RESOURCE_NOT_FOUND");
            expect(call(method, path(hair), body, token), 404, "RESOURCE_NOT_FOUND");
            expect(call(method, path(hair), body, login(hair)), 400, "VALIDATION_FAILED");
            expect(call(method, "/api/v1/merchant/services/9223372036854775807/print-prices", body, token), 404, "RESOURCE_NOT_FOUND");
        }
        for (String state : List.of("PENDING", "REJECTED", "SUSPENDED")) {
            jdbc.update("UPDATE shop SET approval_status=? WHERE id=?", state, user.shop());
            expect(get(user, token), 403, "SHOP_NOT_APPROVED");
            expect(put(user, List.of(rule(null)), token), 403, "SHOP_NOT_APPROVED");
        }
        jdbc.update("UPDATE shop SET approval_status='APPROVED' WHERE id=?", user.shop());
        for (String state : List.of("PENDING", "DISABLED")) {
            jdbc.update("UPDATE shop_member SET status=? WHERE account_id=?", state, user.id());
            expect(get(user, token), 403, "ROLE_FORBIDDEN"); expect(put(user, List.of(rule(null)), token), 403, "ROLE_FORBIDDEN");
        }
        jdbc.update("UPDATE shop_member SET status='ACTIVE' WHERE account_id=?", user.id());
        jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?", user.id());
        expect(get(user, token), 403, "ACCOUNT_DISABLED"); expect(put(user, List.of(rule(null)), token), 403, "ACCOUNT_DISABLED");
        jdbc.update("UPDATE account SET status='ACTIVE' WHERE id=?", user.id());
        expect(call("POST", "/api/v1/auth/logout", null, token), 200, "OK");
        expect(put(user, List.of(rule(null)), token), 401, "SESSION_REVOKED");
        assertThat(rows(user)).isEmpty(); assertThat(rows(foreign)).hasSize(1);
    }

    @Test void concurrentNewSpecHasOneWinnerAndNoDuplicate() throws Exception {
        var user = merchant("PRINT"); String a = login(user), b = login(user); var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var p = pool.submit(() -> { await(start); return put(user, List.of(rule(null)), a); });
            var q = pool.submit(() -> { await(start); return put(user, List.of(rule(null)), b); });
            start.countDown();
            assertThat(List.of(p.get(30, TimeUnit.SECONDS).statusCode(), q.get(30, TimeUnit.SECONDS).statusCode()))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(rows(user)).hasSize(1); assertThat(rows(user).getFirst().get("version").toString()).isEqualTo("0");
    }

    @Test void differentShopsCanCreateFirstRulesWithoutEmptyRangeGapLockDeadlock() throws Exception {
        var first = merchant("PRINT"); var second = merchant("PRINT"); String a = login(first), b = login(second);
        var reads = new CountDownLatch(2); var calls = new AtomicInteger(); var real = sqlSession.getMapper(PrintPriceRuleMapper.class);
        doAnswer(inv -> {
            assertThat(jdbc.queryForObject("SELECT @@transaction_isolation", String.class)).isEqualTo("READ-COMMITTED");
            var current = real.current(inv.getArgument(0));
            if (calls.incrementAndGet() <= 2) { reads.countDown(); await(reads); }
            return current;
        }).when(prices).current(anyLong());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var p = pool.submit(() -> put(first, List.of(rule(null)), a));
            var q = pool.submit(() -> put(second, List.of(rule(null)), b));
            expect(p.get(30, TimeUnit.SECONDS), 200, "OK"); expect(q.get(30, TimeUnit.SECONDS), 200, "OK");
        }
        assertThat(rows(first)).hasSize(1); assertThat(rows(second)).hasSize(1);
    }

    @Test void sameRuleUpdatesConflictButDifferentRuleVersionsRemainIndependent() throws Exception {
        var user = merchant("PRINT"); String a = login(user), b = login(user);
        putOk(user, List.of(rule(null), rule("COLOR", "DOUBLE", 80, true, null)), a);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var p = pool.submit(() -> { await(start); return put(user, List.of(rule("BW", "SINGLE", 30, false, "0")), a); });
            var q = pool.submit(() -> { await(start); return put(user, List.of(rule("BW", "SINGLE", 40, true, "0")), b); });
            start.countDown(); var first = p.get(30, TimeUnit.SECONDS); var second = q.get(30, TimeUnit.SECONDS);
            assertThat(List.of(first.statusCode(), second.statusCode())).containsExactlyInAnyOrder(200, 409);
            var row = rows(user).getFirst();
            assertThat(row.get("price_per_impression").toString()).isEqualTo(first.statusCode() == 200 ? "30" : "40");
            assertThat(row.get("enabled").toString()).isEqualTo(first.statusCode() == 200 ? "false" : "true");
        }
        try (var pool = Executors.newFixedThreadPool(2)) {
            var p = pool.submit(() -> put(user, List.of(rule("BW", "SINGLE", 45, true, "1")), a));
            var q = pool.submit(() -> put(user, List.of(rule("COLOR", "DOUBLE", 90, false, "0")), b));
            expect(p.get(30, TimeUnit.SECONDS), 200, "OK"); expect(q.get(30, TimeUnit.SECONDS), 200, "OK");
        }
        assertThat(rows(user).getFirst().get("version").toString()).isEqualTo("2");
        assertThat(rows(user).get(1).get("version").toString()).isEqualTo("1");
    }

    @Test void failuresAfterRealInsertsAndUpdatesRollBackWholeBatch() throws Exception {
        var user = merchant("PRINT"); String token = login(user); var parent = parentSnapshot(user);
        var real = sqlSession.getMapper(PrintPriceRuleMapper.class); var writes = new AtomicInteger();
        doAnswer(inv -> {
            int count = real.insert((PrintPriceRuleEntity) inv.getArgument(0));
            if (writes.incrementAndGet() == 2) { throw new IllegalStateException("Injected after second actual insert"); }
            return count;
        }).when(prices).insert(any(PrintPriceRuleEntity.class));
        expect(put(user, List.of(rule(null), rule("COLOR", "DOUBLE", 80, true, null)), token), 500, "INTERNAL_ERROR");
        assertThat(rows(user)).isEmpty(); reset(prices); putOk(user, List.of(rule(null)), token);
        var before = rows(user);
        doAnswer(inv -> { real.insert((PrintPriceRuleEntity) inv.getArgument(0)); throw new IllegalStateException("Injected after real insert following update"); })
                .when(prices).insert(any(PrintPriceRuleEntity.class));
        expect(put(user, List.of(rule("BW", "SINGLE", 35, false, "0"), rule("COLOR", "DOUBLE", 80, true, null)), token), 500, "INTERNAL_ERROR");
        assertThat(rows(user)).isEqualTo(before); reset(prices);
        doAnswer(inv -> {
            real.updateOwn(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), inv.getArgument(3), inv.getArgument(4));
            throw new IllegalStateException("Injected after actual price update");
        }).when(prices).updateOwn(anyLong(), anyLong(), anyLong(), anyLong(), anyBoolean());
        expect(put(user, List.of(rule("BW", "SINGLE", 35, false, "0")), token), 500, "INTERNAL_ERROR");
        assertThat(rows(user)).isEqualTo(before); assertThat(parentSnapshot(user)).isEqualTo(parent);
    }

    @Test void sessionExpiryAfterFirstRealWriteRollsBack() throws Exception {
        var user = merchant("PRINT"); String token = login(user); var real = sqlSession.getMapper(PrintPriceRuleMapper.class);
        doAnswer(inv -> {
            int count = real.insert((PrintPriceRuleEntity) inv.getArgument(0));
            doReturn(Instant.now().plusSeconds(7201)).when(clock).instant(); return count;
        }).when(prices).insert(any(PrintPriceRuleEntity.class));
        expect(put(user, List.of(rule(null), rule("COLOR", "DOUBLE", 80, true, null)), token), 401, "SESSION_EXPIRED");
        assertThat(rows(user)).isEmpty();
    }

    @Test void freshDatabaseAccountAndSessionOverridePrincipalSnapshot() throws Exception {
        var user = merchant("PRINT"); String token = login(user);
        String jti = json.readTree(new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8)).path("jti").asString();
        var principal = new AuthPrincipal(user.id(), jti, new AccountView(Long.toString(user.id()), user.name(), "店主", "MERCHANT", "ACTIVE", "0"));
        var body = json.convertValue(Map.of("rules", List.of(rule(null))), PrintPricesRequest.class);
        jdbc.update("UPDATE account SET role='STUDENT' WHERE id=?", user.id());
        assertBusiness(() -> pricing.save(principal, Long.toString(user.service()), body), "ROLE_FORBIDDEN");
        jdbc.update("UPDATE account SET role='MERCHANT',status='DISABLED' WHERE id=?", user.id());
        assertBusiness(() -> pricing.save(principal, Long.toString(user.service()), body), "ACCOUNT_DISABLED");
        jdbc.update("UPDATE account SET status='ACTIVE' WHERE id=?", user.id());
        jdbc.update("UPDATE auth_session SET revoked_at=created_at WHERE account_id=? AND jti=?", user.id(), jti);
        assertBusiness(() -> pricing.save(principal, Long.toString(user.service()), body), "SESSION_REVOKED");
        assertThat(rows(user)).isEmpty();
    }

    @Test void shopLockWaitRechecksApprovalBeforeWriting() throws Exception {
        var user = merchant("PRINT"); String token = login(user);
        waitOnParent(user, token, "shop", user.shop(), () -> jdbc.update("UPDATE shop SET approval_status='SUSPENDED' WHERE id=?", user.shop()),
                List.of(rule(null)), 403, "SHOP_NOT_APPROVED");
        assertThat(rows(user)).isEmpty();
    }

    @Test void sessionExpiringDuringShopLockWaitCannotSave() throws Exception {
        var user = merchant("PRINT"); String token = login(user);
        waitOnParent(user, token, "shop", user.shop(), () -> doReturn(Instant.now().plusSeconds(7201)).when(clock).instant(),
                List.of(rule(null)), 401, "SESSION_EXPIRED");
        assertThat(rows(user)).isEmpty();
    }

    @ParameterizedTest @CsvSource({"role,STUDENT,ROLE_FORBIDDEN", "status,DISABLED,ACCOUNT_DISABLED"})
    void accountLockWaitRechecksDatabaseRoleAndStatus(String field, String value, String code) throws Exception {
        var user = merchant("PRINT"); String token = login(user);
        assertThat(Set.of("role", "status")).contains(field);
        var held = new CountDownLatch(1); var reached = new CountDownLatch(1); var release = new CountDownLatch(1);
        var real = sqlSession.getMapper(AccountMapper.class);
        doAnswer(inv -> { reached.countDown(); return real.lockById(user.id()); }).when(accounts).lockById(user.id());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var blocker = pool.submit(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM account WHERE id=? FOR UPDATE", Long.class, user.id());
                held.countDown(); await(release); jdbc.update("UPDATE account SET " + field + "=? WHERE id=?", value, user.id());
            }));
            try {
                assertThat(held.await(15, TimeUnit.SECONDS)).isTrue();
                var response = pool.submit(() -> put(user, List.of(rule(null)), token));
                try {
                    assertThat(reached.await(15, TimeUnit.SECONDS)).isTrue();
                    assertThatThrownBy(() -> response.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                } finally { release.countDown(); }
                blocker.get(20, TimeUnit.SECONDS); expect(response.get(20, TimeUnit.SECONDS), 403, code);
            } finally { release.countDown(); }
        }
        assertThat(rows(user)).isEmpty();
    }

    @Test void serviceLockWaitSeesRuleCreatedAfterReadSnapshot() throws Exception {
        var user = merchant("PRINT"); String token = login(user);
        waitOnParent(user, token, "service_item", user.service(), () -> jdbc.update("INSERT INTO print_price_rule "
                        + "(service_id,paper_size,color_mode,side_mode,price_per_impression,enabled) VALUES (?,'A4','BW','SINGLE',25,TRUE)", user.service()),
                List.of(rule(null)), 409, "CONFIG_VERSION_CONFLICT");
        assertThat(rows(user)).hasSize(1); assertThat(rows(user).getFirst().get("price_per_impression").toString()).isEqualTo("25");
    }

    @Test void priceLockWaitChecksLatestVersionInsteadOfOldSnapshot() throws Exception {
        var user = merchant("PRINT"); String token = login(user); putOk(user, List.of(rule(null)), token);
        long id = Long.parseLong(rows(user).getFirst().get("id").toString());
        waitOnParent(user, token, "print_price_rule", id, () -> jdbc.update("UPDATE print_price_rule SET price_per_impression=25,version=1 WHERE id=?", id),
                List.of(rule("BW", "SINGLE", 30, false, "1")), 200, "OK");
        assertThat(rows(user).getFirst().get("version").toString()).isEqualTo("2");
        assertThat(rows(user).getFirst().get("price_per_impression").toString()).isEqualTo("30");
    }

    private void waitOnParent(User user, String token, String table, long id, Runnable update,
                              List<?> rules, int expectedStatus, String expectedCode) throws Exception {
        // Table names are fixed test constants; never accept request data in this helper.
        assertThat(Set.of("shop", "service_item", "print_price_rule")).contains(table);
        var held = new CountDownLatch(1); var read = new CountDownLatch(1); var release = new CountDownLatch(1);
        var real = sqlSession.getMapper(ShopMapper.class);
        doAnswer(inv -> { var own = real.findOwn(user.id()); read.countDown(); return own; }).when(shops).findOwn(user.id());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var blocker = pool.submit(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM " + table + " WHERE id=? FOR UPDATE", Long.class, id);
                held.countDown(); await(release); update.run();
            }));
            try {
                assertThat(held.await(15, TimeUnit.SECONDS)).isTrue();
                var response = pool.submit(() -> put(user, rules, token));
                try {
                    assertThat(read.await(15, TimeUnit.SECONDS)).isTrue();
                    assertThatThrownBy(() -> response.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                } finally { release.countDown(); }
                blocker.get(20, TimeUnit.SECONDS); expect(response.get(20, TimeUnit.SECONDS), expectedStatus, expectedCode);
            } finally { release.countDown(); }
        }
    }

    @Test void mysqlEnforcesSpecsBoundsUniquenessAndServiceForeignKey() throws Exception {
        var user = merchant("PRINT"); String token = login(user); putOk(user, List.of(rule(null)), token);
        assertSql(() -> jdbc.update("INSERT INTO print_price_rule (service_id,paper_size,color_mode,side_mode,price_per_impression,enabled) VALUES (?,'A4','BW','SINGLE',30,TRUE)", user.service()), 1062);
        for (String values : List.of("'A3','BW','DOUBLE',20,1,0", "'A4','bw','DOUBLE',20,1,0", "'A4','BW','double',20,1,0",
                "'A4','BW','DOUBLE',0,1,0", "'A4','BW','DOUBLE',100001,1,0", "'A4','BW','DOUBLE',20,2,0", "'A4','BW','DOUBLE',20,1,-1")) {
            assertSql(() -> jdbc.update("INSERT INTO print_price_rule (service_id,paper_size,color_mode,side_mode,price_per_impression,enabled,version) VALUES (?," + values + ")", user.service()), 3819);
        }
        assertSql(() -> jdbc.update("INSERT INTO print_price_rule (service_id,paper_size,color_mode,side_mode,price_per_impression,enabled) VALUES (?,'A4','BW','SINGLE',20,TRUE)", Long.MAX_VALUE), 1216, 1452);
        assertSql(() -> jdbc.update("DELETE FROM service_item WHERE id=?", user.service()), 1217, 1451);
    }

    private record User(long id, String name, long shop, long service) {}
    private User account(String role) {
        var row = new AccountEntity(); row.setUsername("pp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 22));
        row.setPasswordHash(hash); row.setNickname("价目测试"); row.setRole(role); row.setStatus("ACTIVE"); row.setVersion(0L);
        accounts.insert(row); accountIds.add(row.getId()); return new User(row.getId(), row.getUsername(), 0, 0);
    }
    private User merchant(String type) {
        var user = account("MERCHANT"); var shop = new ShopEntity();
        shop.setCategoryId(jdbc.queryForObject("SELECT id FROM shop_category WHERE code=?", Long.class, type));
        shop.setName("虚构价目店"); shop.setCampusCode("XINGHAI_MAIN"); shop.setLocationText("虚构校区"); shop.setDescription("");
        shop.setApprovalStatus("APPROVED"); shop.setBusinessEnabled(false); shop.setVersion(1L); shops.insert(shop); shopIds.add(shop.getId());
        jdbc.update("INSERT INTO shop_member (shop_id,account_id,status) VALUES (?,?,'ACTIVE')", shop.getId(), user.id());
        var service = new ServiceItemEntity(); service.setShopId(shop.getId()); service.setServiceType(type); service.setName("模拟服务");
        service.setDescription(""); service.setPriceAmount(20L); service.setDurationMinutes(type.equals("PRINT") ? null : 30);
        service.setEnabled(false); service.setVersion(0L); services.insert(service);
        return new User(user.id(), user.name(), shop.getId(), service.getId());
    }
    private static LinkedHashMap<String, Object> rule(Object version) { return rule("BW", "SINGLE", 20, true, version); }
    private static LinkedHashMap<String, Object> rule(String color, String side, Object amount, Object enabled, Object version) {
        var body = new LinkedHashMap<String, Object>(); body.put("paperSize", "A4"); body.put("colorMode", color); body.put("sideMode", side);
        body.put("pricePerImpression", amount); body.put("enabled", enabled); body.put("expectedVersion", version); return body;
    }
    private String path(User user) { return "/api/v1/merchant/services/" + user.service() + "/print-prices"; }
    private HttpResponse<String> get(User user, String token) throws Exception { return call("GET", path(user), null, token); }
    private HttpResponse<String> put(User user, List<?> rules, String token) throws Exception { return call("PUT", path(user), Map.of("rules", rules), token); }
    private void putOk(User user, List<?> rules, String token) throws Exception { expect(put(user, rules, token), 200, "OK"); }
    private List<Map<String, Object>> rows(User user) { return jdbc.queryForList("SELECT * FROM print_price_rule WHERE service_id=? ORDER BY color_mode,CASE side_mode WHEN 'SINGLE' THEN 0 ELSE 1 END", user.service()); }
    private List<?> parentSnapshot(User user) {
        return List.of(jdbc.queryForMap("SELECT * FROM account WHERE id=?", user.id()), jdbc.queryForMap("SELECT * FROM shop WHERE id=?", user.shop()),
                jdbc.queryForMap("SELECT * FROM shop_member WHERE account_id=?", user.id()), jdbc.queryForMap("SELECT * FROM service_item WHERE id=?", user.service()),
                jdbc.queryForList("SELECT * FROM shop_business_hour WHERE shop_id=?", user.shop()), jdbc.queryForList("SELECT * FROM shop_review WHERE shop_id=?", user.shop()));
    }
    private List<String> specs(JsonNode data) {
        var keys = new ArrayList<String>(); for (var row : data) { keys.add(row.path("colorMode").asString() + "/" + row.path("sideMode").asString()); } return keys;
    }
    private String login(User user) throws Exception { return expect(call("POST", "/api/v1/auth/login", Map.of("username", user.name(), "password", PASSWORD), null), 200, "OK").path("data").path("accessToken").asString(); }
    private JsonNode expect(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status); var result = json.readTree(response.body());
        assertThat(result.path("code").asString()).isEqualTo(code);
        assertThat(result.path("traceId").asString()).isEqualTo(response.headers().firstValue("X-Trace-Id").orElseThrow()); return result;
    }
    private HttpResponse<String> call(String method, String path, Object body, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + environment.getRequiredProperty("local.server.port") + path)).timeout(Duration.ofSeconds(25));
        if (token != null) { request.header("Authorization", "Bearer " + token); }
        if (body != null) { request.header("Content-Type", "application/json"); }
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(15, TimeUnit.SECONDS)) { throw new IllegalStateException("Test gate timed out"); } }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
    }
    private void assertBusiness(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String code) {
        assertThatThrownBy(action).isInstanceOf(BusinessException.class).satisfies(error -> assertThat(((BusinessException) error).code()).isEqualTo(code));
    }
    private void assertSql(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, int... codes) {
        assertThatThrownBy(action).isInstanceOf(DataAccessException.class).satisfies(error -> assertThat(codes)
                .contains(((SQLException) ((DataAccessException) error).getMostSpecificCause()).getErrorCode()));
    }
}
