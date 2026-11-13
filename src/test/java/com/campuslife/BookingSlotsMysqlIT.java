package com.campuslife;

import com.campuslife.auth.entity.AccountEntity;
import com.campuslife.auth.mapper.AccountMapper;
import com.campuslife.merchant.entity.*;
import com.campuslife.merchant.mapper.*;
import java.net.URI;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "campuslife.booking.expiry-enabled=false")
@ActiveProfiles("mysql")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BookingSlotsMysqlIT {
    private static final LocalDate DATE = LocalDate.of(2026, 10, 22);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant NOW = at(DATE.minusDays(1), "18:00");
    private static final String PASSWORD = "Slot_Query123!";
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
    @Autowired BarberServiceMapper bindings;
    @Autowired PlatformTransactionManager manager;
    @Autowired org.mybatis.spring.SqlSessionTemplate sqlSession;
    @MockitoSpyBean PublicShopMapper catalog;
    @MockitoSpyBean ServiceItemMapper services;
    @MockitoSpyBean BusinessHourMapper hours;
    @MockitoSpyBean BarberScheduleMapper schedules;
    @MockitoSpyBean Clock clock;

    @BeforeAll void hash() { hash = passwords.encode(PASSWORD); }
    @BeforeEach void freeze() { fixed(NOW); }
    @AfterEach void cleanup() {
        reset(catalog, services, hours, schedules, clock);
        for (long shop : shopIds) {
            jdbc.update("DELETE s FROM barber_schedule s JOIN barber b ON b.id=s.barber_id WHERE b.shop_id=?", shop);
            jdbc.update("DELETE g FROM barber_day_guard g JOIN barber b ON b.id=g.barber_id WHERE b.shop_id=?", shop);
            jdbc.update("DELETE bs FROM barber_service bs JOIN barber b ON b.id=bs.barber_id WHERE b.shop_id=?", shop);
        }
        for (long shop : shopIds) {
            jdbc.update("DELETE FROM barber WHERE shop_id=?", shop); jdbc.update("DELETE FROM service_item WHERE shop_id=?", shop);
            jdbc.update("DELETE FROM shop_business_hour WHERE shop_id=?", shop); jdbc.update("DELETE FROM shop_member WHERE shop_id=?", shop); jdbc.update("DELETE FROM shop WHERE id=?", shop);
        }
        for (long id : accountIds) { jdbc.update("DELETE FROM auth_session WHERE account_id=?", id); jdbc.update("DELETE FROM account WHERE id=?", id); }
        jdbc.update("UPDATE shop_category SET enabled=TRUE WHERE code IN ('HAIRCUT','PRINT')"); accountIds.clear(); shopIds.clear();
    }

    @Test void returnsThreeUtcAlternativesAndServerMetadataWithoutChangingConfiguration() throws Exception {
        var u = fixture(); var before = snapshot(u); clearInvocations(clock);
        var data = data(get(url(u), null)); assertThat(data.propertyNames()).containsExactlyInAnyOrder("serviceVersion", "priceAmount", "durationMinutes", "slots");
        assertThat(data.path("serviceVersion").asString()).isEqualTo("0"); assertThat(data.path("priceAmount").asLong()).isEqualTo(3000); assertThat(data.path("durationMinutes").asInt()).isEqualTo(30);
        assertThat(starts(data)).containsExactly(at(DATE, "09:00"), at(DATE, "09:15"), at(DATE, "09:30"));
        assertThat(Instant.parse(data.path("slots").get(2).path("endAt").asString())).isEqualTo(Instant.parse("2026-10-22T02:00:00Z"));
        for (var slot : data.path("slots")) { assertThat(slot.propertyNames()).containsExactlyInAnyOrder("startAt", "endAt"); assertThat(slot.path("startAt").asString()).endsWith("Z"); }
        verify(clock, times(1)).instant(); assertThat(snapshot(u)).isEqualTo(before);
        var attemptedOverride = data(get(url(u) + "&durationMinutes=15&priceAmount=1", null)); assertThat(attemptedOverride).isEqualTo(data);
    }

    @ParameterizedTest @CsvSource({"-1,09:00", "0,09:00", "1,09:15"})
    void exactTwoHourBoundarySurvivesTheHttpAndDatabaseRoundTrip(long millis, String first) throws Exception {
        var u = fixture(); fixed(at(DATE, "07:00").plusMillis(millis));
        assertThat(starts(data(get(url(u), null))).getFirst()).isEqualTo(at(DATE, first));
    }

    @ParameterizedTest @ValueSource(strings = {"closed", "noHours", "restDay", "noSchedule", "shortShift", "tooLate"})
    void emptyResultsKeepValidServiceMetadataAndDoNotCreateGuards(String mode) throws Exception {
        var u = fixture();
        switch (mode) {
            case "closed" -> jdbc.update("UPDATE shop SET business_enabled=FALSE WHERE id=?", u.shop());
            case "noHours" -> jdbc.update("DELETE FROM shop_business_hour WHERE shop_id=?", u.shop());
            case "restDay" -> jdbc.update("UPDATE shop_business_hour SET weekday=5 WHERE shop_id=?", u.shop());
            case "noSchedule" -> { jdbc.update("DELETE FROM barber_schedule WHERE barber_id=?", u.barber()); jdbc.update("DELETE FROM barber_day_guard WHERE barber_id=?", u.barber()); }
            case "shortShift" -> { jdbc.update("DELETE FROM barber_schedule WHERE barber_id=?", u.barber()); shift(u, DATE, "09:00", "09:15"); }
            case "tooLate" -> fixed(at(DATE, "07:45"));
            default -> throw new IllegalStateException(mode);
        }
        var before = snapshot(u); clearInvocations(schedules); var result = data(get(url(u), null));
        assertThat(result.path("slots").isEmpty()).isTrue(); assertThat(result.path("priceAmount").asLong()).isEqualTo(3000);
        assertThat(result.path("serviceVersion").asString()).isEqualTo("0"); assertThat(result.path("durationMinutes").asInt()).isEqualTo(30); assertThat(snapshot(u)).isEqualTo(before);
        verify(schedules, never()).insertGuard(anyLong(), any(LocalDate.class)); verify(schedules, never()).lockGuard(anyLong(), any(LocalDate.class));
    }

    @Test void currentClosedHoursDoNotSuppressTomorrowAndCategoryDisableDoesNotHideTheShop() throws Exception {
        var u = fixture(); jdbc.update("UPDATE shop_category SET enabled=FALSE WHERE code='HAIRCUT'");
        // NOW is 18:00 the previous campus day, which has no business hours at all.
        assertThat(starts(data(get(url(u), null)))).hasSize(3);
        jdbc.update("UPDATE service_item SET price_amount=4500,duration_minutes=45,version=version+1 WHERE id=?", u.service());
        var result = data(get(url(u), null)); assertThat(result.path("priceAmount").asLong()).isEqualTo(4500);
        assertThat(result.path("serviceVersion").asString()).isEqualTo("1"); assertThat(result.path("durationMinutes").asInt()).isEqualTo(45); assertThat(starts(result)).hasSize(2);
    }

    @Test void clipsMinuteHoursAndShiftGapsWithoutCrossingLunch() throws Exception {
        var u = fixture(); setHours(u, DATE, "09:10", "12:00", "14:00", "17:40");
        jdbc.update("DELETE FROM barber_schedule WHERE barber_id=?", u.barber());
        shift(u, DATE, "09:00", "11:00"); shift(u, DATE, "11:15", "12:00"); shift(u, DATE, "14:00", "18:00");
        var result = data(get(url(u), null)); var starts = starts(result);
        assertThat(starts.getFirst()).isEqualTo(at(DATE, "09:15")); assertThat(starts.getLast()).isEqualTo(at(DATE, "17:00"));
        assertThat(starts).doesNotContain(at(DATE, "10:45"), at(DATE, "11:00"), at(DATE, "11:45"), at(DATE, "13:45"), at(DATE, "17:15"));
        assertThat(starts).isSorted().doesNotHaveDuplicates();
    }

    @Test void midnightScheduleUsesPreviousUtcDateAndDoesNotLeakAnotherDay() throws Exception {
        var u = fixture(); setHours(u, DATE, "00:00", "01:00"); jdbc.update("DELETE FROM barber_schedule WHERE barber_id=?", u.barber());
        shift(u, DATE, "00:00", "01:00"); shift(u, DATE.plusDays(1), "00:00", "01:00");
        var result = data(get(url(u), null)); assertThat(starts(result)).containsExactly(Instant.parse("2026-10-21T16:00:00Z"), Instant.parse("2026-10-21T16:15:00Z"), Instant.parse("2026-10-21T16:30:00Z"));
    }

    @ParameterizedTest @ValueSource(ints = {-1, 0, 6, 7})
    void requestedDateWindowUsesCampusTodayThroughSixthDay(int offset) throws Exception {
        var u = fixture(); var day = NOW.atZone(ZONE).toLocalDate().plusDays(offset); setHours(u, day, "09:00", "10:00"); shift(u, day, "09:00", "10:00");
        expect(get(url(u, "" + u.service(), "" + u.barber(), day.toString()), null), offset < 0 || offset > 6 ? 400 : 200, offset < 0 || offset > 6 ? "VALIDATION_FAILED" : "OK");
    }

    @ParameterizedTest @ValueSource(strings = {"2026-2-01", "2026-02-29", "2026-10-22Z", "2026-10-22T00:00:00Z", "0999-12-31", "10000-01-01", "null", ""})
    void strictRealDateFormatIsRequired(String date) throws Exception {
        var u = fixture(); expect(get(url(u, "" + u.service(), "" + u.barber(), date), null), 400, "VALIDATION_FAILED");
    }

    @ParameterizedTest @ValueSource(strings = {"0", "01", "-1", "1.0", "9223372036854775808", "abc", "%201", "+1"})
    void allThreeIdentifiersAreStrictPositiveBigints(String value) throws Exception {
        var u = fixture(); expect(get("/api/v1/shops/" + value + "/booking-slots?serviceId=" + u.service() + "&barberId=" + u.barber() + "&date=" + DATE, null), 400, "VALIDATION_FAILED");
        expect(get(url(u, value, "" + u.barber(), DATE.toString()), null), 400, "VALIDATION_FAILED");
        expect(get(url(u, "" + u.service(), value, DATE.toString()), null), 400, "VALIDATION_FAILED");
    }

    @ParameterizedTest @ValueSource(strings = {"serviceId", "barberId", "date"})
    void missingAndExplicitEmptyParametersDoNotMeanDefaults(String field) throws Exception {
        var u = fixture(); var params = new LinkedHashMap<String,String>(); params.put("serviceId", "" + u.service()); params.put("barberId", "" + u.barber()); params.put("date", DATE.toString());
        params.remove(field); expect(get(path(u) + "?" + query(params), null), 400, "VALIDATION_FAILED");
        params.put(field, ""); expect(get(path(u) + "?" + query(params), null), 400, "VALIDATION_FAILED");
    }

    @ParameterizedTest @ValueSource(strings = {"pending", "rejected", "suspended", "ownerPending", "ownerDisabled", "ownerMissing", "accountDisabled", "student", "admin"})
    void publicQualificationFailuresAre404EvenWithTheBusinessSwitchOff(String mode) throws Exception {
        var u = fixture(); jdbc.update("UPDATE shop SET business_enabled=FALSE WHERE id=?", u.shop());
        switch (mode) {
            case "pending", "rejected", "suspended" -> jdbc.update("UPDATE shop SET approval_status=? WHERE id=?", mode.toUpperCase(Locale.ROOT), u.shop());
            case "ownerPending", "ownerDisabled" -> jdbc.update("UPDATE shop_member SET status=? WHERE shop_id=?", mode.equals("ownerPending") ? "PENDING" : "DISABLED", u.shop());
            case "ownerMissing" -> jdbc.update("DELETE FROM shop_member WHERE shop_id=?", u.shop());
            case "accountDisabled" -> jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?", u.id());
            case "student", "admin" -> jdbc.update("UPDATE account SET role=? WHERE id=?", mode.toUpperCase(Locale.ROOT), u.id());
            default -> throw new IllegalStateException(mode);
        }
        expect(get(url(u), null), 404, "RESOURCE_NOT_FOUND");
    }

    @ParameterizedTest @ValueSource(strings = {"shopMissing", "printShop", "serviceMissing", "serviceForeign", "serviceDown", "servicePrint", "barberMissing", "barberForeign", "barberDisabled", "unbound"})
    void invalidResourceCombinationsNeverBecomeSuccessfulClosedShopResults(String mode) throws Exception {
        var u = fixture(); var other = fixture(); jdbc.update("UPDATE shop SET business_enabled=FALSE WHERE id=?", u.shop());
        String service = "" + u.service(), barber = "" + u.barber(); String path = path(u);
        switch (mode) {
            case "shopMissing" -> path = "/api/v1/shops/9223372036854775807/booking-slots";
            case "printShop" -> jdbc.update("UPDATE shop SET category_id=(SELECT id FROM shop_category WHERE code='PRINT') WHERE id=?", u.shop());
            case "serviceMissing" -> service = "9223372036854775807";
            case "serviceForeign" -> { service = "" + other.service(); bindings.insert(u.barber(), other.service()); }
            case "serviceDown" -> jdbc.update("UPDATE service_item SET enabled=FALSE WHERE id=?", u.service());
            case "servicePrint" -> jdbc.update("UPDATE service_item SET service_type='PRINT',duration_minutes=NULL WHERE id=?", u.service());
            case "barberMissing" -> barber = "9223372036854775807";
            case "barberForeign" -> { barber = "" + other.barber(); bindings.insert(other.barber(), u.service()); }
            case "barberDisabled" -> jdbc.update("UPDATE barber SET enabled=FALSE WHERE id=?", u.barber());
            case "unbound" -> bindings.delete(u.barber(), u.service());
            default -> throw new IllegalStateException(mode);
        }
        expect(get(path + "?serviceId=" + service + "&barberId=" + barber + "&date=" + DATE, null), 404, "RESOURCE_NOT_FOUND");
    }

    @ParameterizedTest @ValueSource(strings = {"STUDENT", "MERCHANT", "ADMIN"})
    void activeRolesMayBrowseButInvalidAndRevokedTokensAreNeverIgnored(String role) throws Exception {
        var u = fixture(); var viewer = account(role); String token = login(viewer);
        expect(get(url(u), token), 200, "OK"); expect(get(url(u), "invalid"), 401, "TOKEN_INVALID");
        expect(call("POST", "/api/v1/auth/logout", null, token), 200, "OK"); expect(get(url(u), token), 401, "SESSION_REVOKED"); expect(get(url(u), null), 200, "OK");
    }

    @ParameterizedTest @ValueSource(strings = {"expired", "disabled"})
    void expiredOrDisabledViewerFailsEvenThoughAnonymousBrowsingWorks(String mode) throws Exception {
        var u = fixture(); var viewer = account("STUDENT"); String token = login(viewer);
        if (mode.equals("expired")) { fixed(NOW.plusSeconds(7201)); } else { jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?", viewer.id()); }
        expect(get(url(u), token), mode.equals("expired") ? 401 : 403, mode.equals("expired") ? "SESSION_EXPIRED" : "ACCOUNT_DISABLED"); expect(get(url(u), null), 200, "OK");
    }

    @Test void onlyThisGetBecomesPublicAndOrdersRemainUnavailable() throws Exception {
        var u = fixture();
        for (String method : List.of("POST", "PUT", "PATCH", "DELETE")) { expect(call(method, path(u), null, null), 401, "AUTH_REQUIRED"); }
        expect(get(path(u) + "/future", null), 401, "AUTH_REQUIRED"); expect(get("/api/v1/orders/bookings", null), 401, "AUTH_REQUIRED");
    }

    @ParameterizedTest @ValueSource(strings = {"shop", "service", "hours", "schedule"})
    void concurrentChangesDoNotTearQualificationPriceDurationHoursOrSchedule(String gate) throws Exception {
        var u = fixture(); var reached = new CountDownLatch(1); var release = new CountDownLatch(1);
        if (gate.equals("shop")) { var real = sqlSession.getMapper(PublicShopMapper.class); doAnswer(inv -> { var row = real.visible(inv.getArgument(0)); reached.countDown(); await(release); return row; }).when(catalog).visible(u.shop()); }
        else if (gate.equals("service")) { var real = sqlSession.getMapper(ServiceItemMapper.class); doAnswer(inv -> { var row = real.findOwn(inv.getArgument(0), inv.getArgument(1)); reached.countDown(); await(release); return row; }).when(services).findOwn(u.service(), u.shop()); }
        else if (gate.equals("hours")) { var real = sqlSession.getMapper(BusinessHourMapper.class); doAnswer(inv -> { var rows = real.views(inv.getArgument(0)); reached.countDown(); await(release); return rows; }).when(hours).views(u.shop()); }
        else { var real = sqlSession.getMapper(BarberScheduleMapper.class); doAnswer(inv -> { var rows = real.rows(inv.getArgument(0), inv.getArgument(1)); reached.countDown(); await(release); return rows; }).when(schedules).rows(u.barber(), DATE); }
        clearInvocations(clock);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var response = pool.submit(() -> get(url(u), null));
            try {
                assertThat(reached.await(15, TimeUnit.SECONDS)).isTrue();
                new TransactionTemplate(manager).executeWithoutResult(tx -> {
                    if (gate.equals("shop")) { jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?", u.id()); }
                    jdbc.update("UPDATE shop SET business_enabled=FALSE,version=version+1 WHERE id=?", u.shop());
                    jdbc.update("UPDATE service_item SET price_amount=5000,duration_minutes=45,version=version+1 WHERE id=?", u.service());
                    schedules.lockGuard(u.barber(), DATE); jdbc.update("UPDATE barber SET enabled=FALSE,version=version+1 WHERE id=?", u.barber()); bindings.delete(u.barber(), u.service());
                    setHours(u, DATE, "14:00", "16:00"); jdbc.update("DELETE FROM barber_schedule WHERE barber_id=?", u.barber()); shift(u, DATE, "14:00", "15:00");
                });
                if (gate.equals("schedule")) { fixed(at(DATE, "07:00").plusMillis(1)); }
            } finally { release.countDown(); }
            var result = data(response.get(20, TimeUnit.SECONDS)); assertThat(result.path("serviceVersion").asString()).isEqualTo("0");
            assertThat(result.path("priceAmount").asLong()).isEqualTo(3000); assertThat(result.path("durationMinutes").asInt()).isEqualTo(30); assertThat(starts(result)).containsExactly(at(DATE, "09:00"), at(DATE, "09:15"), at(DATE, "09:30"));
        }
        verify(clock, times(1)).instant(); expect(get(url(u), null), 404, "RESOURCE_NOT_FOUND");
    }

    @Test void crossingMidnightWhileReadingKeepsTheSingleRequestTimeAndNextRequestUsesNewDate() throws Exception {
        var u = fixture(); var yesterday = DATE.minusDays(1); setHours(u, yesterday, "09:00", "10:00"); shift(u, yesterday, "09:00", "10:00");
        fixed(Instant.parse("2026-10-21T15:59:59Z")); var real = sqlSession.getMapper(PublicShopMapper.class);
        doAnswer(inv -> { var row = real.visible(inv.getArgument(0)); fixed(Instant.parse("2026-10-21T16:00:01Z")); return row; }).when(catalog).visible(u.shop());
        assertThat(data(get(url(u, "" + u.service(), "" + u.barber(), yesterday.toString()), null)).path("slots").isEmpty()).isTrue();
        expect(get(url(u, "" + u.service(), "" + u.barber(), yesterday.toString()), null), 400, "VALIDATION_FAILED");
    }

    private record User(long id, String name, long shop, long service, long barber) {}
    private User account(String role) { var a = new AccountEntity(); a.setUsername("slots_" + UUID.randomUUID().toString().replace("-", "").substring(0, 22)); a.setNickname("候选时段测试"); a.setPasswordHash(hash); a.setRole(role); a.setStatus("ACTIVE"); a.setVersion(0L); accounts.insert(a); accountIds.add(a.getId()); return new User(a.getId(), a.getUsername(), 0, 0, 0); }
    private User fixture() { var a = account("MERCHANT"); var shop = new ShopEntity(); shop.setCategoryId(jdbc.queryForObject("SELECT id FROM shop_category WHERE code='HAIRCUT'", Long.class)); shop.setName("虚构候选店铺"); shop.setLocationText("虚构校区"); shop.setDescription(""); shop.setCampusCode("XINGHAI_MAIN"); shop.setApprovalStatus("APPROVED"); shop.setBusinessEnabled(true); shop.setVersion(1L); shops.insert(shop); shopIds.add(shop.getId()); jdbc.update("INSERT INTO shop_member (shop_id,account_id,status) VALUES (?,?,'ACTIVE')", shop.getId(), a.id()); var s = new ServiceItemEntity(); s.setShopId(shop.getId()); s.setServiceType("HAIRCUT"); s.setName("模拟理发"); s.setDescription(""); s.setPriceAmount(3000L); s.setDurationMinutes(30); s.setEnabled(true); s.setVersion(0L); services.insert(s); var b = new BarberEntity(); b.setShopId(shop.getId()); b.setDisplayName("模拟人员"); b.setEnabled(true); b.setVersion(0L); people.insert(b); bindings.insert(b.getId(), s.getId()); var u = new User(a.id(), a.name(), shop.getId(), s.getId(), b.getId()); setHours(u, DATE, "09:00", "10:00"); shift(u, DATE, "09:00", "10:00"); return u; }
    private void setHours(User u, LocalDate date, String... times) { jdbc.update("DELETE FROM shop_business_hour WHERE shop_id=?", u.shop()); for (int i = 0; i < times.length; i += 2) { jdbc.update("INSERT INTO shop_business_hour (shop_id,weekday,start_time,end_time) VALUES (?,?,?,?)", u.shop(), date.getDayOfWeek().getValue(), times[i], times[i + 1]); } }
    private void shift(User u, LocalDate date, String start, String end) { if (jdbc.queryForObject("SELECT COUNT(*) FROM barber_day_guard WHERE barber_id=? AND service_date=?", Integer.class, u.barber(), date) == 0) { schedules.insertGuard(u.barber(), date); } schedules.insert(u.barber(), date, LocalDateTime.ofInstant(at(date, start), ZoneOffset.UTC), LocalDateTime.ofInstant(at(date, end), ZoneOffset.UTC)); }
    private static Instant at(LocalDate date, String time) { return date.atTime(LocalTime.parse(time)).atZone(ZONE).toInstant(); }
    private void fixed(Instant time) { doReturn(time).when(clock).instant(); }
    private static String path(User u) { return "/api/v1/shops/" + u.shop() + "/booking-slots"; }
    private static String url(User u) { return url(u, "" + u.service(), "" + u.barber(), DATE.toString()); }
    private static String url(User u, String service, String barber, String date) { return path(u) + "?serviceId=" + service + "&barberId=" + barber + "&date=" + date; }
    private static String query(Map<String,String> params) { return params.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue()).collect(java.util.stream.Collectors.joining("&")); }
    private List<?> snapshot(User u) { return List.of(jdbc.queryForMap("SELECT * FROM shop WHERE id=?", u.shop()), jdbc.queryForMap("SELECT * FROM service_item WHERE id=?", u.service()), jdbc.queryForMap("SELECT * FROM barber WHERE id=?", u.barber()), jdbc.queryForList("SELECT * FROM barber_service WHERE barber_id=? ORDER BY id", u.barber()), jdbc.queryForList("SELECT * FROM barber_day_guard WHERE barber_id=? ORDER BY service_date", u.barber()), jdbc.queryForList("SELECT * FROM barber_schedule WHERE barber_id=? ORDER BY service_date,start_at", u.barber()), jdbc.queryForList("SELECT * FROM shop_business_hour WHERE shop_id=? ORDER BY weekday,start_time", u.shop())); }
    private List<Instant> starts(JsonNode data) { var result = new ArrayList<Instant>(); for (var slot : data.path("slots")) { result.add(Instant.parse(slot.path("startAt").asString())); } return result; }
    private String login(User u) throws Exception { return expect(call("POST", "/api/v1/auth/login", Map.of("username", u.name(), "password", PASSWORD), null), 200, "OK").path("data").path("accessToken").asString(); }
    private JsonNode data(HttpResponse<String> response) throws Exception { return expect(response, 200, "OK").path("data"); }
    private JsonNode expect(HttpResponse<String> response, int status, String code) throws Exception { assertThat(response.statusCode()).as(response.body()).isEqualTo(status); var body = json.readTree(response.body()); assertThat(body.path("code").asString()).isEqualTo(code); assertThat(body.path("traceId").asString()).isEqualTo(response.headers().firstValue("X-Trace-Id").orElseThrow()); return body; }
    private HttpResponse<String> get(String path, String token) throws Exception { return call("GET", path, null, token); }
    private HttpResponse<String> call(String method, String path, Object body, String token) throws Exception { var req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + environment.getRequiredProperty("local.server.port") + path)).timeout(Duration.ofSeconds(25)); if (token != null) { req.header("Authorization", "Bearer " + token); } if (body != null) { req.header("Content-Type", "application/json"); } req.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8)); return client.send(req.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)); }
    private static void await(CountDownLatch latch) { try { if (!latch.await(15, TimeUnit.SECONDS)) { throw new IllegalStateException("Test gate timed out"); } } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); } }
}
