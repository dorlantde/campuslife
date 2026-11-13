package com.campuslife;

import com.campuslife.auth.entity.AccountEntity;
import com.campuslife.auth.mapper.AccountMapper;
import com.campuslife.auth.security.AuthPrincipal;
import com.campuslife.auth.vo.AccountView;
import com.campuslife.common.exception.BusinessException;
import com.campuslife.merchant.dto.BarberPatchRequest;
import com.campuslife.merchant.entity.BarberEntity;
import com.campuslife.merchant.entity.ServiceItemEntity;
import com.campuslife.merchant.entity.ShopEntity;
import com.campuslife.merchant.mapper.*;
import com.campuslife.merchant.service.BarberManagementService;
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
class BarberManagementMysqlIT {
    private static final String PATH = "/api/v1/merchant/barbers";
    private static final String PASSWORD = " Barber_密码1! ";
    private final HttpClient client = HttpClient.newHttpClient();
    private final List<Long> accountIds = new ArrayList<>(), shopIds = new ArrayList<>();
    private String hash;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired Environment environment;
    @Autowired PasswordEncoder passwords;
    @Autowired BarberManagementService management;
    @Autowired PlatformTransactionManager manager;
    @Autowired org.mybatis.spring.SqlSessionTemplate sqlSession;
    @MockitoSpyBean AccountMapper accounts;
    @MockitoSpyBean ShopMapper shops;
    @MockitoSpyBean ServiceItemMapper services;
    @MockitoSpyBean BarberMapper barbers;
    @MockitoSpyBean BarberServiceMapper links;
    @MockitoSpyBean Clock clock;

