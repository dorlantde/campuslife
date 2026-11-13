package com.campuslife;

import com.campuslife.auth.entity.AccountEntity;
import com.campuslife.auth.mapper.AccountMapper;
import com.campuslife.merchant.entity.BarberEntity;
import com.campuslife.merchant.entity.ShopEntity;
import com.campuslife.merchant.mapper.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
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
class BarberScheduleMysqlIT {
    private static final String HOURS = "/api/v1/merchant/shop/business-hours";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String PASSWORD = "Schedule_Test123!";
    private final HttpClient client = HttpClient.newHttpClient();
    private final List<Long> accountIds = new ArrayList<>(), shopIds = new ArrayList<>();
    private String hash;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired Environment environment;
    @Autowired PasswordEncoder passwords;
    @Autowired AccountMapper accounts;
    @Autowired PlatformTransactionManager manager;
    @Autowired org.mybatis.spring.SqlSessionTemplate sqlSession;
    @MockitoSpyBean ShopMapper shops;
    @MockitoSpyBean BarberMapper barbers;
    @MockitoSpyBean BarberScheduleMapper schedules;
    @MockitoSpyBean Clock clock;

    @BeforeAll void hash() { hash = passwords.encode(PASSWORD); }
    @AfterEach void cleanup() {
        reset(shops, barbers, schedules, clock);
        for (long shop : shopIds) {
            jdbc.update("DELETE s FROM barber_schedule s JOIN barber b ON b.id=s.barber_id WHERE b.shop_id=?", shop);
            jdbc.update("DELETE g FROM barber_day_guard g JOIN barber b ON b.id=g.barber_id WHERE b.shop_id=?", shop);
            jdbc.update("DELETE bs FROM barber_service bs JOIN barber b ON b.id=bs.barber_id WHERE b.shop_id=?", shop);
            jdbc.update("DELETE FROM barber WHERE shop_id=?", shop);
            jdbc.update("DELETE FROM shop_business_hour WHERE shop_id=?", shop);
            jdbc.update("DELETE FROM shop_member WHERE shop_id=?", shop);
            jdbc.update("DELETE FROM shop WHERE id=?", shop);
        }
        for (long id : accountIds) { jdbc.update("DELETE FROM auth_session WHERE account_id=?", id); jdbc.update("DELETE FROM account WHERE id=?", id); }
        accountIds.clear(); shopIds.clear();
    }

    @Test void normalizesReplacesAndClearsOneDayWithUtcRoundtripAndRetainedGuard() throws Exception {
        var u = fixture("HAIRCUT"); var token = login(u); var day = today(); long other = barber(u.shop());
        var parents = parent(u); var untouched = jdbc.queryForMap("SELECT * FROM barber WHERE id=?", other);
        expect(get(u.barber(), day, token), 200, "OK");
        expect(put(u, day, "0", List.of(), token), 200, "OK");
        assertThat(count("barber_day_guard", u.barber())).isZero();
        var view = expect(put(u, day, "0", List.of(part("14:00", "17:00"), part("10:00", "12:00"), part("09:00", "10:00")), token), 200, "OK").path("data");
        assertThat(view.path("barberVersion").asString()).isEqualTo("1");
        assertThat(view.path("intervals")).isEqualTo(json.valueToTree(List.of(part("09:00", "12:00"), part("14:00", "17:00"))));
        assertThat(expect(get(u.barber(), day, token), 200, "OK").path("data")).isEqualTo(view);
        assertThat(jdbc.queryForObject("SELECT start_at FROM barber_schedule WHERE barber_id=? ORDER BY start_at LIMIT 1", LocalDateTime.class, u.barber())).isEqualTo(day.atTime(1, 0));
        var rows = rows(u.barber());
        expect(put(u, day, "1", List.of(part("09:00", "12:00"), part("14:00", "17:00")), token), 200, "OK");
        assertThat(rows(u.barber())).isEqualTo(rows);
        expect(put(u, day.plusDays(1), "1", List.of(part("09:00", "10:00")), token), 200, "OK");
        expect(put(u, day, "2", List.of(), token), 200, "OK");
        assertThat(expect(get(u.barber(), day, token), 200, "OK").path("data").path("intervals").isEmpty()).isTrue();
        assertThat(count("barber_day_guard", u.barber())).isEqualTo(2);
        assertThat(count("barber_schedule", u.barber())).isEqualTo(1);
        assertThat(parent(u)).isEqualTo(parents); assertThat(jdbc.queryForMap("SELECT * FROM barber WHERE id=?", other)).isEqualTo(untouched);
    }

