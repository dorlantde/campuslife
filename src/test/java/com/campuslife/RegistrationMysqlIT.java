package com.campuslife;

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
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** Real HTTP plus real MySQL, including committed inserts from independent request threads. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("mysql")
class RegistrationMysqlIT {
    private static final String PASSWORD = " TestPass_密码1! ";
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<String> ownedUsernames = new ArrayList<>();

    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired Environment environment;
    @Autowired PasswordEncoder passwords;

    @AfterEach
    void removeOnlyAccountsOwnedByThisTest() {
        // HTTP requests commit on their own threads; test-thread @Transactional cannot roll these back.
        for (String username : ownedUsernames) {
            jdbc.update("DELETE FROM account WHERE username = ? AND role = 'STUDENT'", username);
        }
    }

    @Test
    void registrationCreatesStudentAndStoresOnlyAnEncodedPassword() throws Exception {
        String name = username();
        Map<String, String> request = request("  " + name.toUpperCase(java.util.Locale.ROOT) + "  ", PASSWORD, "  星海同学😀  ");
        HttpResponse<String> response = post(mapper.writeValueAsString(request));
        JsonNode body = envelope(response, 201, "OK");
        JsonNode account = body.path("data");
        assertThat(account.size()).isEqualTo(6);
        assertThat(account.path("id").isString()).isTrue();
        assertThat(account.path("id").asString()).matches("[1-9][0-9]*");
        assertThat(account.path("username").asString()).isEqualTo(name);
        assertThat(account.path("nickname").asString()).isEqualTo("星海同学😀");
        assertThat(account.path("role").asString()).isEqualTo("STUDENT");
        assertThat(account.path("status").asString()).isEqualTo("ACTIVE");
        assertThat(account.path("version").isString()).isTrue();
        assertThat(account.path("version").asString()).isEqualTo("0");
        String hash = hash(name);
        assertThat(hash).startsWith("{bcrypt}$2b$12$").hasSize(68);
        assertThat(response.body()).doesNotContain(hash);
        assertThat(passwords.matches(PASSWORD, hash)).isTrue();
        assertThat(passwords.matches(PASSWORD.strip(), hash)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_session WHERE account_id = ?", Integer.class,
                Long.parseLong(account.path("id").asString()))).isZero();
    }

    @Test
    void repeatedNormalizedUsernameReturnsConflictWithoutReplacingTheAccount() throws Exception {
        String name = username();
        envelope(post(mapper.writeValueAsString(request(name, PASSWORD, "原昵称"))), 201, "OK");
        String originalHash = hash(name);
        envelope(post(mapper.writeValueAsString(request(" " + name.toUpperCase(java.util.Locale.ROOT) + " ",
                "AnotherPassword1!", "新昵称"))), 409, "USERNAME_EXISTS");
        assertThat(count(name)).isEqualTo(1);
        assertThat(hash(name)).isEqualTo(originalHash);
        assertThat(jdbc.queryForObject("SELECT nickname FROM account WHERE username = ?", String.class, name))
                .isEqualTo("原昵称");
    }

    @Test
    void concurrentRegistrationProducesOneCreatedAccountAndThreeConflicts() throws Exception {
        String name = username();
        String body = mapper.writeValueAsString(request(name, PASSWORD, "并发学生"));
        CountDownLatch ready = new CountDownLatch(4);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(4)) {
            List<Future<HttpResponse<String>>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) { throw new IllegalStateException("Start gate timed out"); }
                    return post(body);
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int created = 0;
            int conflicted = 0;
            for (Future<HttpResponse<String>> future : futures) {
                HttpResponse<String> response = future.get(20, TimeUnit.SECONDS);
                if (response.statusCode() == 201) {
                    envelope(response, 201, "OK");
                    created++;
                } else {
                    envelope(response, 409, "USERNAME_EXISTS");
                    conflicted++;
                }
            }
            assertThat(created).isEqualTo(1);
            assertThat(conflicted).isEqualTo(3);
            assertThat(count(name)).isEqualTo(1);
        } finally {
            start.countDown();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRequests")
    void invalidOrInjectedFieldsAreRejectedWithoutInserting(String label, Map<String, Object> changes) throws Exception {
        String name = username();
        Map<String, Object> body = new LinkedHashMap<>(request(name, PASSWORD, "学生"));
        body.putAll(changes);
        envelope(post(mapper.writeValueAsString(body)), 400, "VALIDATION_FAILED");
        assertThat(count(name)).isZero();
    }

    static Stream<Arguments> invalidRequests() {
        List<Arguments> cases = new ArrayList<>();
        cases.add(Arguments.of("missing username", nullableChange("username")));
        cases.add(Arguments.of("missing password", nullableChange("password")));
        cases.add(Arguments.of("missing nickname", nullableChange("nickname")));
        cases.add(Arguments.of("short username", Map.of("username", "abc")));
        cases.add(Arguments.of("long username", Map.of("username", "x".repeat(33))));
        cases.add(Arguments.of("SQL-like username", Map.of("username", "x'; DROP TABLE account;--")));
        cases.add(Arguments.of("blank nickname", Map.of("nickname", " \t ")));
        cases.add(Arguments.of("long nickname", Map.of("nickname", "😀".repeat(41))));
        cases.add(Arguments.of("short password", Map.of("password", "1234567")));
        cases.add(Arguments.of("short emoji password", Map.of("password", "😀".repeat(7))));
        cases.add(Arguments.of("73-byte password", Map.of("password", "x".repeat(73))));
        cases.add(Arguments.of("75-byte Unicode password", Map.of("password", "中".repeat(25))));
        cases.add(Arguments.of("role injection", Map.of("role", "ADMIN")));
        cases.add(Arguments.of("status injection", Map.of("status", "DISABLED")));
        cases.add(Arguments.of("ID injection", Map.of("id", "1")));
        cases.add(Arguments.of("version injection", Map.of("version", "99")));
        return cases.stream();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ASCII", "CHINESE", "EMOJI"})
    void exactly72Utf8BytesAreAcceptedWithoutTruncation(String kind) throws Exception {
        String password = switch (kind) {
            case "CHINESE" -> "中".repeat(24);
            case "EMOJI" -> "😀".repeat(18);
            default -> "x".repeat(72);
        };
        String name = username();
        envelope(post(mapper.writeValueAsString(request(name, password, "😀".repeat(40)))), 201, "OK");
        assertThat(passwords.matches(password, hash(name))).isTrue();
        assertThat(jdbc.queryForObject("SELECT nickname FROM account WHERE username = ?", String.class, name))
                .isEqualTo("😀".repeat(40));
    }

    @Test
    void samePasswordForTwoAccountsGetsDifferentSalt() throws Exception {
        String first = username();
        String second = username();
        envelope(post(mapper.writeValueAsString(request(first, PASSWORD, "学生甲"))), 201, "OK");
        envelope(post(mapper.writeValueAsString(request(second, PASSWORD, "学生乙"))), 201, "OK");
        assertThat(hash(first)).isNotEqualTo(hash(second));
    }

    @Test
    void missingBodyAndMalformedJsonUseTheUnifiedError() throws Exception {
        envelope(post(""), 400, "VALIDATION_FAILED");
        envelope(post("{broken"), 400, "VALIDATION_FAILED");
    }

    @Test
    void unsupportedAcceptDoesNotCreateAnAccount() throws Exception {
        String name = username();
        HttpRequest request = HttpRequest.newBuilder(registrationUri()).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json").header("Accept", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(request(name, PASSWORD, "学生")),
                        StandardCharsets.UTF_8)).build();
        envelope(client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)), 406, "NOT_ACCEPTABLE");
        assertThat(count(name)).isZero();
    }

    @Test
    void unsupportedContentTypeDoesNotCreateAnAccount() throws Exception {
        String name = username();
        HttpRequest request = HttpRequest.newBuilder(registrationUri()).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(request(name, PASSWORD, "学生")),
                        StandardCharsets.UTF_8)).build();
        envelope(client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)), 415, "UNSUPPORTED_MEDIA_TYPE");
        assertThat(count(name)).isZero();
    }

    private HttpResponse<String> post(String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(registrationUri())
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json;charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private URI registrationUri() {
        return URI.create("http://127.0.0.1:" + environment.getRequiredProperty("local.server.port") + "/api/v1/auth/register");
    }

    private JsonNode envelope(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        JsonNode body = mapper.readTree(response.body());
        assertThat(body.path("code").asString()).isEqualTo(code);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).containsIgnoringCase("charset=UTF-8");
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.headers().firstValue("X-Trace-Id")).contains(body.path("traceId").asString());
        assertThat(response.body()).doesNotContain(PASSWORD, "passwordHash", "password_hash", "accessToken");
        if (status >= 400) { assertThat(body.path("data").isNull()).isTrue(); }
        return body;
    }

    private String username() {
        String name = "rg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        assertThat(count(name)).isZero();
        ownedUsernames.add(name);
        return name;
    }

    private int count(String name) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM account WHERE username = ?", Integer.class, name);
    }

    private String hash(String name) {
        return jdbc.queryForObject("SELECT password_hash FROM account WHERE username = ?", String.class, name);
    }

    private static Map<String, String> request(String username, String password, String nickname) {
        return Map.of("username", username, "password", password, "nickname", nickname);
    }

    private static Map<String, Object> nullableChange(String field) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(field, null);
        return result;
    }
}