    @BeforeAll void prepareSyntheticHash() { hash = passwords.encode(PASSWORD); }
    @AfterEach void cleanup() {
        reset(accounts, shops, services, barbers, links, clock);
        for (long shop : shopIds) {
            jdbc.update("DELETE bs FROM barber_service bs JOIN barber b ON b.id=bs.barber_id WHERE b.shop_id=?", shop);
            jdbc.update("DELETE FROM barber WHERE shop_id=?", shop);
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
        jdbc.update("UPDATE shop_category SET enabled=TRUE WHERE code='HAIRCUT'");
    }

    @Test void createsDisabledPeopleWithSortedBindingsAndOneBatchListQuery() throws Exception {
        var user = merchant("HAIRCUT"); String token = login(user);
        long first = service(user, "HAIRCUT"), second = service(user, "HAIRCUT"); var parent = parent(user);
        assertThat(expect(call("GET", PATH, null, token), 200, "OK").path("data").isEmpty()).isTrue();
        var body = Map.of("displayName", "  " + "😀".repeat(40) + "  ", "serviceIds", ids(second, first));
        var created = expect(call("POST", PATH, body, token), 201, "OK").path("data");
        assertThat(created.path("displayName").asString()).isEqualTo("😀".repeat(40));
        assertThat(created.path("enabled").asBoolean()).isFalse(); assertThat(created.path("version").asString()).isEqualTo("0");
        assertThat(strings(created.path("serviceIds"))).containsExactly(Long.toString(first), Long.toString(second));
        var duplicate = expect(call("POST", PATH, body, token), 201, "OK").path("data");
        assertThat(duplicate.path("id")).isNotEqualTo(created.path("id"));
        var empty = create(user, token, List.of());
        var other = merchant("HAIRCUT"); create(other, login(other), List.of());
        reset(links); var list = expect(call("GET", PATH, null, token), 200, "OK").path("data");
        assertThat(list.size()).isEqualTo(3); assertThat(list.get(0).path("id").asString()).isEqualTo(empty);
        assertThat(list.get(1)).isEqualTo(duplicate); assertThat(list.get(2)).isEqualTo(created);
        verify(links, times(1)).listOwnShop(user.shop()); verify(links, never()).current(anyLong());
        assertThat(parent(user)).isEqualTo(parent);
        expect(call("DELETE", PATH + "/" + empty, null, token), 405, "METHOD_NOT_ALLOWED");
        expect(call("GET", PATH + "/" + empty + "/schedule", null, token), 400, "VALIDATION_FAILED");
    }

    @Test void supportsFiftyIdsEmptyBindingsAndEnabledWithoutServices() throws Exception {
        var user = merchant("HAIRCUT"); String token = login(user); var ids = new ArrayList<String>();
        for (int i = 0; i < 50; i++) { ids.add(Long.toString(service(user, "HAIRCUT"))); }
        Collections.reverse(ids); String id = create(user, token, ids);
        assertThat(relations(id)).hasSize(50);
        var cleared = expect(bind(id, "0", List.of(), token), 200, "OK").path("data");
        assertThat(cleared.path("serviceIds").isEmpty()).isTrue(); assertThat(cleared.path("version").asString()).isEqualTo("1");
        var enabled = expect(patch(id, Map.of("expectedVersion", "1", "enabled", true), token), 200, "OK").path("data");
        assertThat(enabled.path("enabled").asBoolean()).isTrue(); assertThat(enabled.path("serviceIds").isEmpty()).isTrue();
    }

    @Test void replacingIsFullSetPreservesUnchangedRelationAndSharesVersionWithPatch() throws Exception {
        var user = merchant("HAIRCUT"); String token = login(user);
        long a = service(user, "HAIRCUT"), b = service(user, "HAIRCUT"), c = service(user, "HAIRCUT");
        String id = create(user, token, ids(a, b)); var parent = parent(user); var kept = relations(id).getFirst();
        var view = expect(patch(id, Map.of("expectedVersion", "0", "displayName", "  新姓名  ", "enabled", true), token), 200, "OK").path("data");
        assertThat(view.path("displayName").asString()).isEqualTo("新姓名"); assertThat(view.path("version").asString()).isEqualTo("1");
        expect(bind(id, "0", ids(a, c), token), 409, "CONFIG_VERSION_CONFLICT");
        view = expect(bind(id, "1", ids(c, a), token), 200, "OK").path("data");
        assertThat(strings(view.path("serviceIds"))).containsExactly(Long.toString(a), Long.toString(c));
        assertThat(view.path("version").asString()).isEqualTo("2"); assertThat(view.path("enabled").asBoolean()).isTrue();
        assertThat(relations(id).getFirst()).isEqualTo(kept);
        expect(patch(id, Map.of("expectedVersion", "1", "enabled", false), token), 409, "CONFIG_VERSION_CONFLICT");
        expect(patch(id, Map.of("expectedVersion", "2", "enabled", false), token), 200, "OK");
        assertThat(relations(id)).hasSize(2); assertThat(parent(user)).isEqualTo(parent);
        jdbc.update("UPDATE service_item SET enabled=TRUE WHERE id=?", a);
        jdbc.update("UPDATE service_item SET enabled=FALSE WHERE id=?", a);
        assertThat(strings(expect(call("GET", PATH, null, token), 200, "OK").path("data").get(0).path("serviceIds"))).contains(Long.toString(a));
    }

    @Test void noopsDoNotWriteAndMaxVersionOnlyAllowsNoChange() throws Exception {
        var user = merchant("HAIRCUT"); String token = login(user); long a = service(user, "HAIRCUT"), b = service(user, "HAIRCUT");
        String id = create(user, token, ids(a, b)); var before = snapshot(id); reset(barbers, links);
        expect(patch(id, Map.of("expectedVersion", "0", "displayName", "  模拟理发师  "), token), 200, "OK");
        expect(patch(id, Map.of("expectedVersion", "0"), token), 200, "OK");
        expect(bind(id, "0", ids(b, a), token), 200, "OK");
        verify(barbers, never()).updateOwn(anyLong(), anyLong(), anyLong(), anyString(), anyBoolean());
        verify(links, never()).insert(anyLong(), anyLong()); verify(links, never()).delete(anyLong(), anyLong());
        assertThat(snapshot(id)).isEqualTo(before);
        jdbc.update("UPDATE barber SET version=? WHERE id=?", Long.MAX_VALUE, id); var max = snapshot(id); String version = Long.toString(Long.MAX_VALUE);
        expect(patch(id, Map.of("expectedVersion", version), token), 200, "OK"); expect(bind(id, version, ids(b, a), token), 200, "OK");
        expect(patch(id, Map.of("expectedVersion", version, "enabled", true), token), 409, "CONFIG_VERSION_CONFLICT");
        expect(bind(id, version, List.of(), token), 409, "CONFIG_VERSION_CONFLICT"); assertThat(snapshot(id)).isEqualTo(max);
    }

    static Stream<Arguments> badCreate() {
        var cases = new ArrayList<Arguments>();
        for (Object name : Arrays.asList(null, "", "  ", "名".repeat(41), "😀".repeat(41), "\uD800", 1, false, List.of("x"))) {
            var body = createBody(List.of()); body.put("displayName", name); cases.add(Arguments.of("name " + name, body));
        }
        var missingName = createBody(List.of()); missingName.remove("displayName"); cases.add(Arguments.of("missing name", missingName));
        var missingIds = createBody(List.of()); missingIds.remove("serviceIds"); cases.add(Arguments.of("missing ids", missingIds));
        for (Object value : invalidCollections()) { var body = createBody(value); cases.add(Arguments.of("ids " + value, body)); }
        for (String field : List.of("enabled", "shopId", "accountId", "role", "version", "id", "expectedVersion")) {
            var body = createBody(List.of()); body.put(field, true); cases.add(Arguments.of("extra " + field, body));
        }
        return cases.stream();
    }
    static List<Object> invalidCollections() {
        return Arrays.asList(null, "[]", 0, Map.of("x", "1"), Collections.nCopies(51, "1"), List.of("1", "1"),
                Arrays.asList((Object) null), List.of(1), List.of(true), List.of("0"), List.of("01"), List.of(" 1"), List.of("-1"),
                List.of("9223372036854775808"), List.of("1.0"), List.of(List.of("1")));
    }
    @ParameterizedTest(name = "{0}") @MethodSource("badCreate")
    void invalidCreateNeverLeavesPersonOrBindings(String label, Object body) throws Exception {
        var user = merchant("HAIRCUT"); String token = login(user);
        expect(call("POST", PATH, body, token), 400, "VALIDATION_FAILED"); assertThat(people(user)).isZero();
    }

    static Stream<Arguments> badPatch() {
        var cases = new ArrayList<Arguments>();
        cases.add(Arguments.of("missing version", Map.of("enabled", true)));
        for (Object version : Arrays.asList(null, 0, false, "00", "-1", " 0", "1.0", "9223372036854775808")) {
            var body = new LinkedHashMap<String,Object>(); body.put("expectedVersion", version); cases.add(Arguments.of("version " + version, body));
        }
        for (Object name : Arrays.asList(null, "", " ", "名".repeat(41), 1, false, "\uD800")) {
            var body = new LinkedHashMap<String,Object>(); body.put("expectedVersion", "0"); body.put("displayName", name); cases.add(Arguments.of("name " + name, body));
        }
        for (Object enabled : Arrays.asList(null, "true", 0, 1, List.of(true))) {
            var body = new LinkedHashMap<String,Object>(); body.put("expectedVersion", "0"); body.put("enabled", enabled); cases.add(Arguments.of("enabled " + enabled, body));
        }
        for (String field : List.of("serviceIds", "shopId", "role", "version", "id")) {
            cases.add(Arguments.of("extra " + field, Map.of("expectedVersion", "0", field, List.of())));
        }
        return cases.stream();
    }
    @ParameterizedTest(name = "{0}") @MethodSource("badPatch")
    void invalidPatchDoesNotChangeAnyData(String label, Object body) throws Exception {
        var user = merchant("HAIRCUT"); String token = login(user); String id = create(user, token, List.of()); var before = snapshot(id);
        expect(patch(id, body, token), 400, "VALIDATION_FAILED"); assertThat(snapshot(id)).isEqualTo(before);
    }

    static Stream<Arguments> badBind() {
        var cases = new ArrayList<Arguments>();
        for (Object ids : invalidCollections()) {
            var body = new LinkedHashMap<String,Object>(); body.put("expectedVersion", "0"); body.put("serviceIds", ids); cases.add(Arguments.of("ids " + ids, body));
        }
        cases.add(Arguments.of("missing ids", Map.of("expectedVersion", "0")));
        cases.add(Arguments.of("missing version", Map.of("serviceIds", List.of())));
        for (Object version : Arrays.asList(null, 0, "00", "-1", "9223372036854775808")) {
            var body = new LinkedHashMap<String,Object>(); body.put("expectedVersion", version); body.put("serviceIds", List.of()); cases.add(Arguments.of("version " + version, body));
        }
        cases.add(Arguments.of("unknown", Map.of("expectedVersion", "0", "serviceIds", List.of(), "enabled", true)));
        return cases.stream();
    }
    @ParameterizedTest(name = "{0}") @MethodSource("badBind")
    void invalidReplacementDoesNotChangeBindings(String label, Object body) throws Exception {
        var user = merchant("HAIRCUT"); String token = login(user); String id = create(user, token, ids(service(user, "HAIRCUT"))); var before = snapshot(id);
        expect(call("PUT", PATH + "/" + id + "/services", body, token), 400, "VALIDATION_FAILED"); assertThat(snapshot(id)).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"0", "01", "-1", "abc", "9223372036854775808"})
    void malformedBarberIdsAreRejected(String id) throws Exception {
        var user = merchant("HAIRCUT"); String token = login(user);
        expect(patch(id, Map.of("expectedVersion", "0"), token), 400, "VALIDATION_FAILED");
        expect(bind(id, "0", List.of(), token), 400, "VALIDATION_FAILED");
    }