    @Test void sharedVersionAcrossDaysProfileAndBindingsIncludingNoopAndLongMax() throws Exception {
        var u = fixture("HAIRCUT"); var token = login(u); var day = today();
        expect(put(u, day, "0", List.of(part("09:00", "10:00")), token), 200, "OK");
        expect(put(u, day.plusDays(1), "0", List.of(), token), 409, "CONFIG_VERSION_CONFLICT");
        expect(call("PATCH", "/api/v1/merchant/barbers/" + u.barber(), Map.of("expectedVersion", "1", "displayName", "新姓名"), token), 200, "OK");
        expect(put(u, day, "1", List.of(part("09:00", "10:00")), token), 409, "CONFIG_VERSION_CONFLICT");
        expect(call("PUT", "/api/v1/merchant/barbers/" + u.barber() + "/services", Map.of("expectedVersion", "1", "serviceIds", List.of()), token), 409, "CONFIG_VERSION_CONFLICT");
        expect(put(u, day, "2", List.of(part("09:00", "10:00")), token), 200, "OK");
        jdbc.update("UPDATE barber SET version=? WHERE id=?", Long.MAX_VALUE, u.barber()); var before = snapshot(u);
        expect(put(u, day, Long.toString(Long.MAX_VALUE), List.of(part("09:00", "10:00")), token), 200, "OK");
        expect(put(u, day.plusDays(1), Long.toString(Long.MAX_VALUE), List.of(), token), 200, "OK");
        expect(put(u, day, Long.toString(Long.MAX_VALUE), List.of(), token), 409, "CONFIG_VERSION_CONFLICT");
        expect(put(u, day.plusDays(1), Long.toString(Long.MAX_VALUE), List.of(part("09:00", "10:00")), token), 409, "CONFIG_VERSION_CONFLICT");
        assertThat(snapshot(u)).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(ints = {-1, 0, 6, 7})
    void shanghaiWindowIncludesTodayAndSixthDay(int offset) throws Exception {
        var u = fixture("HAIRCUT"); String token = login(u); var date = today().plusDays(offset);
        expect(put(u, date, "0", List.of(part("09:00", "10:00")), token), offset < 0 || offset > 6 ? 400 : 200,
                offset < 0 || offset > 6 ? "VALIDATION_FAILED" : "OK");
        expect(get(u.barber(), date, token), 200, "OK");
    }

    @Test void fourRawSegmentsAreAllowedButFiveAreNotEvenIfTheyMerge() throws Exception {
        var u = fixture("HAIRCUT"); String token = login(u);
        expect(put(u, today(), "0", List.of(part("09:00", "09:15"), part("09:15", "09:30"), part("09:30", "09:45"), part("09:45", "10:00")), token), 200, "OK");
        var before = snapshot(u);
        expect(put(u, today(), "1", Collections.nCopies(5, part("09:00", "09:15")), token), 400, "VALIDATION_FAILED"); assertThat(snapshot(u)).isEqualTo(before);
    }

    static Stream<Arguments> invalidBodies() {
        var cases = new ArrayList<Arguments>();
        for (String field : List.of("date", "expectedBarberVersion", "intervals")) {
            var omitted = body("2026-10-22", "0", List.of()); omitted.remove(field); cases.add(Arguments.of("missing " + field, omitted));
            var nul = body("2026-10-22", "0", List.of()); nul.put(field, null); cases.add(Arguments.of("null " + field, nul));
        }
        for (Object date : List.of(20261022, true, "2026-2-01", "2026-02-29", "2024-02-30", "0999-12-31", "10000-01-01", "2026-10-22T00:00:00Z", "2026-10-22 ")) {
            cases.add(Arguments.of("date " + date, body(date, "0", List.of())));
        }
        for (Object version : List.of(0, true, "00", "-1", " 0", "1.0", "9223372036854775808")) { cases.add(Arguments.of("version " + version, body("TODAY", version, List.of()))); }
        for (Object intervals : Arrays.asList("[]", 0, Map.of(), Arrays.asList((Object) null), List.of(Map.of("startTime", "09:00")),
                List.of(Map.of("startTime", "09:00", "endTime", "10:00", "extra", true)))) {
            cases.add(Arguments.of("intervals " + intervals, body("TODAY", "0", intervals)));
        }
        for (Object time : Arrays.asList(null, 900, true, "9:00", "09:00:00", "09:00Z", "24:00", "09:10", "09:01", " 09:00")) {
            var interval = new LinkedHashMap<String,Object>(); interval.put("startTime", time); interval.put("endTime", "12:00");
            cases.add(Arguments.of("time " + time, body("TODAY", "0", List.of(interval))));
        }
        for (var intervals : List.of(List.of(part("10:00", "09:00")), List.of(part("09:00", "09:00")), List.of(part("23:00", "01:00")),
                List.of(part("09:00", "11:00"), part("10:00", "12:00")), List.of(part("09:00", "10:00"), part("09:00", "10:00")))) {
            cases.add(Arguments.of("overlap/order " + intervals, body("TODAY", "0", intervals)));
        }
        var extra = body("TODAY", "0", List.of()); extra.put("shopId", "1"); cases.add(Arguments.of("extra field", extra));
        return cases.stream();
    }
    @ParameterizedTest(name = "{0}") @MethodSource("invalidBodies")
    void malformedRequestsNeverLeaveAGuardOrVersion(String label, Map<String,Object> body) throws Exception {
        var u = fixture("HAIRCUT"); var token = login(u); if ("TODAY".equals(body.get("date"))) { body.put("date", today().toString()); }
        var before = snapshot(u); expect(call("PUT", path(u.barber()), body, token), 400, "VALIDATION_FAILED"); assertThat(snapshot(u)).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"2026-2-01", "2026-02-29", "0999-01-01", "10000-01-01", "2026-10-22Z", "null", ""})
    void getStrictlyValidatesDates(String date) throws Exception {
        var u = fixture("HAIRCUT"); expect(call("GET", path(u.barber()) + "?date=" + date, null, login(u)), 400, "VALIDATION_FAILED");
    }
    @Test void getAllowsDatabaseDateExtremesLeapDayAndDoesNotWrite() throws Exception {
        var u = fixture("HAIRCUT"); String token = login(u);
        for (String date : List.of("1000-01-01", "9999-12-31", "2024-02-29")) { expect(get(u.barber(), LocalDate.parse(date), token), 200, "OK"); }
        expect(call("GET", path(u.barber()), null, token), 400, "VALIDATION_FAILED"); assertThat(count("barber_day_guard", u.barber())).isZero();
    }

