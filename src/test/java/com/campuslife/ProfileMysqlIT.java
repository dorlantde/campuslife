package com.campuslife;

import com.campuslife.auth.dto.ProfileUpdateRequest;
import com.campuslife.auth.security.AuthPrincipal;
import com.campuslife.auth.security.TokenService;
import com.campuslife.auth.service.ProfileUpdateService;
import com.campuslife.common.exception.BusinessException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("mysql")
@Import(ProfileMysqlIT.ClockConfiguration.class)
class ProfileMysqlIT {
    private static final String PASSWORD = "ProfileDemo_密码1!";
    private final HttpClient client = HttpClient.newHttpClient();
    private final List<Long> ownedIds = new ArrayList<>();
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired Environment environment;
    @Autowired TokenService tokens;
    @Autowired ProfileUpdateService profiles;
    @Autowired MutableClock clock;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach
    void resetClock() { clock.set(Instant.now().plusSeconds(5)); }

    @AfterEach
    void cleanupOwnedAccountsAndSessions() {
        for (long id : ownedIds) {
            jdbc.update("DELETE FROM auth_session WHERE account_id = ?", id);
            jdbc.update("DELETE FROM account WHERE id = ?", id);
        }
    }

    @Test
    void updateOwnNicknameKeepsBothSessionsAndOtherAccountsUnchanged() throws Exception {
        User user = register();
        User other = register();
        String token = login(user, PASSWORD);
        String secondToken = login(user, PASSWORD);
        String otherToken = login(other, PASSWORD);
        String hash = passwordHash(user);
        JsonNode result = envelope(patch(token, "  星海新昵称😀  ", "0"), 200, "OK").path("data");
        assertThat(result.path("id").asString()).isEqualTo(Long.toString(user.id()));
        assertThat(result.path("username").asString()).isEqualTo(user.name());
        assertThat(result.path("nickname").asString()).isEqualTo("星海新昵称😀");
        assertThat(result.path("version").isString()).isTrue();
        assertThat(result.path("version").asString()).isEqualTo("1");
        assertThat(result.path("role").asString()).isEqualTo("STUDENT");
        assertThat(result.path("status").asString()).isEqualTo("ACTIVE");
        for (String usable : List.of(token, secondToken)) {
            assertThat(envelope(call("GET", "/api/v1/me", null, usable), 200, "OK").path("data")).isEqualTo(result);
        }
        JsonNode otherProfile = envelope(call("GET", "/api/v1/me", null, otherToken), 200, "OK").path("data");
        assertThat(otherProfile.path("nickname").asString()).isEqualTo("资料验收同学");
        assertThat(otherProfile.path("version").asString()).isEqualTo("0");
        assertThat(passwordHash(user)).isEqualTo(hash);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_session WHERE account_id = ? AND revoked_at IS NULL",
                Integer.class, user.id())).isEqualTo(2);
    }

    @Test
    void staleVersionCannotOverwriteAndRereadingAllowsANewChange() throws Exception {
        User user = register();
        String token = login(user, PASSWORD);
        envelope(patch(token, "第一次修改", "0"), 200, "OK");
        envelope(patch(token, "覆盖旧页面", "0"), 409, "CONFIG_VERSION_CONFLICT");
        JsonNode current = envelope(call("GET", "/api/v1/me", null, token), 200, "OK").path("data");
        assertThat(current.path("nickname").asString()).isEqualTo("第一次修改");
        assertThat(current.path("version").asString()).isEqualTo("1");
        JsonNode updated = envelope(patch(token, "读取后再修改", current.path("version").asString()), 200, "OK").path("data");
        assertThat(updated.path("version").asString()).isEqualTo("2");
        assertThat(updated.path("nickname").asString()).isEqualTo("读取后再修改");
    }

    @Test
    void omittedAndIdenticalNicknamesDoNotAdvanceVersionButStillCheckIt() throws Exception {
        User user = register();
        String token = login(user, PASSWORD);
        for (String body : List.of("{\"expectedVersion\":\"0\"}",
                mapper.writeValueAsString(Map.of("nickname", "  资料验收同学  ", "expectedVersion", "0")))) {
            JsonNode profile = envelope(call("PATCH", "/api/v1/me", body, token), 200, "OK").path("data");
            assertThat(profile.path("nickname").asString()).isEqualTo("资料验收同学");
            assertThat(profile.path("version").asString()).isEqualTo("0");
        }
        envelope(patch(token, "新昵称", "0"), 200, "OK");
        envelope(call("PATCH", "/api/v1/me", "{\"expectedVersion\":\"0\"}", token), 409, "CONFIG_VERSION_CONFLICT");
        envelope(patch(token, "新昵称", "0"), 409, "CONFIG_VERSION_CONFLICT");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 40})
    void nicknameUsesUnicodeCodePointBoundaries(int characters) throws Exception {
        String nickname = "😀".repeat(characters);
        User user = register();
        JsonNode profile = envelope(patch(login(user, PASSWORD), nickname, "0"), 200, "OK").path("data");
        assertThat(profile.path("nickname").asString()).isEqualTo(nickname);
        assertThat(profile.path("version").asString()).isEqualTo("1");
    }

    @Test
    void malformedFieldsAndAttemptsToChangeIdentityHaveNoSideEffects() throws Exception {
        User user = register();
        String token = login(user, PASSWORD);
        String hash = passwordHash(user);
        List<String> invalid = new ArrayList<>(List.of("", "null", "{}", "{broken", "[]",
                "{\"nickname\":\"新昵称\"}", "{\"nickname\":null,\"expectedVersion\":\"0\"}",
                "{\"nickname\":123,\"expectedVersion\":\"0\"}", "{\"nickname\":true,\"expectedVersion\":\"0\"}",
                "{\"nickname\":{},\"expectedVersion\":\"0\"}", "{\"nickname\":[],\"expectedVersion\":\"0\"}",
                "{\"nickname\":\"\\ud800\",\"expectedVersion\":\"0\"}"));
        for (String rawVersion : List.of("null", "0", "0.0", "true", "{}", "[]", "\"\"", "\"-1\"", "\"01\"",
                "\"+1\"", "\" 0\"", "\"0 \"", "\"1e0\"", "\"０\"", "\"9223372036854775808\"", "\"100000000000000000000\"")) {
            invalid.add("{\"nickname\":\"新昵称\",\"expectedVersion\":" + rawVersion + "}");
        }
        for (String nickname : List.of("", "  \t\n", "a".repeat(41), "😀".repeat(41))) {
            invalid.add(mapper.writeValueAsString(Map.of("nickname", nickname, "expectedVersion", "0")));
        }
        for (String forbidden : List.of("id", "accountId", "username", "role", "status", "password", "version")) {
            invalid.add(mapper.writeValueAsString(Map.of("nickname", "新昵称", "expectedVersion", "0", forbidden, "1")));
        }
        for (String body : invalid) { envelope(call("PATCH", "/api/v1/me", body, token), 400, "VALIDATION_FAILED"); }
        JsonNode current = envelope(call("GET", "/api/v1/me", null, token), 200, "OK").path("data");
        assertThat(current.path("nickname").asString()).isEqualTo("资料验收同学");
        assertThat(current.path("version").asString()).isEqualTo("0");
        assertThat(passwordHash(user)).isEqualTo(hash);
    }

    @Test
    void versionsBeyondJavascriptSafeIntegerRemainExactDecimalStrings() throws Exception {
        User user = register();
        String token = login(user, PASSWORD);
        jdbc.update("UPDATE account SET version = ? WHERE id = ?", 9007199254740993L, user.id());
        JsonNode current = envelope(call("GET", "/api/v1/me", null, token), 200, "OK").path("data");
        assertThat(current.path("version").asString()).isEqualTo("9007199254740993");
        JsonNode result = envelope(patch(token, "精确版本", current.path("version").asString()), 200, "OK").path("data");
        assertThat(result.path("version").asString()).isEqualTo("9007199254740994");
        envelope(patch(token, "精度丢失版本", "9007199254740992"), 409, "CONFIG_VERSION_CONFLICT");
    }

    @Test
    void maximumVersionCannotWrapOrPartiallyChangeNickname() throws Exception {
        User user = register();
        String token = login(user, PASSWORD);
        jdbc.update("UPDATE account SET version = ? WHERE id = ?", Long.MAX_VALUE, user.id());
        envelope(patch(token, "溢出修改", Long.toString(Long.MAX_VALUE)), 409, "CONFIG_VERSION_CONFLICT");
        JsonNode unchanged = envelope(call("PATCH", "/api/v1/me", "{\"expectedVersion\":\"9223372036854775807\"}", token), 200, "OK").path("data");
        assertThat(unchanged.path("nickname").asString()).isEqualTo("资料验收同学");
        assertThat(unchanged.path("version").asString()).isEqualTo(Long.toString(Long.MAX_VALUE));
    }

    @Test
    void twoPagesUsingTheSameVersionHaveExactlyOneWinner() throws Exception {
        User user = register();
        String firstToken = login(user, PASSWORD);
        String secondToken = login(user, PASSWORD);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) { throw new IllegalStateException("Start gate timed out"); }
                return patch(firstToken, "页面甲", "0");
            });
            var second = executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) { throw new IllegalStateException("Start gate timed out"); }
                return patch(secondToken, "页面乙", "0");
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            HttpResponse<String> firstResponse = first.get(20, TimeUnit.SECONDS);
            HttpResponse<String> secondResponse = second.get(20, TimeUnit.SECONDS);
            assertThat(List.of(firstResponse.statusCode(), secondResponse.statusCode())).containsExactlyInAnyOrder(200, 409);
            var winner = firstResponse.statusCode() == 200 ? firstResponse : secondResponse;
            var loser = firstResponse.statusCode() == 200 ? secondResponse : firstResponse;
            JsonNode result = envelope(winner, 200, "OK").path("data");
            envelope(loser, 409, "CONFIG_VERSION_CONFLICT");
            JsonNode current = envelope(call("GET", "/api/v1/me", null, firstToken), 200, "OK").path("data");
            assertThat(current).isEqualTo(result);
            assertThat(current.path("version").asString()).isEqualTo("1");
            assertThat(current.path("nickname").asString()).isEqualTo(firstResponse.statusCode() == 200 ? "页面甲" : "页面乙");
            envelope(call("GET", "/api/v1/me", null, secondToken), 200, "OK");
        } finally { start.countDown(); }
    }

    @Test
    void nicknameAndVersionRollBackTogetherAfterAnActualDatabaseFailure() throws Exception {
        User user = register();
        String token = login(user, PASSWORD);
        var principal = principal(user, token);
        var request = request("回滚测试", "0");
        var transaction = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            profiles.update(principal, request);
            jdbc.update("UPDATE account SET version = -1 WHERE id = ?", user.id());
        })).isInstanceOf(DataAccessException.class);
        JsonNode current = envelope(call("GET", "/api/v1/me", null, token), 200, "OK").path("data");
        assertThat(current.path("nickname").asString()).isEqualTo("资料验收同学");
        assertThat(current.path("version").asString()).isEqualTo("0");
    }

    @ParameterizedTest
    @ValueSource(strings = {"REVOKED", "EXPIRED", "MISSING", "DISABLED"})
    void writeTransactionRechecksAuthenticationState(String state) throws Exception {
        User user = register();
        String token = login(user, PASSWORD);
        var principal = principal(user, token);
        String code;
        if (state.equals("REVOKED")) {
            envelope(call("POST", "/api/v1/auth/logout", null, token), 200, "OK");
            code = "SESSION_REVOKED";
        } else if (state.equals("EXPIRED")) {
            clock.set(tokens.verify(token).getExpiresAt());
            code = "SESSION_EXPIRED";
        } else if (state.equals("MISSING")) {
            jdbc.update("DELETE FROM auth_session WHERE account_id = ?", user.id());
            code = "TOKEN_INVALID";
        } else {
            jdbc.update("UPDATE account SET status = 'DISABLED' WHERE id = ?", user.id());
            code = "ACCOUNT_DISABLED";
        }
        var request = request("不能提交", "0");
        assertThatThrownBy(() -> profiles.update(principal, request)).isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).code()).isEqualTo(code));
        envelope(patch(token, "不能提交", "0"), state.equals("DISABLED") ? 403 : 401, code);
        assertThat(jdbc.queryForObject("SELECT nickname FROM account WHERE id = ?", String.class, user.id())).isEqualTo("资料验收同学");
        assertThat(jdbc.queryForObject("SELECT version FROM account WHERE id = ?", Long.class, user.id())).isZero();
    }

    @Test
    void passwordChangeMakesThePreviousProfileVersionStale() throws Exception {
        User user = register();
        String token = login(user, PASSWORD);
        String nextPassword = "ProfileNew_密码2!";
        envelope(call("POST", "/api/v1/me/password", mapper.writeValueAsString(
                Map.of("currentPassword", PASSWORD, "newPassword", nextPassword)), token), 200, "OK");
        envelope(patch(token, "旧身份修改", "0"), 401, "SESSION_REVOKED");
        String fresh = login(user, nextPassword);
        envelope(patch(fresh, "旧版本修改", "0"), 409, "CONFIG_VERSION_CONFLICT");
        assertThat(envelope(patch(fresh, "新身份修改", "1"), 200, "OK").path("data").path("version").asString()).isEqualTo("2");
    }

    @Test
    void anonymousAndUnsupportedMediaRequestsCannotWrite() throws Exception {
        envelope(patch(null, "匿名修改", "0"), 401, "AUTH_REQUIRED");
        User user = register();
        String token = login(user, PASSWORD);
        for (String kind : List.of("ACCEPT", "CONTENT_TYPE")) {
            var builder = HttpRequest.newBuilder(uri("/api/v1/me")).header("Authorization", "Bearer " + token)
                    .header("Content-Type", kind.equals("CONTENT_TYPE") ? "text/plain" : "application/json")
                    .header("Accept", kind.equals("ACCEPT") ? "text/plain" : "application/json")
                    .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"nickname\":\"失败修改\",\"expectedVersion\":\"0\"}", StandardCharsets.UTF_8));
            envelope(client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)),
                    kind.equals("ACCEPT") ? 406 : 415, kind.equals("ACCEPT") ? "NOT_ACCEPTABLE" : "UNSUPPORTED_MEDIA_TYPE");
        }
        assertThat(jdbc.queryForObject("SELECT version FROM account WHERE id = ?", Long.class, user.id())).isZero();
    }

    private ProfileUpdateRequest request(String nickname, String version) {
        return new ProfileUpdateRequest(mapper.valueToTree(nickname), mapper.valueToTree(version));
    }
    private AuthPrincipal principal(User user, String token) { return new AuthPrincipal(user.id(), tokens.verify(token).getId(), null); }
    private String passwordHash(User user) {
        return jdbc.queryForObject("SELECT password_hash FROM account WHERE id = ?", String.class, user.id());
    }
    private HttpResponse<String> patch(String token, String nickname, String version) throws Exception {
        return call("PATCH", "/api/v1/me", mapper.writeValueAsString(Map.of("nickname", nickname, "expectedVersion", version)), token);
    }
    private User register() throws Exception {
        String name = "pf_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        JsonNode account = envelope(call("POST", "/api/v1/auth/register", mapper.writeValueAsString(
                Map.of("username", name, "password", PASSWORD, "nickname", "资料验收同学")), null), 201, "OK").path("data");
        long id = Long.parseLong(account.path("id").asString());
        ownedIds.add(id);
        return new User(id, name);
    }
    private String login(User user, String password) throws Exception {
        return envelope(call("POST", "/api/v1/auth/login", mapper.writeValueAsString(
                Map.of("username", user.name(), "password", password)), null), 200, "OK").path("data").path("accessToken").asString();
    }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + environment.getRequiredProperty("local.server.port") + path); }
    private HttpResponse<String> call(String method, String path, String body, String token) throws Exception {
        var builder = HttpRequest.newBuilder(uri(path)).timeout(java.time.Duration.ofSeconds(15));
        if (token != null) { builder.header("Authorization", "Bearer " + token); }
        if (body != null) { builder.header("Content-Type", "application/json;charset=UTF-8"); }
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
    private JsonNode envelope(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        JsonNode body = mapper.readTree(response.body());
        assertThat(body.path("code").asString()).isEqualTo(code);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).containsIgnoringCase("charset=UTF-8");
        assertThat(response.headers().firstValue("X-Trace-Id")).contains(body.path("traceId").asString());
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        if (status == 401) { assertThat(response.headers().firstValue("WWW-Authenticate")).contains("Bearer"); }
        if (status >= 400) { assertThat(body.path("data").isNull()).isTrue(); }
        assertThat(response.body()).doesNotContain(PASSWORD, "passwordHash", "password_hash");
        return body;
    }
    private record User(long id, String name) { }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {
        @Bean @Primary MutableClock profileTestClock() { return new MutableClock(); }
    }
    static class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());
        void set(Instant instant) { now.set(instant); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }
}
