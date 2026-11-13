package com.campuslife;

import com.campuslife.auth.entity.AccountEntity;
import com.campuslife.auth.mapper.AccountMapper;
import com.campuslife.merchant.entity.ServiceItemEntity;
import com.campuslife.merchant.entity.ShopEntity;
import com.campuslife.merchant.mapper.*;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "campuslife.booking.expiry-enabled=false")
@ActiveProfiles("mysql")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PublicShopCatalogMysqlIT {
    private static final String BASE = "/api/v1/shops";
    private static final String PASSWORD = "Public_Catalog123!";
    private final HttpClient client = HttpClient.newHttpClient();
    private final List<Long> accountIds = new ArrayList<>(), shopIds = new ArrayList<>();
    private String hash, prefix;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired Environment environment;
    @Autowired PasswordEncoder passwords;
    @Autowired AccountMapper accounts;
    @Autowired ShopMapper shops;
    @Autowired ServiceItemMapper services;
    @Autowired PlatformTransactionManager manager;
    @Autowired org.mybatis.spring.SqlSessionTemplate sqlSession;
    @MockitoSpyBean PublicShopMapper catalog;
    @MockitoSpyBean BusinessHourMapper hours;
    @MockitoSpyBean Clock clock;

    @BeforeAll void hash() { hash = passwords.encode(PASSWORD); }
    @BeforeEach void uniquePrefix() { prefix = "公开_" + UUID.randomUUID().toString().substring(0, 12); }
    @AfterEach void cleanup() {
        reset(catalog, hours, clock);
        jdbc.update("UPDATE shop_category SET enabled=TRUE WHERE code IN ('HAIRCUT','PRINT')");
        for (long shop : shopIds) {
            jdbc.update("DELETE FROM service_item WHERE shop_id=?", shop);
            jdbc.update("DELETE FROM shop_business_hour WHERE shop_id=?", shop);
            jdbc.update("DELETE FROM shop_member WHERE shop_id=?", shop);
            jdbc.update("DELETE FROM shop WHERE id=?", shop);
        }
        for (long id : accountIds) { jdbc.update("DELETE FROM auth_session WHERE account_id=?", id); jdbc.update("DELETE FROM account WHERE id=?", id); }
        accountIds.clear(); shopIds.clear();
    }

    @Test void anonymousPagesAreOrderedFilteredAndExposeOnlyPublicFields() throws Exception {
        var a = fixture("HAIRCUT", "Z"); var b = fixture("PRINT", "A"); var c = fixture("HAIRCUT", "B");
        var first = data(get(list() + "&size=2", null));
        assertThat(first.path("page").asInt()).isEqualTo(1); assertThat(first.path("size").asInt()).isEqualTo(2);
        assertThat(first.path("total").asLong()).isEqualTo(3); assertThat(first.path("totalPages").asLong()).isEqualTo(2);
        assertThat(ids(first.path("items"))).containsExactly("" + a.shop(), "" + b.shop());
        var second = data(get(list() + "&size=2&page=2", null)); assertThat(ids(second.path("items"))).containsExactly("" + c.shop());
        assertThat(data(get(list() + "&categoryId=" + category("HAIRCUT"), null)).path("total").asLong()).isEqualTo(2);
        assertThat(data(get(BASE + "?keyword=" + enc(prefix + "Z") + "&categoryId=" + category("PRINT"), null)).path("total").asLong()).isZero();
        var beyond = data(get(list() + "&page=2147483647&size=100", null));
        assertThat(beyond.path("items").isEmpty()).isTrue(); assertThat(beyond.path("total").asLong()).isEqualTo(3);
        assertThat(beyond.path("page").asInt()).isEqualTo(Integer.MAX_VALUE);
        assertThat(data(get(list() + "&categoryId=9223372036854775807", null)).path("total").asLong()).isZero();
        assertThat(data(get(BASE, null)).path("size").asInt()).isEqualTo(20);
        var item = first.path("items").get(0);
        assertThat(item.propertyNames()).containsExactlyInAnyOrder("id", "name", "category", "locationText", "businessEnabled", "openNow", "rating", "reviewCount");
        assertThat(item.path("rating").isNull()).isTrue(); assertThat(item.path("reviewCount").asLong()).isZero();
        var detail = data(get(path(a), null));
        assertThat(detail.propertyNames()).containsExactlyInAnyOrder("id", "name", "category", "locationText", "businessEnabled", "openNow", "rating", "reviewCount", "description", "businessHours", "campusCode");
        assertThat(detail.path("description").asString()).isEqualTo("公开介绍"); assertThat(detail.path("campusCode").asString()).isEqualTo("XINGHAI_MAIN");
        assertThat(detail.path("businessHours").size()).isEqualTo(14);
        assertThat(detail.path("category").propertyNames()).containsExactlyInAnyOrder("id", "code", "name");
        assertThat(detail.toString()).doesNotContain(a.name(), "passwordHash", "approvalReason", "memberStatus", "version", "accountId", "jti");
    }

    @ParameterizedTest @ValueSource(strings = {"pending", "rejected", "suspended", "ownerPending", "ownerDisabled", "ownerMissing", "accountDisabled", "student", "admin"})
    void allThreeRoutesHideIneligibleShops(String mode) throws Exception {
        var u = fixture("HAIRCUT", "隐藏"); service(u, true);
        switch (mode) {
            case "pending", "rejected", "suspended" -> jdbc.update("UPDATE shop SET approval_status=? WHERE id=?", mode.toUpperCase(Locale.ROOT), u.shop());
            case "ownerPending", "ownerDisabled" -> jdbc.update("UPDATE shop_member SET status=? WHERE shop_id=?", mode.equals("ownerPending") ? "PENDING" : "DISABLED", u.shop());
            case "ownerMissing" -> jdbc.update("DELETE FROM shop_member WHERE shop_id=?", u.shop());
            case "accountDisabled" -> jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?", u.id());
            case "student", "admin" -> jdbc.update("UPDATE account SET role=? WHERE id=?", mode.toUpperCase(Locale.ROOT), u.id());
            default -> throw new IllegalStateException(mode);
        }
        assertThat(data(get(list(), null)).path("total").asLong()).isZero();
        expect(get(path(u), null), 404, "RESOURCE_NOT_FOUND"); expect(get(path(u) + "/services", null), 404, "RESOURCE_NOT_FOUND");
    }

    @Test void closedUnconfiguredAndDisabledCategoryShopsRemainVisible() throws Exception {
        var u = fixture("HAIRCUT", "停业"); service(u, true);
        jdbc.update("UPDATE shop SET business_enabled=FALSE WHERE id=?", u.shop());
        jdbc.update("DELETE FROM shop_business_hour WHERE shop_id=?", u.shop());
        jdbc.update("UPDATE shop_category SET enabled=FALSE WHERE code='HAIRCUT'");
        assertThat(data(get(list() + "&categoryId=" + category("HAIRCUT"), null)).path("total").asLong()).isEqualTo(1);
        var detail = data(get(path(u), null)); assertThat(detail.path("businessHours").isEmpty()).isTrue(); assertThat(detail.path("openNow").asBoolean()).isFalse();
        assertThat(data(get(path(u) + "/services", null)).size()).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {"%", "_", "!", "\\", "' OR 1=1 --", "😀"})
    void searchTreatsWildcardsAndSpecialCharactersAsLiteralNameContent(String value) throws Exception {
        var yes = fixture("HAIRCUT", "命中" + value + "后缀"); fixture("HAIRCUT", "普通后缀");
        var result = data(get(BASE + "?keyword=" + enc("  " + prefix + "命中" + value + "  "), null));
        assertThat(ids(result.path("items"))).containsExactly("" + yes.shop());
    }

    @Test void blankKeywordsDoNotFilterAndEightyCodePointsAreAccepted() throws Exception {
        var u = fixture("HAIRCUT", "😀");
        for (String keyword : List.of("", "   ", "\u3000")) {
            assertThat(data(get(BASE + "?keyword=" + enc(keyword), null)).path("total").asLong()).isGreaterThanOrEqualTo(1);
        }
        jdbc.update("UPDATE shop SET name=? WHERE id=?", "😀".repeat(80), u.shop());
        assertThat(data(get(BASE + "?keyword=" + enc("😀".repeat(80)), null)).path("total").asLong()).isEqualTo(1);
        expect(get(BASE + "?keyword=" + enc("😀".repeat(81)), null), 400, "VALIDATION_FAILED");
    }

    static Stream<String> badQueries() {
        return Stream.of("page=0", "page=-1", "page=1.0", "page=2147483648", "page=", "page=true", "size=0", "size=101", "size=2147483648", "size=1.0", "size=", "categoryId=0", "categoryId=01", "categoryId=-1", "categoryId=1.0", "categoryId=9223372036854775808", "categoryId=", "categoryId=%201");
    }
    @ParameterizedTest @MethodSource("badQueries")
    void invalidPaginationAndCategoryReturn400(String query) throws Exception { expect(get(BASE + "?" + query, null), 400, "VALIDATION_FAILED"); }

    @ParameterizedTest @ValueSource(strings = {"0", "01", "-1", "1.0", "9223372036854775808", "abc"})
    void invalidShopIdsAreRejectedOnBothRoutes(String id) throws Exception {
        for (String suffix : List.of("", "/services")) { expect(get(BASE + "/" + id + suffix, null), 400, "VALIDATION_FAILED"); }
    }

    @Test void servicesExposeOnlyEnabledOwnRowsAndPrintingIsReferencePrice() throws Exception {
        var haircut = fixture("HAIRCUT", "理发"); var print = fixture("PRINT", "打印");
        long a = service(haircut, true), hidden = service(haircut, false), b = service(haircut, true); service(print, true);
        var list = data(get(path(haircut) + "/services", null)); assertThat(ids(list)).containsExactly("" + b, "" + a).doesNotContain("" + hidden);
        assertThat(list.get(0).propertyNames()).containsExactlyInAnyOrder("id", "serviceType", "name", "description", "priceAmount", "durationMinutes", "version");
        assertThat(list.get(0).path("priceAmount").asLong()).isEqualTo(3000); assertThat(list.get(0).path("durationMinutes").asInt()).isEqualTo(30);
        assertThat(data(get(path(haircut) + "/services?serviceType=HAIRCUT", null))).isEqualTo(list);
        assertThat(data(get(path(haircut) + "/services?serviceType=PRINT", null)).isEmpty()).isTrue();
        assertThat(data(get(path(print) + "/services", null)).get(0).path("durationMinutes").isNull()).isTrue();
        for (String type : List.of("haircut", "OTHER", "", "null")) { expect(get(path(haircut) + "/services?serviceType=" + type, null), 400, "VALIDATION_FAILED"); }
        var empty = fixture("HAIRCUT", "空服务"); assertThat(data(get(path(empty) + "/services", null)).isEmpty()).isTrue();
        for (String suffix : List.of("", "/services")) { expect(get(BASE + "/9223372036854775807" + suffix, null), 404, "RESOURCE_NOT_FOUND"); }
        jdbc.update("UPDATE service_item SET enabled=FALSE WHERE id=?", b);
        assertThat(ids(data(get(path(haircut) + "/services", null)))).containsExactly("" + a);
    }

    @ParameterizedTest @ValueSource(strings = {"STUDENT", "MERCHANT", "ADMIN"})
    void anyActiveRoleMayBrowseButInvalidTokensAreNotIgnored(String role) throws Exception {
        var u = fixture("HAIRCUT", "访问"); var viewer = account(role); String token = login(viewer);
        for (String route : routes(u)) { expect(get(route, token), 200, "OK"); expect(get(route, "invalid"), 401, "TOKEN_INVALID"); }
        expect(call("POST", "/api/v1/auth/logout", null, token), 200, "OK");
        for (String route : routes(u)) { expect(get(route, token), 401, "SESSION_REVOKED"); expect(get(route, null), 200, "OK"); }
    }

    @ParameterizedTest @ValueSource(strings = {"expired", "disabled"})
    void expiredOrDisabledViewerCannotBypassAuthenticationOnPublicRoutes(String mode) throws Exception {
        var u = fixture("HAIRCUT", "访问"); var viewer = account("STUDENT"); String token = login(viewer);
        if (mode.equals("expired")) { fixed(Instant.now().plusSeconds(7201)); }
        else { jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?", viewer.id()); }
        for (String route : routes(u)) { expect(get(route, token), mode.equals("expired") ? 401 : 403, mode.equals("expired") ? "SESSION_EXPIRED" : "ACCOUNT_DISABLED"); expect(get(route, null), 200, "OK"); }
    }

    @Test void onlyImplementedGetRoutesBecomePublic() throws Exception {
        var u = fixture("HAIRCUT", "路由");
        for (String suffix : List.of("/services/1", "/future")) { expect(get(path(u) + suffix, null), 401, "AUTH_REQUIRED"); }
        for (String method : List.of("POST", "PUT", "PATCH", "DELETE")) { expect(call(method, path(u), null, null), 401, "AUTH_REQUIRED"); }
        expect(get("/api/v1/merchant/services", null), 401, "AUTH_REQUIRED");
    }

    static Stream<Arguments> openingTimes() {
        return Stream.of(Arguments.of("2026-10-21T00:59:59.999Z", false), Arguments.of("2026-10-21T01:00:00Z", true),
                Arguments.of("2026-10-21T03:59:59.999Z", true), Arguments.of("2026-10-21T04:00:00Z", false),
                Arguments.of("2026-10-21T05:59:59.999Z", false), Arguments.of("2026-10-21T06:00:00Z", true),
                Arguments.of("2026-10-21T10:00:00Z", false));
    }
    @ParameterizedTest @MethodSource("openingTimes")
    void openingUsesShanghaiClockAndHalfOpenIntervals(String instant, boolean open) throws Exception {
        var u = fixture("HAIRCUT", "营业"); fixed(Instant.parse(instant));
        assertThat(data(get(path(u), null)).path("openNow").asBoolean()).isEqualTo(open);
        assertThat(data(get(list(), null)).path("items").get(0).path("openNow").asBoolean()).isEqualTo(open);
        jdbc.update("UPDATE shop SET business_enabled=FALSE WHERE id=?", u.shop()); assertThat(data(get(path(u), null)).path("openNow").asBoolean()).isFalse();
    }

    @Test void openingRespectsLocalWeekdayMidnightRestDayAndNoHours() throws Exception {
        var u = fixture("HAIRCUT", "跨日"); jdbc.update("DELETE FROM shop_business_hour WHERE shop_id=?", u.shop());
        jdbc.update("INSERT INTO shop_business_hour (shop_id,weekday,start_time,end_time) VALUES (?,4,'00:00','01:00')", u.shop());
        fixed(Instant.parse("2026-10-21T15:59:59.999Z")); assertThat(data(get(path(u), null)).path("openNow").asBoolean()).isFalse();
        fixed(Instant.parse("2026-10-21T16:00:00Z")); assertThat(data(get(path(u), null)).path("openNow").asBoolean()).isTrue();
        fixed(Instant.parse("2026-10-22T16:00:00Z")); assertThat(data(get(path(u), null)).path("openNow").asBoolean()).isFalse();
        jdbc.update("DELETE FROM shop_business_hour WHERE shop_id=?", u.shop()); assertThat(data(get(path(u), null)).path("openNow").asBoolean()).isFalse();
    }

    @Test void listBatchesHoursAndUsesOneClockInstantForAllShopsWithoutWriting() throws Exception {
        var a = fixture("HAIRCUT", "甲"); var b = fixture("HAIRCUT", "乙"); fixed(Instant.parse("2026-10-21T01:00:00Z"));
        var before = List.of(snapshot(a), snapshot(b)); clearInvocations(clock, hours);
        var result = data(get(list(), null)); assertThat(result.path("items").size()).isEqualTo(2);
        for (var item : result.path("items")) { assertThat(item.path("openNow").asBoolean()).isTrue(); }
        verify(clock, times(1)).instant(); verify(hours, times(1)).batchRows(List.of(a.shop(), b.shop())); verify(hours, never()).rows(anyLong());
        assertThat(List.of(snapshot(a), snapshot(b))).isEqualTo(before);
        clearInvocations(hours); data(get(list() + "&page=100", null)); verify(hours, never()).batchRows(anyList());
    }

    @ParameterizedTest @ValueSource(strings = {"list", "categorizedList", "detail", "services"})
    void concurrentVisibilityHoursAndServiceWritesDoNotTearReadSnapshots(String route) throws Exception {
        var u = fixture("HAIRCUT", "快照"); long id = service(u, true); fixed(Instant.parse("2026-10-21T01:30:00Z"));
        var reached = new CountDownLatch(1); var release = new CountDownLatch(1); var real = sqlSession.getMapper(PublicShopMapper.class);
        boolean listing=route.equals("list")||route.equals("categorizedList");
        String listRoute=route.equals("categorizedList")?BASE+"?categoryId="+category("HAIRCUT"):list();
        if (listing) {
            doAnswer(inv -> { long total = real.count(inv.getArgument(0), inv.getArgument(1)); reached.countDown(); await(release); return total; }).when(catalog).count(any(), any());
        } else {
            doAnswer(inv -> { var row = real.visible(inv.getArgument(0)); reached.countDown(); await(release); return row; }).when(catalog).visible(u.shop());
        }
        try (var pool = Executors.newSingleThreadExecutor()) {
            var response = pool.submit(() -> get(listing ? listRoute : path(u) + (route.equals("services") ? "/services" : ""), null));
            try {
                assertThat(reached.await(15, TimeUnit.SECONDS)).isTrue();
                new TransactionTemplate(manager).executeWithoutResult(tx -> {
                    jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?", u.id());
                    jdbc.update("UPDATE shop SET name='新名称',business_enabled=FALSE,version=version+1 WHERE id=?", u.shop());
                    jdbc.update("DELETE FROM shop_business_hour WHERE shop_id=?", u.shop());
                    jdbc.update("UPDATE service_item SET enabled=FALSE,price_amount=5000,version=version+1 WHERE id=?", id);
                });
            } finally { release.countDown(); }
            var view = data(response.get(20, TimeUnit.SECONDS));
            if (route.equals("services")) { assertThat(view.size()).isEqualTo(1); assertThat(view.get(0).path("priceAmount").asLong()).isEqualTo(3000); }
            else {
                if (listing) { assertThat(view.path("total").asLong()).isEqualTo(1); view = view.path("items").get(0); }
                else { assertThat(view.path("businessHours").size()).isEqualTo(14); }
                assertThat(view.path("name").asString()).isEqualTo(prefix + "快照"); assertThat(view.path("openNow").asBoolean()).isTrue();
            }
        }
        if (listing) { assertThat(data(get(listRoute, null)).path("total").asLong()).isZero(); }
        else { expect(get(path(u) + (route.equals("services") ? "/services" : ""), null), 404, "RESOURCE_NOT_FOUND"); }
    }

    @ParameterizedTest @ValueSource(strings={"HAIRCUT","PRINT"})
    void categoryFirstPagePreservesVisibilityOrderAndBlankKeywordNormalization(String type)throws Exception{
        var a=fixture(type,"首页甲");var b=fixture(type,"首页乙");var hidden=fixture(type,"隐藏");
        jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?",hidden.id());
        jdbc.update("UPDATE shop SET business_enabled=FALSE WHERE id=?",b.shop());
        jdbc.update("UPDATE shop_category SET enabled=FALSE WHERE code=?",type);
        long categoryId=category(type);clearInvocations(catalog);
        String first=BASE+"?categoryId="+categoryId;
        for(String suffix:List.of("","&keyword=%20%20","&size=100")){
            var result=data(get(first+suffix,null));assertThat(result.path("total").asLong()).isEqualTo(2);
            assertThat(ids(result.path("items"))).containsExactly(Long.toString(a.shop()),Long.toString(b.shop()));
            assertThat(result.path("items").get(1).path("openNow").asBoolean()).isFalse();
        }
        verify(catalog,times(2)).categoryFirstPage(categoryId,20);verify(catalog,times(1)).categoryFirstPage(categoryId,100);
        verify(catalog,never()).page(any(),any(),anyLong(),anyInt());
        var second=data(get(first+"&page=2&size=1",null));assertThat(ids(second.path("items"))).containsExactly(Long.toString(b.shop()));
        verify(catalog,times(1)).page(categoryId,null,1L,1);
    }

    @ParameterizedTest @ValueSource(strings={"search","later","uncategorized"})
    void otherListShapesKeepTheOriginalPaginationPath(String shape)throws Exception{
        var u=fixture("HAIRCUT","原查询");long id=category("HAIRCUT");clearInvocations(catalog);
        String query=switch(shape){case "search"->BASE+"?categoryId="+id+"&keyword="+enc(prefix);case "later"->BASE+"?categoryId="+id+"&page=2";default->BASE;};
        var value=data(get(query,null));assertThat(value.path("total").asLong()).isEqualTo(1);
        if(shape.equals("later")){assertThat(value.path("items").isEmpty()).isTrue();}else{assertThat(ids(value.path("items"))).containsExactly(Long.toString(u.shop()));}
        verify(catalog,never()).categoryFirstPage(anyLong(),anyInt());verify(catalog,times(1)).page(any(),any(),anyLong(),anyInt());
    }

    private record User(long id, String name, long shop, String category) {}
    private User account(String role) {
        var a = new AccountEntity(); a.setUsername("pub_" + UUID.randomUUID().toString().replace("-", "").substring(0, 22)); a.setNickname("公开测试"); a.setPasswordHash(hash); a.setRole(role); a.setStatus("ACTIVE"); a.setVersion(0L); accounts.insert(a); accountIds.add(a.getId()); return new User(a.getId(), a.getUsername(), 0, "");
    }
    private User fixture(String category, String suffix) {
        var a = account("MERCHANT"); var s = new ShopEntity(); s.setCategoryId(category(category)); s.setName(prefix + suffix); s.setLocationText("虚构校区"); s.setDescription("公开介绍"); s.setCampusCode("XINGHAI_MAIN"); s.setApprovalStatus("APPROVED"); s.setBusinessEnabled(true); s.setVersion(1L); shops.insert(s); shopIds.add(s.getId());
        jdbc.update("INSERT INTO shop_member (shop_id,account_id,status) VALUES (?,?,'ACTIVE')", s.getId(), a.id());
        for (int day = 1; day <= 7; day++) { jdbc.update("INSERT INTO shop_business_hour (shop_id,weekday,start_time,end_time) VALUES (?,?,'09:00','12:00'),(?,?,'14:00','18:00')", s.getId(), day, s.getId(), day); }
        return new User(a.id(), a.name(), s.getId(), category);
    }
    private long category(String code) { return jdbc.queryForObject("SELECT id FROM shop_category WHERE code=?", Long.class, code); }
    private long service(User u, boolean enabled) { var s = new ServiceItemEntity(); s.setShopId(u.shop()); s.setServiceType(u.category()); s.setName("虚构服务"); s.setDescription(""); s.setPriceAmount(3000L); s.setDurationMinutes(u.category().equals("PRINT") ? null : 30); s.setEnabled(enabled); s.setVersion(0L); services.insert(s); return s.getId(); }
    private String list() { return BASE + "?keyword=" + enc(prefix); }
    private static String path(User u) { return BASE + "/" + u.shop(); }
    private List<String> routes(User u) { return List.of(list(), path(u), path(u) + "/services"); }
    private static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private void fixed(Instant instant) { doReturn(instant).when(clock).instant(); }
    private List<?> snapshot(User u) { return List.of(jdbc.queryForMap("SELECT * FROM shop WHERE id=?", u.shop()), jdbc.queryForMap("SELECT * FROM account WHERE id=?", u.id()), jdbc.queryForList("SELECT * FROM shop_business_hour WHERE shop_id=? ORDER BY weekday,start_time", u.shop()), jdbc.queryForList("SELECT * FROM service_item WHERE shop_id=?", u.shop())); }
    private List<String> ids(JsonNode items) { var ids = new ArrayList<String>(); for (var item : items) { ids.add(item.path("id").asString()); } return ids; }
    private String login(User u) throws Exception { return expect(call("POST", "/api/v1/auth/login", Map.of("username", u.name(), "password", PASSWORD), null), 200, "OK").path("data").path("accessToken").asString(); }
    private JsonNode data(HttpResponse<String> response) throws Exception { return expect(response, 200, "OK").path("data"); }
    private JsonNode expect(HttpResponse<String> response, int status, String code) throws Exception { assertThat(response.statusCode()).as(response.body()).isEqualTo(status); var body = json.readTree(response.body()); assertThat(body.path("code").asString()).isEqualTo(code); assertThat(body.path("traceId").asString()).isEqualTo(response.headers().firstValue("X-Trace-Id").orElseThrow()); return body; }
    private HttpResponse<String> get(String path, String token) throws Exception { return call("GET", path, null, token); }
    private HttpResponse<String> call(String method, String path, Object body, String token) throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + environment.getRequiredProperty("local.server.port") + path)).timeout(Duration.ofSeconds(25));
        if (token != null) { req.header("Authorization", "Bearer " + token); } if (body != null) { req.header("Content-Type", "application/json"); }
        req.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8));
        return client.send(req.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
    private static void await(CountDownLatch latch) { try { if (!latch.await(15, TimeUnit.SECONDS)) { throw new IllegalStateException("Test gate timed out"); } } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); } }
}