    @Test void checksContinuousOpeningHoursLunchRestDaysAndMinutePrecision() throws Exception {
        var u = fixture("HAIRCUT"); String token = login(u); var day = today();
        jdbc.update("DELETE FROM shop_business_hour WHERE shop_id=?", u.shop());
        expect(put(u, day, "0", List.of(), token), 200, "OK");
        expect(put(u, day, "0", List.of(part("09:00", "10:00")), token), 400, "VALIDATION_FAILED");
        jdbc.update("INSERT INTO shop_business_hour (shop_id,weekday,start_time,end_time) VALUES (?,?,'09:10','12:00'), (?,?,'14:00','17:40')", u.shop(), day.getDayOfWeek().getValue(), u.shop(), day.getDayOfWeek().getValue());
        expect(put(u, day, "0", List.of(part("11:30", "14:30")), token), 400, "VALIDATION_FAILED");
        expect(put(u, day, "0", List.of(part("09:00", "12:00")), token), 400, "VALIDATION_FAILED");
        expect(put(u, day, "0", List.of(part("14:00", "17:45")), token), 400, "VALIDATION_FAILED");
        expect(put(u, day, "0", List.of(part("09:15", "12:00"), part("14:00", "17:30")), token), 200, "OK");
        expect(put(u, day.plusDays(1), "1", List.of(part("09:15", "10:00")), token), 400, "VALIDATION_FAILED");
    }