    @Test void requiresOwnHaircutServicesAndRejectsWholeCreateOrReplacement() throws Exception {
        var user = merchant("HAIRCUT"); String token = login(user); long valid = service(user, "HAIRCUT"), wrongType = service(user, "PRINT");
        var other = merchant("HAIRCUT"); long foreign = service(other, "HAIRCUT"); String id = create(user, token, ids(valid)); var before = snapshot(id);
        for (long bad : List.of(foreign, Long.MAX_VALUE)) {
            expect(call("POST", PATH, createBody(ids(valid, bad)), token), 404, "RESOURCE_NOT_FOUND");
            expect(bind(id, "0", ids(valid, bad), token), 404, "RESOURCE_NOT_FOUND");
        }
        expect(call("POST", PATH, createBody(ids(valid, wrongType)), token), 400, "VALIDATION_FAILED");
        expect(bind(id, "0", ids(valid, wrongType), token), 400, "VALIDATION_FAILED");
        assertThat(people(user)).isEqualTo(1); assertThat(snapshot(id)).isEqualTo(before);
        jdbc.update("UPDATE shop_category SET enabled=FALSE WHERE code='HAIRCUT'");
        expect(patch(id, Map.of("expectedVersion", "0", "enabled", true), token), 200, "OK");
        create(user, token, List.of());
    }

