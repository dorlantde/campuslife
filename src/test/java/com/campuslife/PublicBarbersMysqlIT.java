package com.campuslife;

import com.campuslife.auth.entity.AccountEntity;
import com.campuslife.auth.mapper.AccountMapper;
import com.campuslife.merchant.entity.*;
import com.campuslife.merchant.mapper.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("mysql")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PublicBarbersMysqlIT {
    private static final String PASSWORD = "Public_Barber123!";
    private final HttpClient client = HttpClient.newHttpClient();
    private final List<Long> accountIds = new ArrayList<>(), shopIds = new ArrayList<>();
    private String hash;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired Environment environment;
    @Autowired PasswordEncoder passwords;
    @Autowired AccountMapper accounts;
    @Autowired ShopMapper shops;
    @Autowired BarberMapper people;
    @Autowired BarberServiceMapper links;
    @Autowired PlatformTransactionManager manager;
    @Autowired org.mybatis.spring.SqlSessionTemplate sqlSession;
    @MockitoSpyBean PublicShopMapper catalog;
    @MockitoSpyBean PublicBarberMapper barbers;
    @MockitoSpyBean ServiceItemMapper services;
    @MockitoSpyBean Clock clock;

    @BeforeAll void hash() { hash = passwords.encode(PASSWORD); }
    @AfterEach void cleanup() {
        reset(catalog, barbers, services, clock);
        // Cross-shop test bindings must all be removed before deleting either shop's services.
        for (long shop : shopIds) { jdbc.update("DELETE bs FROM barber_service bs JOIN barber b ON b.id=bs.barber_id WHERE b.shop_id=?", shop); }
        for (long shop : shopIds) {
            jdbc.update("DELETE FROM barber WHERE shop_id=?", shop);
            jdbc.update("DELETE FROM service_item WHERE shop_id=?", shop);
            jdbc.update("DELETE FROM shop_business_hour WHERE shop_id=?", shop);
            jdbc.update("DELETE FROM shop_member WHERE shop_id=?", shop);
            jdbc.update("DELETE FROM shop WHERE id=?", shop);
        }
        for (long id : accountIds) { jdbc.update("DELETE FROM auth_session WHERE account_id=?", id); jdbc.update("DELETE FROM account WHERE id=?", id); }
        jdbc.update("UPDATE shop_category SET enabled=TRUE WHERE code IN ('HAIRCUT','PRINT')");
        accountIds.clear(); shopIds.clear();
    }

    @Test void returnsOnlyEnabledPeopleWithPublicBindingsAndKeepsCompleteIdsWhenFiltered() throws Exception {
        var u = shop("HAIRCUT"); long a = service(u, "HAIRCUT", true), b = service(u, "HAIRCUT", true), down = service(u, "HAIRCUT", false);
        long first = barber(u, true, "同名", b, down, a), second = barber(u, true, "同名", a);
        barber(u, false, "停用", a); barber(u, true, "空绑定"); barber(u, true, "仅下架", down);
        var foreign = shop("HAIRCUT"); long other = service(foreign, "HAIRCUT", true); barber(foreign, true, "其他店", other);
        var all = data(get(path(u), null)); assertThat(ids(all)).containsExactly("" + second, "" + first);
        assertThat(all.get(0).propertyNames()).containsExactlyInAnyOrder("id", "displayName", "serviceIds");
        assertThat(all.get(0).path("displayName").asString()).isEqualTo("同名");
        assertThat(strings(all.get(1).path("serviceIds"))).containsExactly("" + a, "" + b);
        assertThat(all.toString()).doesNotContain("version", "enabled", "accountId", "shopId", "createdAt", "schedule", "password", "jti");
        var filtered = data(get(path(u) + "?serviceId=" + b, null));
        assertThat(ids(filtered)).containsExactly("" + first); assertThat(strings(filtered.get(0).path("serviceIds"))).containsExactly("" + a, "" + b);
        long unused = service(u, "HAIRCUT", true); assertThat(data(get(path(u) + "?serviceId=" + unused, null)).isEmpty()).isTrue();
        assertThat(data(get(path(u) + "?serviceId=" + a, null))).isEqualTo(all);
        // created_at takes priority over ID; with equal timestamps ID desc breaks ties.
        jdbc.update("UPDATE barber SET created_at='2026-10-20 00:00:00' WHERE id=?", second);
        jdbc.update("UPDATE barber SET created_at='2026-10-21 00:00:00' WHERE id=?", first);
        assertThat(ids(data(get(path(u), null)))).containsExactly("" + first, "" + second);
    }

    @Test void stoppedOrUnconfiguredShopsRemainVisibleWithoutAnyScheduleAndCategoryMayBeDisabled() throws Exception {
        var u = shop("HAIRCUT"); long a = service(u, "HAIRCUT", true), id = barber(u, true, "无排班", a);
        jdbc.update("UPDATE shop SET business_enabled=FALSE WHERE id=?", u.shop());
        jdbc.update("UPDATE shop_category SET enabled=FALSE WHERE code='HAIRCUT'");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shop_business_hour WHERE shop_id=?", Integer.class, u.shop())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM barber_schedule WHERE barber_id=?", Integer.class, id)).isZero();
        assertThat(ids(data(get(path(u), null)))).containsExactly("" + id);
        assertThat(ids(data(get(path(u) + "?serviceId=" + a, null)))).containsExactly("" + id);
    }

    @Test void nonHaircutShopsReturnEmptyWithoutFilterAnd404WithFilter() throws Exception {
        var u = shop("PRINT"); long print = service(u, "PRINT", true); barber(u, true, "人工异常人员", print);
        assertThat(data(get(path(u), null)).isEmpty()).isTrue();
        expect(get(path(u) + "?serviceId=" + print, null), 404, "RESOURCE_NOT_FOUND");
        // Even a manually corrupted PRINT shop with a HAIRCUT service is not a public barber shop.
        long wrongType = service(u, "HAIRCUT", true); barber(u, true, "异常类型", wrongType);
        assertThat(data(get(path(u), null)).isEmpty()).isTrue();
        expect(get(path(u) + "?serviceId=" + wrongType, null), 404, "RESOURCE_NOT_FOUND");
    }

    @Test void crossShopAndWrongTypeBindingsAreIgnoredWithoutRepairingStoredConfiguration() throws Exception {
        var u = shop("HAIRCUT"); var v = shop("HAIRCUT");
        long own = service(u, "HAIRCUT", true), foreign = service(v, "HAIRCUT", true), print = service(u, "PRINT", true);
        long person = barber(u, true, "混合异常绑定", own, foreign, print); barber(u, true, "只有跨店绑定", foreign); barber(u, true, "只有错误类型", print);
        var before = snapshot(u); var all = data(get(path(u), null));
        assertThat(ids(all)).containsExactly("" + person); assertThat(strings(all.get(0).path("serviceIds"))).containsExactly("" + own);
        expect(get(path(u) + "?serviceId=" + foreign, null), 404, "RESOURCE_NOT_FOUND");
        expect(get(path(u) + "?serviceId=" + print, null), 404, "RESOURCE_NOT_FOUND");
        assertThat(snapshot(u)).isEqualTo(before);
    }

    @Test void missingForeignDisabledAndNonHaircutFiltersUseTheSameError() throws Exception {
        var u = shop("HAIRCUT"); var v = shop("HAIRCUT");
        long down = service(u, "HAIRCUT", false), foreign = service(v, "HAIRCUT", true), print = service(u, "PRINT", true);
        for (long id : List.of(Long.MAX_VALUE, down, foreign, print)) { expect(get(path(u) + "?serviceId=" + id, null), 404, "RESOURCE_NOT_FOUND"); }
        expect(get("/api/v1/shops/9223372036854775807/barbers", null), 404, "RESOURCE_NOT_FOUND");
    }

    @ParameterizedTest @ValueSource(strings = {"pending", "rejected", "suspended", "ownerPending", "ownerDisabled", "ownerMissing", "accountDisabled", "student", "admin"})
    void visibilityUsesTheSameShopAndOwnerQualificationAsThePublicCatalog(String mode) throws Exception {
        var u = shop("HAIRCUT"); long a = service(u, "HAIRCUT", true); barber(u, true, "正常人员", a);
        switch (mode) {
            case "pending", "rejected", "suspended" -> jdbc.update("UPDATE shop SET approval_status=? WHERE id=?", mode.toUpperCase(Locale.ROOT), u.shop());
            case "ownerPending", "ownerDisabled" -> jdbc.update("UPDATE shop_member SET status=? WHERE shop_id=?", mode.equals("ownerPending") ? "PENDING" : "DISABLED", u.shop());
            case "ownerMissing" -> jdbc.update("DELETE FROM shop_member WHERE shop_id=?", u.shop());
            case "accountDisabled" -> jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?", u.id());
            case "student", "admin" -> jdbc.update("UPDATE account SET role=? WHERE id=?", mode.toUpperCase(Locale.ROOT), u.id());
            default -> throw new IllegalStateException(mode);
        }
        expect(get(path(u), null), 404, "RESOURCE_NOT_FOUND"); expect(get(path(u) + "?serviceId=" + a, null), 404, "RESOURCE_NOT_FOUND");
    }

    @ParameterizedTest @ValueSource(strings = {"0", "01", "-1", "1.0", "9223372036854775808", "abc", "%201", "+1"})
    void malformedIdsAreRejectedRatherThanCoerced(String bad) throws Exception {
        var u = shop("HAIRCUT");
        expect(get("/api/v1/shops/" + bad + "/barbers", null), 400, "VALIDATION_FAILED");
        expect(get(path(u) + "?serviceId=" + bad, null), 400, "VALIDATION_FAILED");
    }

    @Test void blankServiceIdIsNotEquivalentToOmissionAndEmptyEligibleShopReturnsArray() throws Exception {
        var u = shop("HAIRCUT"); assertThat(data(get(path(u), null)).isEmpty()).isTrue();
        expect(get(path(u) + "?serviceId=", null), 400, "VALIDATION_FAILED");
        expect(get(path(u) + "?serviceId=%20", null), 400, "VALIDATION_FAILED");
    }

    @Test void upDownEnableAndBindingChangesBecomeVisibleOnTheNextRequest() throws Exception {
        var u = shop("HAIRCUT"); long a = service(u, "HAIRCUT", false), b = service(u, "HAIRCUT", true), id = barber(u, true, "变化", a);
        assertThat(data(get(path(u), null)).isEmpty()).isTrue();
        jdbc.update("UPDATE service_item SET enabled=TRUE,version=version+1 WHERE id=?", a);
        assertThat(ids(data(get(path(u), null)))).containsExactly("" + id);
        links.insert(id, b); assertThat(strings(data(get(path(u) + "?serviceId=" + a, null)).get(0).path("serviceIds"))).containsExactly("" + a, "" + b);
        links.delete(id, a); assertThat(data(get(path(u) + "?serviceId=" + a, null)).isEmpty()).isTrue();
        jdbc.update("UPDATE barber SET enabled=FALSE,version=version+1 WHERE id=?", id); assertThat(data(get(path(u), null)).isEmpty()).isTrue();
        jdbc.update("UPDATE barber SET enabled=TRUE,version=version+1 WHERE id=?", id); assertThat(ids(data(get(path(u), null)))).containsExactly("" + id);
        jdbc.update("UPDATE service_item SET enabled=FALSE,version=version+1 WHERE id=?", b); assertThat(data(get(path(u), null)).isEmpty()).isTrue();
    }

    @Test void oneJoinedQueryLoadsAllPeopleAndFiftySortedPublicServiceIdsWithoutWrites() throws Exception {
        var u = shop("HAIRCUT"); var serviceIds = new ArrayList<Long>();
        for (int i = 0; i < 50; i++) { serviceIds.add(service(u, "HAIRCUT", true)); }
        var reversed = new ArrayList<>(serviceIds); Collections.reverse(reversed);
        for (int i = 0; i < 8; i++) { barber(u, true, "人员" + i, reversed.stream().mapToLong(Long::longValue).toArray()); }
        var before = snapshot(u); clearInvocations(barbers, services);
        var all = data(get(path(u), null)); assertThat(all.size()).isEqualTo(8);
        for (var person : all) { assertThat(strings(person.path("serviceIds"))).isEqualTo(serviceIds.stream().map(String::valueOf).toList()); }
        verify(barbers, times(1)).rows(u.shop(), null); verify(services, never()).findOwn(anyLong(), anyLong());
        clearInvocations(barbers, services); data(get(path(u) + "?serviceId=" + serviceIds.getFirst(), null));
        verify(barbers, times(1)).rows(u.shop(), serviceIds.getFirst()); verify(services, times(1)).findOwn(serviceIds.getFirst(), u.shop());
        assertThat(snapshot(u)).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"STUDENT", "MERCHANT", "ADMIN"})
    void publicAccessAcceptsActiveRolesButNeverIgnoresInvalidOrRevokedTokens(String role) throws Exception {
        var u = shop("HAIRCUT"); long a = service(u, "HAIRCUT", true); barber(u, true, "正常", a); var viewer = account(role); String token = login(viewer);
        expect(get(path(u), token), 200, "OK"); expect(get(path(u) + "?serviceId=" + a, token), 200, "OK");
        expect(get(path(u), "invalid"), 401, "TOKEN_INVALID");
        expect(call("POST", "/api/v1/auth/logout", null, token), 200, "OK"); expect(get(path(u), token), 401, "SESSION_REVOKED"); expect(get(path(u), null), 200, "OK");
    }

    @ParameterizedTest @ValueSource(strings = {"expired", "disabled"})
    void expiredAndDisabledViewersStillFailOnPublicEndpoint(String mode) throws Exception {
        var u = shop("HAIRCUT"); var viewer = account("STUDENT"); String token = login(viewer);
        if (mode.equals("expired")) { doReturn(Instant.now().plusSeconds(7201)).when(clock).instant(); }
        else { jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?", viewer.id()); }
        expect(get(path(u), token), mode.equals("expired") ? 401 : 403, mode.equals("expired") ? "SESSION_EXPIRED" : "ACCOUNT_DISABLED"); expect(get(path(u), null), 200, "OK");
    }

    @Test void otherMethodsAndFuturePathsRemainProtected() throws Exception {
        var u = shop("HAIRCUT");
        for (String method : List.of("POST", "PUT", "PATCH", "DELETE")) { expect(call(method, path(u), null, null), 401, "AUTH_REQUIRED"); }
        for (String suffix : List.of("/reviews/1", "/barbers/1", "/barbers/future")) { expect(get("/api/v1/shops/" + u.shop() + suffix, null), 401, "AUTH_REQUIRED"); }
        expect(get("/api/v1/merchant/barbers", null), 401, "AUTH_REQUIRED");
    }

    @ParameterizedTest @ValueSource(strings = {"shop", "service"})
    void realConcurrentChangesCannotMixEligibilityWithNewPersonnelOrBindings(String gate) throws Exception {
        var u = shop("HAIRCUT"); long a = service(u, "HAIRCUT", true), b = service(u, "HAIRCUT", true), id = barber(u, true, "旧姓名", a, b);
        var reached = new CountDownLatch(1); var release = new CountDownLatch(1);
        if (gate.equals("shop")) {
            var real = sqlSession.getMapper(PublicShopMapper.class);
            doAnswer(inv -> { var row = real.visible(inv.getArgument(0)); reached.countDown(); await(release); return row; }).when(catalog).visible(u.shop());
        } else {
            var real = sqlSession.getMapper(ServiceItemMapper.class);
            doAnswer(inv -> { var row = real.findOwn(inv.getArgument(0), inv.getArgument(1)); reached.countDown(); await(release); return row; }).when(services).findOwn(a, u.shop());
        }
        try (var pool = Executors.newSingleThreadExecutor()) {
            var response = pool.submit(() -> get(path(u) + (gate.equals("service") ? "?serviceId=" + a : ""), null));
            try {
                assertThat(reached.await(15, TimeUnit.SECONDS)).isTrue();
                new TransactionTemplate(manager).executeWithoutResult(tx -> {
                    if (gate.equals("shop")) { jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?", u.id()); }
                    jdbc.queryForObject("SELECT id FROM shop WHERE id=? FOR UPDATE", Long.class, u.shop());
                    jdbc.update("UPDATE service_item SET enabled=FALSE,version=version+1 WHERE id=?", a);
                    jdbc.update("UPDATE barber SET display_name='新姓名',enabled=FALSE,version=version+1 WHERE id=?", id);
                    links.delete(id, a); links.delete(id, b);
                });
            } finally { release.countDown(); }
            var view = data(response.get(20, TimeUnit.SECONDS)); assertThat(ids(view)).containsExactly("" + id);
            assertThat(view.get(0).path("displayName").asString()).isEqualTo("旧姓名"); assertThat(strings(view.get(0).path("serviceIds"))).containsExactly("" + a, "" + b);
        }
        expect(get(path(u) + "?serviceId=" + a, null), 404, "RESOURCE_NOT_FOUND");
    }

    private record User(long id, String name, long shop) {}
    private User account(String role) { var a = new AccountEntity(); a.setUsername("pbar_" + UUID.randomUUID().toString().replace("-", "").substring(0, 22)); a.setNickname("公开人员测试"); a.setPasswordHash(hash); a.setRole(role); a.setStatus("ACTIVE"); a.setVersion(0L); accounts.insert(a); accountIds.add(a.getId()); return new User(a.getId(), a.getUsername(), 0); }
    private User shop(String category) { var a = account("MERCHANT"); var s = new ShopEntity(); s.setCategoryId(jdbc.queryForObject("SELECT id FROM shop_category WHERE code=?", Long.class, category)); s.setName("虚构公开人员店"); s.setLocationText("虚构校区"); s.setDescription(""); s.setCampusCode("XINGHAI_MAIN"); s.setApprovalStatus("APPROVED"); s.setBusinessEnabled(false); s.setVersion(1L); shops.insert(s); shopIds.add(s.getId()); jdbc.update("INSERT INTO shop_member (shop_id,account_id,status) VALUES (?,?,'ACTIVE')", s.getId(), a.id()); return new User(a.id(), a.name(), s.getId()); }
    private long service(User u, String type, boolean enabled) { var s = new ServiceItemEntity(); s.setShopId(u.shop()); s.setServiceType(type); s.setName("模拟服务"); s.setDescription(""); s.setPriceAmount(3000L); s.setDurationMinutes(type.equals("PRINT") ? null : 30); s.setEnabled(enabled); s.setVersion(0L); services.insert(s); return s.getId(); }
    private long barber(User u, boolean enabled, String name, long... ids) { var b = new BarberEntity(); b.setShopId(u.shop()); b.setDisplayName(name); b.setEnabled(enabled); b.setVersion(0L); people.insert(b); for (long id : ids) { links.insert(b.getId(), id); } return b.getId(); }
    private static String path(User u) { return "/api/v1/shops/" + u.shop() + "/barbers"; }
    private List<?> snapshot(User u) { return List.of(jdbc.queryForMap("SELECT * FROM shop WHERE id=?", u.shop()), jdbc.queryForList("SELECT * FROM barber WHERE shop_id=? ORDER BY id", u.shop()), jdbc.queryForList("SELECT bs.* FROM barber_service bs JOIN barber b ON b.id=bs.barber_id WHERE b.shop_id=? ORDER BY bs.id", u.shop()), jdbc.queryForList("SELECT * FROM service_item WHERE shop_id=? ORDER BY id", u.shop())); }
    private List<String> strings(JsonNode items) { var list = new ArrayList<String>(); for (var item : items) { assertThat(item.isString()).isTrue(); list.add(item.asString()); } return list; }
    private List<String> ids(JsonNode items) { var list = new ArrayList<String>(); for (var item : items) { assertThat(item.path("id").isString()).isTrue(); list.add(item.path("id").asString()); } return list; }
    private String login(User u) throws Exception { return expect(call("POST", "/api/v1/auth/login", Map.of("username", u.name(), "password", PASSWORD), null), 200, "OK").path("data").path("accessToken").asString(); }
    private JsonNode data(HttpResponse<String> response) throws Exception { return expect(response, 200, "OK").path("data"); }
    private JsonNode expect(HttpResponse<String> response, int status, String code) throws Exception { assertThat(response.statusCode()).as(response.body()).isEqualTo(status); var body = json.readTree(response.body()); assertThat(body.path("code").asString()).isEqualTo(code); assertThat(body.path("traceId").asString()).isEqualTo(response.headers().firstValue("X-Trace-Id").orElseThrow()); return body; }
    private HttpResponse<String> get(String path, String token) throws Exception { return call("GET", path, null, token); }
    private HttpResponse<String> call(String method, String path, Object body, String token) throws Exception { var req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + environment.getRequiredProperty("local.server.port") + path)).timeout(Duration.ofSeconds(25)); if (token != null) { req.header("Authorization", "Bearer " + token); } if (body != null) { req.header("Content-Type", "application/json"); } req.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8)); return client.send(req.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)); }
    private static void await(CountDownLatch latch) { try { if (!latch.await(15, TimeUnit.SECONDS)) { throw new IllegalStateException("Test gate timed out"); } } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); } }
}