    @ParameterizedTest @ValueSource(strings = {"student", "admin", "noShop", "print", "pending", "suspended", "member", "disabled", "foreign", "missing", "anonymous"})
    void permissionsAndOwnershipProtectBothRoutes(String mode) throws Exception {
        var u = fixture(mode.equals("print") ? "PRINT" : "HAIRCUT"); String token = login(u); long id = u.barber(); int status = 403; String code = "ROLE_FORBIDDEN";
        switch (mode) {
            case "student", "admin" -> jdbc.update("UPDATE account SET role=? WHERE id=?", mode.toUpperCase(Locale.ROOT), u.id());
            case "noShop" -> { jdbc.update("DELETE FROM shop_member WHERE shop_id=?", u.shop()); status = 404; code = "RESOURCE_NOT_FOUND"; }
            case "print" -> { status = 400; code = "VALIDATION_FAILED"; }
            case "pending", "suspended" -> { jdbc.update("UPDATE shop SET approval_status=? WHERE id=?", mode.toUpperCase(Locale.ROOT), u.shop()); code = "SHOP_NOT_APPROVED"; }
            case "member" -> jdbc.update("UPDATE shop_member SET status='DISABLED' WHERE shop_id=?", u.shop());
            case "disabled" -> { jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?", u.id()); code = "ACCOUNT_DISABLED"; }
            case "foreign" -> { id = fixture("HAIRCUT").barber(); status = 404; code = "RESOURCE_NOT_FOUND"; }
            case "missing" -> { id = Long.MAX_VALUE; status = 404; code = "RESOURCE_NOT_FOUND"; }
            case "anonymous" -> { token = null; status = 401; code = "AUTH_REQUIRED"; }
            default -> throw new IllegalStateException(mode);
        }
        var before = snapshot(u);
        expect(get(id, today(), token), status, code); expect(call("PUT", path(id), body(today().toString(), "0", List.of(part("09:00", "10:00"))), token), status, code);
        assertThat(snapshot(u)).isEqualTo(before);
    }

    @Test void businessHoursProtectTodayAndFutureIncludingDisabledPeopleButIgnoreHistory() throws Exception {
        var u = fixture("HAIRCUT"); String token = login(u); var day = today();
        expect(put(u, day, "0", List.of(part("09:00", "12:00")), token), 200, "OK"); var before = snapshot(u);
        expect(hours(u, "1", List.of(), token), 409, "SCHEDULE_CONFLICT"); assertThat(snapshot(u)).isEqualTo(before);
        expect(hours(u, "1", week("10:00", "18:00"), token), 409, "SCHEDULE_CONFLICT"); assertThat(snapshot(u)).isEqualTo(before);
        expect(hours(u, "1", week("08:00", "19:00"), token), 200, "OK");
        assertThat(version(u)).isEqualTo(1); // Only shop version increased.
        expect(put(u, day.plusDays(6), "1", List.of(part("08:00", "09:00")), token), 200, "OK");
        expect(hours(u, "2", week("09:00", "18:00"), token), 409, "SCHEDULE_CONFLICT");
        expect(put(u, day.plusDays(6), "2", List.of(), token), 200, "OK");
        expect(put(u, day, "3", List.of(), token), 200, "OK");
        seed(u, day.minusDays(7), "06:00", "07:00");
        expect(hours(u, "2", List.of(), token), 200, "OK");
        assertThat(count("barber_schedule", u.barber())).isEqualTo(1);
    }

    @Test void todayMorningIsProtectedEvenWhenAlreadyPastAndUtcPreviousDateConvertsBack() throws Exception {
        fixed("2026-10-21T15:30:00Z"); var u = fixture("HAIRCUT"); String token = login(u); var day = today();
        expect(hours(u, "1", week("00:00", "23:45"), token), 200, "OK");
        expect(put(u, day, "0", List.of(part("00:00", "00:15")), token), 200, "OK");
        assertThat(jdbc.queryForObject("SELECT start_at FROM barber_schedule WHERE barber_id=?", LocalDateTime.class, u.barber())).isEqualTo(day.minusDays(1).atTime(16, 0));
        expect(hours(u, "2", week("09:00", "18:00"), token), 409, "SCHEDULE_CONFLICT");
        assertThat(expect(get(u.barber(), day, token), 200, "OK").path("data").path("intervals").get(0).path("startTime").asString()).isEqualTo("00:00");
    }

    @ParameterizedTest @ValueSource(strings = {"guard", "insert", "delete", "version", "expiry", "midnight"})
    void actualSqlFollowedByFailureRollsBackSchedulesGuardsAndVersion(String phase) throws Exception {
        if (phase.equals("midnight")) { fixed("2026-10-21T15:59:59Z"); }
        var u = fixture("HAIRCUT"); String token = login(u); var day = today();
        if (phase.equals("delete")) { expect(put(u, day, "0", List.of(part("09:00", "10:00")), token), 200, "OK"); }
        var before = snapshot(u); var real = sqlSession.getMapper(BarberScheduleMapper.class); var realBarbers = sqlSession.getMapper(BarberMapper.class);
        if (phase.equals("guard")) {
            doAnswer(inv -> { real.insertGuard(inv.getArgument(0), inv.getArgument(1)); throw new IllegalStateException("After actual guard INSERT"); }).when(schedules).insertGuard(anyLong(), any(LocalDate.class));
        } else if (phase.equals("delete")) {
            doAnswer(inv -> { real.delete(inv.getArgument(0), inv.getArgument(1)); throw new IllegalStateException("After actual DELETE"); }).when(schedules).delete(anyLong(), any(LocalDate.class));
        } else if (phase.equals("version")) {
            doAnswer(inv -> { realBarbers.updateOwn(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), inv.getArgument(3), inv.getArgument(4)); throw new IllegalStateException("After actual version UPDATE"); })
                    .when(barbers).updateOwn(anyLong(), anyLong(), anyLong(), anyString(), anyBoolean());
        } else {
            doAnswer(inv -> {
                int n = real.insert(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), inv.getArgument(3));
                if (phase.equals("expiry")) { fixed(Instant.now().plusSeconds(7201).toString()); }
                else if (phase.equals("midnight")) { fixed("2026-10-21T16:00:01Z"); }
                else { throw new IllegalStateException("After actual schedule INSERT"); }
                return n;
            }).when(schedules).insert(anyLong(), any(LocalDate.class), any(LocalDateTime.class), any(LocalDateTime.class));
        }
        int status = phase.equals("expiry") ? 401 : phase.equals("midnight") ? 400 : 500;
        expect(put(u, day, phase.equals("delete") ? "1" : "0", List.of(part("10:00", "11:00")), token), status,
                phase.equals("expiry") ? "SESSION_EXPIRED" : phase.equals("midnight") ? "VALIDATION_FAILED" : "INTERNAL_ERROR");
        assertThat(snapshot(u)).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"midnight", "expiry", "suspension", "hours"})
    void waitingForShopRechecksWindowSessionQualificationAndCurrentHours(String mode) throws Exception {
        if (mode.equals("midnight")) { fixed("2026-10-21T15:59:59Z"); }
        var u = fixture("HAIRCUT"); String token = login(u); var day = today(); var before = snapshot(u);
        var held = new CountDownLatch(1); var release = new CountDownLatch(1); var reached = new CountDownLatch(1);
        var real = sqlSession.getMapper(ShopMapper.class);
        doAnswer(inv -> { var own = real.findOwn(u.id()); reached.countDown(); return own; }).when(shops).findOwn(u.id());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var blocker = pool.submit(() -> new TransactionTemplate(manager).executeWithoutResult(tx -> {
                jdbc.queryForObject("SELECT id FROM shop WHERE id=? FOR UPDATE", Long.class, u.shop()); held.countDown(); await(release);
                switch (mode) {
                    case "midnight" -> fixed("2026-10-21T16:00:01Z");
                    case "expiry" -> fixed(Instant.now().plusSeconds(7201).toString());
                    case "suspension" -> jdbc.update("UPDATE shop SET approval_status='SUSPENDED' WHERE id=?", u.shop());
                    case "hours" -> jdbc.update("DELETE FROM shop_business_hour WHERE shop_id=?", u.shop());
                    default -> throw new IllegalStateException(mode);
                }
            }));
            try {
                assertThat(held.await(15, TimeUnit.SECONDS)).isTrue();
                var response = pool.submit(() -> put(u, day, "0", List.of(part("09:00", "10:00")), token));
                try { assertThat(reached.await(15, TimeUnit.SECONDS)).isTrue(); assertThatThrownBy(() -> response.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class); }
                finally { release.countDown(); }
                blocker.get(20, TimeUnit.SECONDS);
                expect(response.get(20, TimeUnit.SECONDS), mode.equals("expiry") ? 401 : mode.equals("suspension") ? 403 : 400,
                        mode.equals("expiry") ? "SESSION_EXPIRED" : mode.equals("suspension") ? "SHOP_NOT_APPROVED" : "VALIDATION_FAILED");
            } finally { release.countDown(); }
        }
        assertThat(rows(u.barber())).isEqualTo(before.get(2)); assertThat(version(u)).isZero(); assertThat(count("barber_day_guard", u.barber())).isZero();
    }

    @Test void concurrentFirstSaveAndClearSerializeOnGuardForeignKeyAndSharedVersion() throws Exception {
        var u = fixture("HAIRCUT"); String token = login(u); var day = today();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var a = pool.submit(() -> { await(start); return put(u, day, "0", List.of(part("09:00", "10:00")), token); });
            var b = pool.submit(() -> { await(start); return put(u, day, "0", List.of(part("14:00", "15:00")), token); });
            start.countDown(); var replies = List.of(a.get(25, TimeUnit.SECONDS), b.get(25, TimeUnit.SECONDS));
            assertThat(replies.stream().map(HttpResponse::statusCode).sorted().toList()).containsExactly(200, 409);
        }
        assertThat(count("barber_day_guard", u.barber())).isEqualTo(1); assertThat(count("barber_schedule", u.barber())).isEqualTo(1); assertThat(version(u)).isEqualTo(1);
        expect(put(u, day, "1", List.of(), token), 200, "OK"); assertThat(count("barber_day_guard", u.barber())).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {"barberFk", "guard"})
    void actualChildLockWaitRechecksSessionAndRollsBackNewGuard(String resource) throws Exception {
        var u = fixture("HAIRCUT"); String token = login(u); var day = today();
        if (resource.equals("guard")) { expect(put(u, day, "0", List.of(part("09:00", "10:00")), token), 200, "OK"); }
        var before = snapshot(u); var held = new CountDownLatch(1); var release = new CountDownLatch(1); var reached = new CountDownLatch(1);
        var real = sqlSession.getMapper(BarberScheduleMapper.class);
        if (resource.equals("guard")) {
            doAnswer(inv -> { reached.countDown(); return real.lockGuard(inv.getArgument(0), inv.getArgument(1)); }).when(schedules).lockGuard(u.barber(), day);
        } else {
            doAnswer(inv -> { reached.countDown(); return real.insertGuard(inv.getArgument(0), inv.getArgument(1)); }).when(schedules).insertGuard(u.barber(), day);
        }
        try (var pool = Executors.newFixedThreadPool(2)) {
            var blocker = pool.submit(() -> new TransactionTemplate(manager).executeWithoutResult(tx -> {
                if (resource.equals("guard")) { jdbc.queryForObject("SELECT id FROM barber_day_guard WHERE barber_id=? AND service_date=? FOR UPDATE", Long.class, u.barber(), day); }
                else { jdbc.queryForObject("SELECT id FROM barber WHERE id=? FOR UPDATE", Long.class, u.barber()); }
                held.countDown(); await(release); fixed(Instant.now().plusSeconds(7201).toString());
            }));
            try {
                assertThat(held.await(15, TimeUnit.SECONDS)).isTrue();
                var response = pool.submit(() -> put(u, day, resource.equals("guard") ? "1" : "0", List.of(part("10:00", "11:00")), token));
                try { assertThat(reached.await(15, TimeUnit.SECONDS)).isTrue(); assertThatThrownBy(() -> response.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class); }
                finally { release.countDown(); }
                blocker.get(20, TimeUnit.SECONDS); expect(response.get(20, TimeUnit.SECONDS), 401, "SESSION_EXPIRED");
            } finally { release.countDown(); }
        }
        assertThat(snapshot(u)).isEqualTo(before);
    }

    @Test void rrGetBindsPersonnelVersionAndScheduleToOneSnapshot() throws Exception {
        var u = fixture("HAIRCUT"); String token = login(u); var day = today();
        expect(put(u, day, "0", List.of(part("09:00", "10:00")), token), 200, "OK");
        var read = new CountDownLatch(1); var release = new CountDownLatch(1); var real = sqlSession.getMapper(BarberMapper.class);
        doAnswer(inv -> { var row = real.findOwn(inv.getArgument(0), inv.getArgument(1)); read.countDown(); await(release); return row; }).when(barbers).findOwn(u.barber(), u.shop());
        try (var pool = Executors.newSingleThreadExecutor()) {
            var response = pool.submit(() -> get(u.barber(), day, token));
            try {
                assertThat(read.await(15, TimeUnit.SECONDS)).isTrue();
                new TransactionTemplate(manager).executeWithoutResult(tx -> {
                    jdbc.queryForObject("SELECT id FROM shop WHERE id=? FOR UPDATE", Long.class, u.shop());
                    schedules.lockGuard(u.barber(), day); schedules.delete(u.barber(), day);
                    schedules.insert(u.barber(), day, day.atTime(6, 0), day.atTime(7, 0));
                    jdbc.update("UPDATE barber SET version=2 WHERE id=?", u.barber());
                });
            } finally { release.countDown(); }
            var view = expect(response.get(20, TimeUnit.SECONDS), 200, "OK").path("data");
            assertThat(view.path("barberVersion").asString()).isEqualTo("1"); assertThat(view.path("intervals").get(0).path("startTime").asString()).isEqualTo("09:00");
        }
    }

    @Test void shopGateBlocksIndependentDatabaseWriterUntilScheduleCommit() throws Exception {
        var u = fixture("HAIRCUT"); String token = login(u); var day = today(); var held = new CountDownLatch(1); var release = new CountDownLatch(1); var reached = new CountDownLatch(1);
        var real = sqlSession.getMapper(BarberScheduleMapper.class);
        doAnswer(inv -> { int n = real.insert(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), inv.getArgument(3)); held.countDown(); await(release); return n; })
                .when(schedules).insert(anyLong(), any(LocalDate.class), any(LocalDateTime.class), any(LocalDateTime.class));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var response = pool.submit(() -> put(u, day, "0", List.of(part("09:00", "10:00")), token));
            try {
                assertThat(held.await(15, TimeUnit.SECONDS)).isTrue();
                var reader = pool.submit(() -> new TransactionTemplate(manager).execute(tx -> {
                    reached.countDown(); jdbc.queryForObject("SELECT id FROM shop WHERE id=? FOR SHARE", Long.class, u.shop());
                    schedules.lockGuard(u.barber(), day); return schedules.rows(u.barber(), day).size();
                }));
                try { assertThat(reached.await(15, TimeUnit.SECONDS)).isTrue(); assertThatThrownBy(() -> reader.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class); }
                finally { release.countDown(); }
                expect(response.get(20, TimeUnit.SECONDS), 200, "OK"); assertThat(reader.get(20, TimeUnit.SECONDS)).isEqualTo(1);
            } finally { release.countDown(); }
        }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void scheduleAndHoursGateBothOrdersAndNeverCommitIncompatibleConfigurations(boolean scheduleFirst) throws Exception {
        var u = fixture("HAIRCUT"); String token = login(u); var day = today(); var held = new CountDownLatch(1); var release = new CountDownLatch(1);
        var realSchedules = sqlSession.getMapper(BarberScheduleMapper.class); var realShops = sqlSession.getMapper(ShopMapper.class);
        if (scheduleFirst) {
            doAnswer(inv -> { int n = realSchedules.insert(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), inv.getArgument(3)); held.countDown(); await(release); return n; })
                    .when(schedules).insert(anyLong(), any(LocalDate.class), any(LocalDateTime.class), any(LocalDateTime.class));
        } else {
            doAnswer(inv -> { int n = realShops.incrementVersion(inv.getArgument(0), inv.getArgument(1)); held.countDown(); await(release); return n; }).when(shops).incrementVersion(u.shop(), 1L);
        }
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> scheduleFirst ? put(u, day, "0", List.of(part("09:00", "12:00")), token) : hours(u, "1", week("10:00", "18:00"), token));
            try {
                assertThat(held.await(15, TimeUnit.SECONDS)).isTrue();
                var second = pool.submit(() -> scheduleFirst ? hours(u, "1", week("10:00", "18:00"), token) : put(u, day, "0", List.of(part("09:00", "12:00")), token));
                try { assertThatThrownBy(() -> second.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class); }
                finally { release.countDown(); }
                expect(first.get(20, TimeUnit.SECONDS), 200, "OK"); expect(second.get(20, TimeUnit.SECONDS), scheduleFirst ? 409 : 400, scheduleFirst ? "SCHEDULE_CONFLICT" : "VALIDATION_FAILED");
            } finally { release.countDown(); }
        }
        assertThat(count("barber_schedule", u.barber())).isEqualTo(scheduleFirst ? 1 : 0); assertThat(count("barber_day_guard", u.barber())).isEqualTo(scheduleFirst ? 1 : 0);
    }

    @Test void databaseEnforcesGuardFkCompositeFkUniquenessAndPositiveIntervals() throws Exception {
        var u = fixture("HAIRCUT"); var day = today(); var start = day.atTime(1, 0);
        assertSql(() -> schedules.insert(u.barber(), day, start, start.plusHours(1)), 1216, 1452);
        schedules.insertGuard(u.barber(), day);
        assertSql(() -> schedules.insertGuard(u.barber(), day), 1062);
        assertSql(() -> schedules.insertGuard(Long.MAX_VALUE, day), 1216, 1452);
        assertSql(() -> schedules.insert(u.barber(), day, start, start), 3819);
        schedules.insert(u.barber(), day, start, start.plusHours(1));
        assertSql(() -> schedules.insert(u.barber(), day, start, start.plusHours(2)), 1062);
        assertSql(() -> jdbc.update("DELETE FROM barber_day_guard WHERE barber_id=?", u.barber()), 1217, 1451);
        assertSql(() -> jdbc.update("DELETE FROM barber WHERE id=?", u.barber()), 1217, 1451);
    }

    private record User(long id, String name, long shop, long barber) {}
    private User fixture(String category) {
        var a = new AccountEntity(); a.setUsername("sch_" + UUID.randomUUID().toString().replace("-", "").substring(0, 22)); a.setNickname("排班测试"); a.setPasswordHash(hash); a.setRole("MERCHANT"); a.setStatus("ACTIVE"); a.setVersion(0L); accounts.insert(a); accountIds.add(a.getId());
        var s = new ShopEntity(); s.setCategoryId(jdbc.queryForObject("SELECT id FROM shop_category WHERE code=?", Long.class, category)); s.setName("虚构排班店"); s.setLocationText("虚构校区"); s.setCampusCode("XINGHAI_MAIN"); s.setDescription(""); s.setApprovalStatus("APPROVED"); s.setBusinessEnabled(false); s.setVersion(1L); shops.insert(s); shopIds.add(s.getId());
        jdbc.update("INSERT INTO shop_member (shop_id,account_id,status) VALUES (?,?,'ACTIVE')", s.getId(), a.getId());
        for (int day = 1; day <= 7; day++) { jdbc.update("INSERT INTO shop_business_hour (shop_id,weekday,start_time,end_time) VALUES (?,?,'09:00','18:00')", s.getId(), day); }
        return new User(a.getId(), a.getUsername(), s.getId(), barber(s.getId()));
    }
    private long barber(long shop) { var b = new BarberEntity(); b.setShopId(shop); b.setDisplayName("模拟理发师"); b.setEnabled(false); b.setVersion(0L); barbers.insert(b); return b.getId(); }
    private void seed(User u, LocalDate date, String start, String end) { schedules.insertGuard(u.barber(), date); schedules.insert(u.barber(), date, date.atTime(LocalTime.parse(start)).minusHours(8), date.atTime(LocalTime.parse(end)).minusHours(8)); }
    private static Map<String,String> part(String start, String end) { return Map.of("startTime", start, "endTime", end); }
    private static Map<String,Object> body(Object date, Object version, Object intervals) { var body = new LinkedHashMap<String,Object>(); body.put("date", date); body.put("expectedBarberVersion", version); body.put("intervals", intervals); return body; }
    private static List<Map<String,Object>> week(String start, String end) { var rows = new ArrayList<Map<String,Object>>(); for (int day = 1; day <= 7; day++) { rows.add(Map.of("weekday", day, "startTime", start, "endTime", end)); } return rows; }
    private static String path(long id) { return "/api/v1/merchant/barbers/" + id + "/schedule"; }
    private LocalDate today() { return LocalDate.ofInstant(clock.instant(), ZONE); }
    private void fixed(String instant) { doReturn(Instant.parse(instant)).when(clock).instant(); }
    private List<Map<String,Object>> rows(long id) { return jdbc.queryForList("SELECT * FROM barber_schedule WHERE barber_id=? ORDER BY service_date,start_at", id); }
    private int count(String table, long id) { assertThat(Set.of("barber_schedule", "barber_day_guard")).contains(table); return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE barber_id=?", Integer.class, id); }
    private long version(User u) { return jdbc.queryForObject("SELECT version FROM barber WHERE id=?", Long.class, u.barber()); }
    private List<?> snapshot(User u) { return List.of(jdbc.queryForMap("SELECT * FROM barber WHERE id=?", u.barber()), jdbc.queryForList("SELECT * FROM barber_day_guard WHERE barber_id=? ORDER BY service_date", u.barber()), rows(u.barber()), parent(u)); }
    private List<?> parent(User u) { return List.of(jdbc.queryForMap("SELECT * FROM shop WHERE id=?", u.shop()), jdbc.queryForList("SELECT * FROM shop_business_hour WHERE shop_id=? ORDER BY weekday,start_time", u.shop())); }
    private HttpResponse<String> get(long id, LocalDate date, String token) throws Exception { return call("GET", path(id) + "?date=" + date, null, token); }
    private HttpResponse<String> put(User u, LocalDate day, String version, Object intervals, String token) throws Exception { return call("PUT", path(u.barber()), body(day.toString(), version, intervals), token); }
    private HttpResponse<String> hours(User u, String version, Object intervals, String token) throws Exception { return call("PUT", HOURS, Map.of("expectedVersion", version, "intervals", intervals), token); }
    private String login(User u) throws Exception { return expect(call("POST", "/api/v1/auth/login", Map.of("username", u.name(), "password", PASSWORD), null), 200, "OK").path("data").path("accessToken").asString(); }
    private JsonNode expect(HttpResponse<String> response, int status, String code) throws Exception { assertThat(response.statusCode()).as(response.body()).isEqualTo(status); var body = json.readTree(response.body()); assertThat(body.path("code").asString()).isEqualTo(code); return body; }
    private HttpResponse<String> call(String method, String path, Object body, String token) throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + environment.getRequiredProperty("local.server.port") + path)).timeout(Duration.ofSeconds(25));
        if (token != null) { req.header("Authorization", "Bearer " + token); } if (body != null) { req.header("Content-Type", "application/json"); }
        req.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8));
        return client.send(req.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
    private static void await(CountDownLatch latch) { try { if (!latch.await(15, TimeUnit.SECONDS)) { throw new IllegalStateException("Test gate timed out"); } } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); } }
    private void assertSql(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, int... codes) { assertThatThrownBy(action).isInstanceOf(DataAccessException.class).satisfies(ex -> assertThat(codes).contains(((SQLException) ((DataAccessException) ex).getMostSpecificCause()).getErrorCode())); }
}