    @Test void allFourOperationsEnforceRolesStoreMemberStatusAndSessions() throws Exception {
        var user = merchant("HAIRCUT"); String token = login(user); String id = create(user, token, List.of()); var before = snapshot(id);
        assertAllDenied(id, null, 401, "AUTH_REQUIRED");
        for (String role : List.of("STUDENT", "ADMIN")) { assertAllDenied(id, login(account(role)), 403, "ROLE_FORBIDDEN"); }
        assertAllDenied(id, login(account("MERCHANT")), 404, "RESOURCE_NOT_FOUND");
        assertAllDenied(id, login(merchant("PRINT")), 400, "VALIDATION_FAILED");
        var other = merchant("HAIRCUT"); String otherToken = login(other); String otherId = create(other, otherToken, List.of());
        expect(patch(otherId, Map.of("expectedVersion", "0"), token), 404, "RESOURCE_NOT_FOUND");
        expect(bind(otherId, "0", List.of(), token), 404, "RESOURCE_NOT_FOUND");
        expect(patch(Long.toString(Long.MAX_VALUE), Map.of("expectedVersion", "0"), token), 404, "RESOURCE_NOT_FOUND");
        for (String state : List.of("PENDING", "REJECTED", "SUSPENDED")) {
            jdbc.update("UPDATE shop SET approval_status=? WHERE id=?", state, user.shop()); assertAllDenied(id, token, 403, "SHOP_NOT_APPROVED");
        }
        jdbc.update("UPDATE shop SET approval_status='APPROVED' WHERE id=?", user.shop());
        for (String state : List.of("PENDING", "DISABLED")) {
            jdbc.update("UPDATE shop_member SET status=? WHERE account_id=?", state, user.id()); assertAllDenied(id, token, 403, "ROLE_FORBIDDEN");
        }
        jdbc.update("UPDATE shop_member SET status='ACTIVE' WHERE account_id=?", user.id());
        jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?", user.id()); assertAllDenied(id, token, 403, "ACCOUNT_DISABLED");
        jdbc.update("UPDATE account SET status='ACTIVE' WHERE id=?", user.id());
        jdbc.update("DELETE FROM shop_member WHERE account_id=?", user.id());
        assertAllDenied(id, token, 404, "RESOURCE_NOT_FOUND");
        jdbc.update("INSERT INTO shop_member (shop_id,account_id,status) VALUES (?,?,'ACTIVE')", user.shop(), user.id());
        expect(call("POST", "/api/v1/auth/logout", null, token), 200, "OK"); assertAllDenied(id, token, 401, "SESSION_REVOKED");
        assertThat(snapshot(id)).isEqualTo(before);
    }
    private void assertAllDenied(String id, String token, int status, String code) throws Exception {
        expect(call("GET", PATH, null, token), status, code); expect(call("POST", PATH, createBody(List.of()), token), status, code);
        expect(patch(id, Map.of("expectedVersion", "0"), token), status, code); expect(bind(id, "0", List.of(), token), status, code);
    }

    @Test void profileAndBindingConcurrentEditsHaveOneWinnerWhileDifferentPeopleStayIndependent() throws Exception {
        var user = merchant("HAIRCUT"); String a = login(user), b = login(user); long x = service(user, "HAIRCUT"), y = service(user, "HAIRCUT");
        String id = create(user, a, ids(x)), second = create(user, a, List.of()); var parent = parent(user); var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var p = pool.submit(() -> { await(start); return patch(id, Map.of("expectedVersion", "0", "displayName", "新姓名", "enabled", true), a); });
            var q = pool.submit(() -> { await(start); return bind(id, "0", ids(y), b); });
            start.countDown(); var rp = p.get(30, TimeUnit.SECONDS); var rq = q.get(30, TimeUnit.SECONDS);
            assertThat(List.of(rp.statusCode(), rq.statusCode())).containsExactlyInAnyOrder(200, 409);
            var row = jdbc.queryForMap("SELECT * FROM barber WHERE id=?", id);
            assertThat(row.get("version").toString()).isEqualTo("1");
            assertThat(row.get("display_name")).isEqualTo(rp.statusCode() == 200 ? "新姓名" : "模拟理发师");
            assertThat(relatedIds(id)).containsExactly(rp.statusCode() == 200 ? x : y);
        }
        try (var pool = Executors.newFixedThreadPool(2)) {
            var p = pool.submit(() -> patch(id, Map.of("expectedVersion", "1", "displayName", "第二次"), a));
            var q = pool.submit(() -> bind(second, "0", ids(x), b));
            expect(p.get(30, TimeUnit.SECONDS), 200, "OK"); expect(q.get(30, TimeUnit.SECONDS), 200, "OK");
        }
        assertThat(parent(user)).isEqualTo(parent);
    }

    @Test void failuresAfterPersonOrRelationInsertsRollBackWholeCreate() throws Exception {
        var user = merchant("HAIRCUT"); String token = login(user); long a = service(user, "HAIRCUT"), b = service(user, "HAIRCUT");
        var realBarbers = sqlSession.getMapper(BarberMapper.class); var realLinks = sqlSession.getMapper(BarberServiceMapper.class);
        doAnswer(inv -> { realBarbers.insert((BarberEntity) inv.getArgument(0)); throw new IllegalStateException("Injected after actual barber insert"); }).when(barbers).insert(any(BarberEntity.class));
        expect(call("POST", PATH, createBody(ids(a, b)), token), 500, "INTERNAL_ERROR"); assertThat(people(user)).isZero(); reset(barbers);
        var writes = new AtomicInteger();
        doAnswer(inv -> {
            int count = realLinks.insert(inv.getArgument(0), inv.getArgument(1));
            if (writes.incrementAndGet() == 2) { throw new IllegalStateException("Injected after actual second link insert"); } return count;
        }).when(links).insert(anyLong(), anyLong());
        expect(call("POST", PATH, createBody(ids(a, b)), token), 500, "INTERNAL_ERROR"); assertThat(people(user)).isZero();
    }

    @Test void failuresAfterDeleteInsertAndVersionUpdateRestoreOldRelations() throws Exception {
        var user = merchant("HAIRCUT"); String token = login(user); long a = service(user, "HAIRCUT"), b = service(user, "HAIRCUT");
        String id = create(user, token, ids(a)); var before = snapshot(id);
        var realLinks = sqlSession.getMapper(BarberServiceMapper.class); var realBarbers = sqlSession.getMapper(BarberMapper.class);
        doAnswer(inv -> { realLinks.insert(inv.getArgument(0), inv.getArgument(1)); throw new IllegalStateException("Injected after actual insert following delete"); }).when(links).insert(anyLong(), anyLong());
        expect(bind(id, "0", ids(b), token), 500, "INTERNAL_ERROR"); assertThat(snapshot(id)).isEqualTo(before); reset(links);
        doAnswer(inv -> {
            realBarbers.updateOwn(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), inv.getArgument(3), inv.getArgument(4));
            throw new IllegalStateException("Injected after actual barber version update");
        }).when(barbers).updateOwn(anyLong(), anyLong(), anyLong(), anyString(), anyBoolean());
        expect(bind(id, "0", ids(b), token), 500, "INTERNAL_ERROR"); assertThat(snapshot(id)).isEqualTo(before);
        expect(patch(id, Map.of("expectedVersion", "0", "enabled", true), token), 500, "INTERNAL_ERROR"); assertThat(snapshot(id)).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"create", "patch", "bind"})
    void sessionExpiryAfterRealWriteRollsBackEveryWritePath(String mode) throws Exception {
        var user = merchant("HAIRCUT"); String token = login(user); long a = service(user, "HAIRCUT"); String id = create(user, token, ids(a)); var before = snapshot(id);
        if (mode.equals("create")) {
            var real = sqlSession.getMapper(BarberMapper.class);
            doAnswer(inv -> { int n = real.insert((BarberEntity) inv.getArgument(0)); expire(); return n; }).when(barbers).insert(any(BarberEntity.class));
            expect(call("POST", PATH, createBody(List.of()), token), 401, "SESSION_EXPIRED"); assertThat(people(user)).isEqualTo(1);
        } else if (mode.equals("patch")) {
            var real = sqlSession.getMapper(BarberMapper.class);
            doAnswer(inv -> { int n = real.updateOwn(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), inv.getArgument(3), inv.getArgument(4)); expire(); return n; })
                    .when(barbers).updateOwn(anyLong(), anyLong(), anyLong(), anyString(), anyBoolean());
            expect(patch(id, Map.of("expectedVersion", "0", "enabled", true), token), 401, "SESSION_EXPIRED");
        } else {
            var real = sqlSession.getMapper(BarberServiceMapper.class);
            doAnswer(inv -> { int n = real.delete(inv.getArgument(0), inv.getArgument(1)); expire(); return n; }).when(links).delete(anyLong(), anyLong());
            expect(bind(id, "0", List.of(), token), 401, "SESSION_EXPIRED");
        }
        assertThat(snapshot(id)).isEqualTo(before);
    }

    @Test void freshRoleAndSessionOverridePrincipalSnapshot() throws Exception {
        var user = merchant("HAIRCUT"); String token = login(user); String id = create(user, token, List.of()); var before = snapshot(id);
        String jti = json.readTree(new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8)).path("jti").asString();
        var principal = new AuthPrincipal(user.id(), jti, new AccountView(Long.toString(user.id()), user.name(), "店主", "MERCHANT", "ACTIVE", "0"));
        var body = json.convertValue(Map.of("expectedVersion", "0", "enabled", true), BarberPatchRequest.class);
        jdbc.update("UPDATE account SET role='STUDENT' WHERE id=?", user.id()); assertBusiness(() -> management.patch(principal, id, body), "ROLE_FORBIDDEN");
        jdbc.update("UPDATE account SET role='MERCHANT',status='DISABLED' WHERE id=?", user.id()); assertBusiness(() -> management.patch(principal, id, body), "ACCOUNT_DISABLED");
        jdbc.update("UPDATE account SET status='ACTIVE' WHERE id=?", user.id());
        jdbc.update("UPDATE auth_session SET revoked_at=created_at WHERE account_id=? AND jti=?", user.id(), jti);
        assertBusiness(() -> management.patch(principal, id, body), "SESSION_REVOKED"); assertThat(snapshot(id)).isEqualTo(before);
    }

    @Test void shopWaitRechecksSuspensionAndSessionExpiry() throws Exception {
        var user = merchant("HAIRCUT"); String token = login(user); String id = create(user, token, List.of());
        waitForRow(user, "shop", user.shop(), () -> jdbc.update("UPDATE shop SET approval_status='SUSPENDED' WHERE id=?", user.shop()),
                () -> patch(id, Map.of("expectedVersion", "0", "enabled", true), token), 403, "SHOP_NOT_APPROVED");
        jdbc.update("UPDATE shop SET approval_status='APPROVED' WHERE id=?", user.shop()); reset(shops);
        waitForRow(user, "shop", user.shop(), this::expire, () -> bind(id, "0", List.of(), token), 401, "SESSION_EXPIRED");
    }

    @Test void serviceWaitRechecksTypeAndBarberWaitReturnsLatestProfileAndBindings() throws Exception {
        var user = merchant("HAIRCUT"); String token = login(user); long a = service(user, "HAIRCUT"), b = service(user, "HAIRCUT");
        String id = create(user, token, List.of());
        waitForRow(user, "service_item", a, () -> jdbc.update("UPDATE service_item SET service_type='PRINT',duration_minutes=NULL WHERE id=?", a),
                () -> bind(id, "0", ids(a), token), 400, "VALIDATION_FAILED");
        assertThat(relatedIds(id)).isEmpty(); reset(shops);
        waitForRow(user, "barber", Long.parseLong(id), () -> {
            // Changing indexed enabled here would recheck the shop FK after taking the child lock,
            // reversing the application's shop -> barber order. Hold the child only for non-indexed data.
            jdbc.update("UPDATE barber SET display_name='最新姓名',version=1 WHERE id=?", id);
            jdbc.update("INSERT INTO barber_service (barber_id,service_id) VALUES (?,?)", id, b);
        }, () -> patch(id, Map.of("expectedVersion", "1", "enabled", true), token), 200, "OK");
        var current = expect(call("GET", PATH, null, token), 200, "OK").path("data").get(0);
        assertThat(current.path("displayName").asString()).isEqualTo("最新姓名"); assertThat(current.path("version").asString()).isEqualTo("2");
        assertThat(current.path("enabled").asBoolean()).isTrue();
        assertThat(strings(current.path("serviceIds"))).containsExactly(Long.toString(b));
    }

    @ParameterizedTest @CsvSource({"role,STUDENT,ROLE_FORBIDDEN", "status,DISABLED,ACCOUNT_DISABLED"})
    void accountWaitRechecksRoleOrStatus(String field, String value, String code) throws Exception {
        var user = merchant("HAIRCUT"); String token = login(user); String id = create(user, token, List.of());
        assertThat(Set.of("role", "status")).contains(field); var reached = new CountDownLatch(1); var real = sqlSession.getMapper(AccountMapper.class);
        doAnswer(inv -> { reached.countDown(); return real.lockById(user.id()); }).when(accounts).lockById(user.id());
        waitForRow(user, "account", user.id(), () -> jdbc.update("UPDATE account SET " + field + "=? WHERE id=?", value, user.id()),
                () -> patch(id, Map.of("expectedVersion", "0", "enabled", true), token), 403, code, reached);
    }

    @Test void listReadsProfileAndBindingsFromSameSnapshotDuringConcurrentEdit() throws Exception {
        var user = merchant("HAIRCUT"); String token = login(user); long a = service(user, "HAIRCUT"), b = service(user, "HAIRCUT"); String id = create(user, token, ids(a));
        var read = new CountDownLatch(1); var release = new CountDownLatch(1); var real = sqlSession.getMapper(BarberMapper.class);
        doAnswer(inv -> { var rows = real.listOwn(user.shop()); read.countDown(); await(release); return rows; }).when(barbers).listOwn(user.shop());
        try (var pool = Executors.newSingleThreadExecutor()) {
            var response = pool.submit(() -> call("GET", PATH, null, token));
            try {
                assertThat(read.await(15, TimeUnit.SECONDS)).isTrue();
                expect(bind(id, "0", ids(b), token), 200, "OK");
                expect(patch(id, Map.of("expectedVersion", "1", "displayName", "新姓名"), token), 200, "OK");
            } finally { release.countDown(); }
            var old = expect(response.get(20, TimeUnit.SECONDS), 200, "OK").path("data").get(0);
            assertThat(old.path("displayName").asString()).isEqualTo("模拟理发师"); assertThat(old.path("version").asString()).isEqualTo("0");
            assertThat(strings(old.path("serviceIds"))).containsExactly(Long.toString(a));
        }
    }

    private void waitForRow(User user, String table, long rowId, Runnable update, Callable<HttpResponse<String>> action,
                            int statusCode, String code) throws Exception {
        var reached = new CountDownLatch(1); var real = sqlSession.getMapper(ShopMapper.class);
        doAnswer(inv -> { var own = real.findOwn(user.id()); reached.countDown(); return own; }).when(shops).findOwn(user.id());
        waitForRow(user, table, rowId, update, action, statusCode, code, reached);
    }
    private void waitForRow(User user, String table, long rowId, Runnable update, Callable<HttpResponse<String>> action,
                            int statusCode, String code, CountDownLatch reached) throws Exception {
        assertThat(Set.of("shop", "service_item", "barber", "account")).contains(table);
        var held = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var blocker = pool.submit(() -> new TransactionTemplate(manager).executeWithoutResult(tx -> {
                jdbc.queryForObject("SELECT id FROM " + table + " WHERE id=? FOR UPDATE", Long.class, rowId);
                held.countDown(); await(release); update.run();
            }));
            try {
                assertThat(held.await(15, TimeUnit.SECONDS)).isTrue(); var response = pool.submit(action);
                try {
                    assertThat(reached.await(15, TimeUnit.SECONDS)).isTrue();
                    assertThatThrownBy(() -> response.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                } finally { release.countDown(); }
                blocker.get(20, TimeUnit.SECONDS); expect(response.get(20, TimeUnit.SECONDS), statusCode, code);
            } finally { release.countDown(); }
        }
    }

    @Test void databaseEnforcesBarberChecksUniqueBindingsAndBothForeignKeys() throws Exception {
        var user = merchant("HAIRCUT"); String token = login(user); long a = service(user, "HAIRCUT"); String id = create(user, token, ids(a));
        for (String values : List.of("'',0,0", "'测试',2,0", "'测试',0,-1")) {
            assertSql(() -> jdbc.update("INSERT INTO barber (shop_id,display_name,enabled,version) VALUES (?," + values + ")", user.shop()), 3819);
        }
        assertSql(() -> jdbc.update("INSERT INTO barber (shop_id,display_name) VALUES (?,'测试')", Long.MAX_VALUE), 1216, 1452);
        assertSql(() -> jdbc.update("INSERT INTO barber_service (barber_id,service_id) VALUES (?,?)", id, a), 1062);
        assertSql(() -> jdbc.update("INSERT INTO barber_service (barber_id,service_id) VALUES (?,?)", id, Long.MAX_VALUE), 1216, 1452);
        assertSql(() -> jdbc.update("INSERT INTO barber_service (barber_id,service_id) VALUES (?,?)", Long.MAX_VALUE, a), 1216, 1452);
        assertSql(() -> jdbc.update("DELETE FROM barber WHERE id=?", id), 1217, 1451);
        assertSql(() -> jdbc.update("DELETE FROM service_item WHERE id=?", a), 1217, 1451);
    }

    private record User(long id, String name, long shop) {}
    private User account(String role) {
        var row = new AccountEntity(); row.setUsername("bar_" + UUID.randomUUID().toString().replace("-", "").substring(0, 22)); row.setNickname("理发师测试");
        row.setPasswordHash(hash); row.setRole(role); row.setStatus("ACTIVE"); row.setVersion(0L); accounts.insert(row); accountIds.add(row.getId());
        return new User(row.getId(), row.getUsername(), 0);
    }
    private User merchant(String category) {
        var user = account("MERCHANT"); var row = new ShopEntity();
        row.setCategoryId(jdbc.queryForObject("SELECT id FROM shop_category WHERE code=?", Long.class, category));
        row.setName("虚构理发店"); row.setLocationText("虚构校区"); row.setCampusCode("XINGHAI_MAIN"); row.setDescription("");
        row.setApprovalStatus("APPROVED"); row.setBusinessEnabled(false); row.setVersion(1L); shops.insert(row); shopIds.add(row.getId());
        jdbc.update("INSERT INTO shop_member (shop_id,account_id,status) VALUES (?,?,'ACTIVE')", row.getId(), user.id()); return new User(user.id(), user.name(), row.getId());
    }
    private long service(User user, String type) {
        var row = new ServiceItemEntity(); row.setShopId(user.shop()); row.setServiceType(type); row.setName("模拟服务"); row.setDescription("");
        row.setPriceAmount(3000L); row.setDurationMinutes(type.equals("HAIRCUT") ? 30 : null); row.setEnabled(false); row.setVersion(0L); services.insert(row); return row.getId();
    }
    private static Map<String,Object> createBody(Object ids) { var body = new LinkedHashMap<String,Object>(); body.put("displayName", "模拟理发师"); body.put("serviceIds", ids); return body; }
    private static List<String> ids(long... values) { return Arrays.stream(values).mapToObj(Long::toString).toList(); }
    private String create(User user, String token, Object ids) throws Exception { return expect(call("POST", PATH, createBody(ids), token), 201, "OK").path("data").path("id").asString(); }
    private HttpResponse<String> patch(String id, Object body, String token) throws Exception { return call("PATCH", PATH + "/" + id, body, token); }
    private HttpResponse<String> bind(String id, String version, Object ids, String token) throws Exception { return call("PUT", PATH + "/" + id + "/services", Map.of("expectedVersion", version, "serviceIds", ids), token); }
    private List<Map<String,Object>> relations(String id) { return jdbc.queryForList("SELECT * FROM barber_service WHERE barber_id=? ORDER BY service_id", id); }
    private List<Long> relatedIds(String id) { return jdbc.queryForList("SELECT service_id FROM barber_service WHERE barber_id=? ORDER BY service_id", Long.class, id); }
    private List<?> snapshot(String id) { return List.of(jdbc.queryForMap("SELECT * FROM barber WHERE id=?", id), relations(id)); }
    private List<?> parent(User user) { return List.of(jdbc.queryForMap("SELECT * FROM account WHERE id=?", user.id()), jdbc.queryForMap("SELECT * FROM shop WHERE id=?", user.shop()),
            jdbc.queryForMap("SELECT * FROM shop_member WHERE account_id=?", user.id()), jdbc.queryForList("SELECT * FROM service_item WHERE shop_id=? ORDER BY id", user.shop())); }
    private int people(User user) { return jdbc.queryForObject("SELECT COUNT(*) FROM barber WHERE shop_id=?", Integer.class, user.shop()); }
    private List<String> strings(JsonNode values) { var result = new ArrayList<String>(); for (var value : values) { assertThat(value.isString()).isTrue(); result.add(value.asString()); } return result; }
    private void expire() { doReturn(Instant.now().plusSeconds(7201)).when(clock).instant(); }
    private String login(User user) throws Exception { return expect(call("POST", "/api/v1/auth/login", Map.of("username", user.name(), "password", PASSWORD), null), 200, "OK").path("data").path("accessToken").asString(); }
    private JsonNode expect(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status); var body = json.readTree(response.body()); assertThat(body.path("code").asString()).isEqualTo(code);
        assertThat(body.path("traceId").asString()).isEqualTo(response.headers().firstValue("X-Trace-Id").orElseThrow()); return body;
    }
    private HttpResponse<String> call(String method, String path, Object body, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + environment.getRequiredProperty("local.server.port") + path)).timeout(Duration.ofSeconds(25));
        if (token != null) { request.header("Authorization", "Bearer " + token); } if (body != null) { request.header("Content-Type", "application/json"); }
        String payload = body == null ? null : json.writeValueAsString(body).replace(String.valueOf((char) 0xD800), "\\uD800");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
    private static void await(CountDownLatch latch) { try { if (!latch.await(15, TimeUnit.SECONDS)) { throw new IllegalStateException("Test gate timed out"); } } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); } }
    private void assertBusiness(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String code) { assertThatThrownBy(action).isInstanceOf(BusinessException.class).satisfies(error -> assertThat(((BusinessException) error).code()).isEqualTo(code)); }
    private void assertSql(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, int... codes) { assertThatThrownBy(action).isInstanceOf(DataAccessException.class).satisfies(error -> assertThat(codes).contains(((SQLException) ((DataAccessException) error).getMostSpecificCause()).getErrorCode())); }
}
