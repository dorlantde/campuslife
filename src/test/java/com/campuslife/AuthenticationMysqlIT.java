package com.campuslife;

import com.campuslife.auth.security.AuthPrincipal;
import com.campuslife.auth.security.TokenService;
import com.campuslife.auth.service.PasswordChangeTransactions;
import com.campuslife.auth.service.SessionService;
import com.campuslife.common.exception.BusinessException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("mysql")
@Import(AuthenticationMysqlIT.ClockConfiguration.class)
class AuthenticationMysqlIT {
    private static final String PASSWORD = " TestLogin_密码1! ";
    private static final String NEW_PASSWORD = " NewPass_密码2! ";
    private final HttpClient client = HttpClient.newHttpClient();
    private final List<Long> ownedIds = new ArrayList<>();
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired Environment environment;
    @Autowired TokenService tokens;
    @Autowired SessionService sessions;
    @Autowired MutableClock clock;
    @Autowired PasswordEncoder passwords;
    @Autowired PasswordChangeTransactions passwordChanges;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach
    void resetClock() { clock.set(Instant.now().plusSeconds(5)); }

    @AfterEach
    void cleanupOwnedAccountsAndSessions() {
        for (Long id : ownedIds) {
            jdbc.update("DELETE FROM auth_session WHERE account_id = ?", id);
            jdbc.update("DELETE FROM account WHERE id = ?", id);
        }
    }

    @Test
    void loginMeLogoutAndRepeatedLogoutFormACompleteSessionLifecycle() throws Exception {
        User user = register();
        HttpResponse<String> loginResponse = loginResponse(user.name(), PASSWORD);
        JsonNode login = envelope(loginResponse, 200, "OK").path("data");
        String token = login.path("accessToken").asString();
        assertThat(login.path("tokenType").asString()).isEqualTo("Bearer");
        var jwt = tokens.verify(token);
        assertThat(Instant.parse(login.path("expiresAt").asString())).isEqualTo(jwt.getIssuedAt().plusSeconds(7200));
        assertThat(login.path("account").path("id").asString()).isEqualTo(Long.toString(user.id()));
        assertThat(loginResponse.body()).doesNotContain(PASSWORD, "passwordHash", "password_hash");
        assertThat(loginResponse.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT jti FROM auth_session WHERE account_id = ?", String.class, user.id()))
                .isEqualTo(jwt.getId());
        JsonNode me = envelope(call("GET", "/api/v1/me", null, token), 200, "OK").path("data");
        assertThat(me.path("id").asString()).isEqualTo(Long.toString(user.id()));
        assertThat(me.path("role").asString()).isEqualTo("STUDENT");
        assertThat(me.path("version").isString()).isTrue();
        assertThat(envelope(call("POST", "/api/v1/auth/logout", null, token), 200, "OK").path("data").isNull()).isTrue();
        LocalDateTime revoked = jdbc.queryForObject("SELECT revoked_at FROM auth_session WHERE jti = ?",
                (rs, row) -> rs.getObject(1, LocalDateTime.class), jwt.getId());
        assertThat(revoked).isNotNull();
        envelope(call("GET", "/api/v1/me", null, token), 401, "SESSION_REVOKED");
        envelope(call("POST", "/api/v1/auth/logout", null, token), 200, "OK");
        LocalDateTime afterRepeat = jdbc.queryForObject("SELECT revoked_at FROM auth_session WHERE jti = ?",
                (rs, row) -> rs.getObject(1, LocalDateTime.class), jwt.getId());
        assertThat(afterRepeat).isEqualTo(revoked);
        envelope(call("GET", "/api/v1/system/health", null, token), 401, "SESSION_REVOKED");
    }

    @Test
    void independentLoginsRemainUsableWhenOneSessionLogsOut() throws Exception {
        User user = register();
        String first = login(user);
        String second = login(user);
        assertThat(first).isNotEqualTo(second);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_session WHERE account_id = ?", Integer.class, user.id()))
                .isEqualTo(2);
        envelope(call("POST", "/api/v1/auth/logout", null, first), 200, "OK");
        envelope(call("GET", "/api/v1/me", null, second), 200, "OK");
        envelope(call("GET", "/api/v1/me", null, first), 401, "SESSION_REVOKED");
    }

    @Test
    void missingUserAndWrongPasswordHaveTheSamePublicFailure() throws Exception {
        User user = register();
        JsonNode wrong = envelope(loginResponse(user.name(), "WrongPassword1!"), 401, "LOGIN_FAILED");
        JsonNode missing = envelope(loginResponse("lg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24),
                "WrongPassword1!"), 401, "LOGIN_FAILED");
        assertThat(wrong.path("message").asString()).isEqualTo(missing.path("message").asString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_session WHERE account_id = ?", Integer.class, user.id())).isZero();
    }

    @Test
    void loginNormalizesUsernameButDoesNotRewritePassword() throws Exception {
        User user = register();
        envelope(loginResponse(" " + user.name().toUpperCase(java.util.Locale.ROOT) + " ", PASSWORD), 200, "OK");
        envelope(loginResponse(user.name(), PASSWORD.strip()), 401, "LOGIN_FAILED");
        envelope(loginResponse(user.name(), PASSWORD.toLowerCase(java.util.Locale.ROOT)), 401, "LOGIN_FAILED");
    }

    @Test
    void disabledAccountCannotLoginOrUseExistingToken() throws Exception {
        User user = register();
        String token = login(user);
        jdbc.update("UPDATE account SET status = 'DISABLED' WHERE id = ?", user.id());
        envelope(loginResponse(user.name(), PASSWORD), 403, "ACCOUNT_DISABLED");
        envelope(loginResponse(user.name(), "WrongPassword1!"), 401, "LOGIN_FAILED");
        envelope(call("GET", "/api/v1/me", null, token), 403, "ACCOUNT_DISABLED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_session WHERE account_id = ?", Integer.class, user.id())).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/me", "/api/v1/auth/logout", "/api/v1/me/password", "/api/v1/private/future"})
    void anonymousRequestsToProtectedResourcesAreRejected(String path) throws Exception {
        envelope(call(path.endsWith("logout") || path.endsWith("password") ? "POST" : "GET", path, null, null), 401, "AUTH_REQUIRED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"garbage", "Basic dXNlcjpwYXNz", "Bearer", "Bearer invalid", "Bearer eyJhbGciOiJub25lIn0.e30."})
    void invalidAuthorizationIsNotIgnoredOnPublicHealth(String value) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/system/health")).header("Authorization", value).GET().build();
        envelope(client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)), 401, "TOKEN_INVALID");
    }

    @Test
    void invalidAuthorizationOnPublicLoginDoesNotCreateASession() throws Exception {
        User user = register();
        envelope(call("POST", "/api/v1/auth/login", credentials(user.name(), PASSWORD), "not-a-jwt"), 401, "TOKEN_INVALID");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_session WHERE account_id = ?", Integer.class, user.id())).isZero();
    }

    @Test
    void forgedSignatureAndDuplicateHeadersCannotAuthenticate() throws Exception {
        User user = register();
        String token = login(user);
        String[] parts = token.split("\\.");
        parts[2] = (parts[2].startsWith("A") ? "B" : "A") + parts[2].substring(1);
        envelope(call("GET", "/api/v1/me", null, String.join(".", parts)), 401, "TOKEN_INVALID");
        HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/me"))
                .header("Authorization", "Bearer " + token).header("Authorization", "Bearer " + token).GET().build();
        envelope(client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)), 401, "TOKEN_INVALID");
    }

    @Test
    void expirationIsEnforcedAtTheExactDeadlineIncludingLogout() throws Exception {
        User user = register();
        String token = login(user);
        Instant expiry = tokens.verify(token).getExpiresAt();
        clock.set(expiry.minusSeconds(1));
        envelope(call("GET", "/api/v1/me", null, token), 200, "OK");
        envelope(call("POST", "/api/v1/auth/logout", null, token), 200, "OK");
        clock.set(expiry);
        envelope(call("GET", "/api/v1/me", null, token), 401, "SESSION_EXPIRED");
        envelope(call("POST", "/api/v1/auth/logout", null, token), 401, "SESSION_EXPIRED");
    }

    @Test
    void signedTokenStillRequiresAMatchingPersistedSession() throws Exception {
        User user = register();
        String token = login(user);
        String jti = tokens.verify(token).getId();
        envelope(call("GET", "/api/v1/me", null, tokens.issue(user.id(), UUID.randomUUID().toString()).value()), 401, "TOKEN_INVALID");
        User other = register();
        envelope(call("GET", "/api/v1/me", null, tokens.issue(other.id(), jti).value()), 401, "TOKEN_INVALID");
        jdbc.update("DELETE FROM auth_session WHERE jti = ?", jti);
        envelope(call("GET", "/api/v1/me", null, token), 401, "TOKEN_INVALID");
    }

    @Test
    void databaseSessionExpiryAndTokenExpiryMustAgree() throws Exception {
        User user = register();
        String token = login(user);
        jdbc.update("UPDATE auth_session SET expires_at = DATE_ADD(expires_at, INTERVAL 1 SECOND) WHERE account_id = ?", user.id());
        envelope(call("GET", "/api/v1/me", null, token), 401, "TOKEN_INVALID");
        jdbc.update("UPDATE auth_session SET expires_at = DATE_ADD(created_at, INTERVAL 1 SECOND) WHERE account_id = ?", user.id());
        clock.set(clock.instant().plusSeconds(2));
        envelope(call("GET", "/api/v1/me", null, token), 401, "SESSION_EXPIRED");
    }

    @Test
    void queryParametersCookiesAndPreviousRequestCannotSupplyIdentity() throws Exception {
        User user = register();
        String token = login(user);
        envelope(call("GET", "/api/v1/me", null, token), 200, "OK");
        envelope(call("GET", "/api/v1/me?access_token=" + token, null, null), 401, "AUTH_REQUIRED");
        HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/me")).header("Cookie", "accessToken=" + token).GET().build();
        envelope(client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)), 401, "AUTH_REQUIRED");
        envelope(call("GET", "/api/v1/me", null, null), 401, "AUTH_REQUIRED");
    }

    @Test
    void profileAndRoleComeFromCurrentDatabaseState() throws Exception {
        User user = register();
        String token = login(user);
        jdbc.update("UPDATE account SET nickname = '更新昵称', role = 'MERCHANT', version = version + 1 WHERE id = ?", user.id());
        JsonNode me = envelope(call("GET", "/api/v1/me", null, token), 200, "OK").path("data");
        assertThat(me.path("nickname").asString()).isEqualTo("更新昵称");
        assertThat(me.path("role").asString()).isEqualTo("MERCHANT");
        assertThat(me.path("version").asString()).isEqualTo("1");
    }

    @Test
    void changedPasswordBetweenVerificationAndSessionCreationIsRejected() throws Exception {
        User user = register();
        String oldHash = jdbc.queryForObject("SELECT password_hash FROM account WHERE id = ?", String.class, user.id());
        jdbc.update("UPDATE account SET password_hash = 'changed-after-verification' WHERE id = ?", user.id());
        assertThatThrownBy(() -> sessions.create(user.id(), oldHash)).isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).code()).isEqualTo("LOGIN_FAILED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_session WHERE account_id = ?", Integer.class, user.id())).isZero();
    }

    @Test
    void malformedLoginAndUnsupportedAcceptDoNotCreateSessions() throws Exception {
        User user = register();
        envelope(call("POST", "/api/v1/auth/login", "{}", null), 400, "VALIDATION_FAILED");
        envelope(call("POST", "/api/v1/auth/login", "{broken", null), 400, "VALIDATION_FAILED");
        envelope(call("POST", "/api/v1/auth/login", mapper.writeValueAsString(Map.of("username", user.name(),
                "password", PASSWORD, "role", "ADMIN")), null), 400, "VALIDATION_FAILED");
        HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/auth/login"))
                .header("Content-Type", "application/json").header("Accept", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(credentials(user.name(), PASSWORD), StandardCharsets.UTF_8)).build();
        envelope(client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)), 406, "NOT_ACCEPTABLE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_session WHERE account_id = ?", Integer.class, user.id())).isZero();
    }

    @Test
    void authenticatedUnknownPathPreservesTheUnified404() throws Exception {
        User user = register();
        envelope(call("GET", "/api/v1/unknown", null, login(user)), 404, "RESOURCE_NOT_FOUND");
    }

    @Test
    void passwordChangeRevokesAllOwnSessionsAndPreservesAnotherAccount() throws Exception {
        User user = register();
        User other = register();
        String first = login(user);
        String second = login(user);
        String previouslyRevoked = login(user);
        String revokedJti = tokens.verify(previouslyRevoked).getId();
        envelope(call("POST", "/api/v1/auth/logout", null, previouslyRevoked), 200, "OK");
        LocalDateTime originalRevocation = jdbc.queryForObject("SELECT revoked_at FROM auth_session WHERE jti = ?",
                (rs, row) -> rs.getObject(1, LocalDateTime.class), revokedJti);
        String otherToken = login(other);
        String originalHash = passwordHash(user);
        clock.set(clock.instant().plusSeconds(1));
        assertThat(envelope(change(first, PASSWORD, NEW_PASSWORD), 200, "OK").path("data").isNull()).isTrue();
        assertThat(passwordHash(user)).startsWith("{bcrypt}$2b$12$").isNotEqualTo(originalHash);
        assertThat(passwords.matches(NEW_PASSWORD, passwordHash(user))).isTrue();
        assertThat(accountVersion(user)).isEqualTo(1L);
        assertThat(activeSessions(user)).isZero();
        LocalDateTime preserved = jdbc.queryForObject("SELECT revoked_at FROM auth_session WHERE jti = ?",
                (rs, row) -> rs.getObject(1, LocalDateTime.class), revokedJti);
        assertThat(preserved).isEqualTo(originalRevocation);
        envelope(call("GET", "/api/v1/me", null, first), 401, "SESSION_REVOKED");
        envelope(call("GET", "/api/v1/me", null, second), 401, "SESSION_REVOKED");
        envelope(change(first, NEW_PASSWORD, PASSWORD), 401, "SESSION_REVOKED");
        envelope(call("GET", "/api/v1/me", null, otherToken), 200, "OK");
        assertThat(activeSessions(other)).isEqualTo(1);
        assertThat(accountVersion(other)).isZero();
        envelope(loginResponse(user.name(), PASSWORD), 401, "LOGIN_FAILED");
        envelope(loginResponse(user.name(), NEW_PASSWORD.strip()), 401, "LOGIN_FAILED");
        String fresh = envelope(loginResponse(user.name(), NEW_PASSWORD), 200, "OK").path("data").path("accessToken").asString();
        JsonNode me = envelope(call("GET", "/api/v1/me", null, fresh), 200, "OK").path("data");
        assertThat(me.path("version").asString()).isEqualTo("1");
        assertThat(me.path("nickname").asString()).isEqualTo("登录测试学生");
        assertThat(me.path("role").asString()).isEqualTo("STUDENT");
    }

    @Test
    void incorrectCurrentPasswordLeavesPasswordAndSessionsUntouched() throws Exception {
        User user = register();
        String token = login(user);
        String originalHash = passwordHash(user);
        envelope(change(token, "WrongPassword1!", NEW_PASSWORD), 400, "CURRENT_PASSWORD_INCORRECT");
        assertThat(passwordHash(user)).isEqualTo(originalHash);
        assertThat(accountVersion(user)).isZero();
        assertThat(activeSessions(user)).isEqualTo(1);
        envelope(call("GET", "/api/v1/me", null, token), 200, "OK");
    }

    @Test
    void invalidPasswordChangeInputHasNoSideEffects() throws Exception {
        User user = register();
        String token = login(user);
        String originalHash = passwordHash(user);
        List<String> invalid = new ArrayList<>(List.of("", "{}", "{broken"));
        for (String value : List.of("1234567", "😀".repeat(7), "x".repeat(73), "中".repeat(25))) {
            invalid.add(mapper.writeValueAsString(Map.of("currentPassword", PASSWORD, "newPassword", value)));
        }
        invalid.add(mapper.writeValueAsString(Map.of("currentPassword", PASSWORD)));
        invalid.add(mapper.writeValueAsString(Map.of("newPassword", NEW_PASSWORD)));
        invalid.add(mapper.writeValueAsString(Map.of("currentPassword", PASSWORD, "newPassword", NEW_PASSWORD, "accountId", "1")));
        invalid.add(mapper.writeValueAsString(Map.of("currentPassword", PASSWORD, "newPassword", NEW_PASSWORD, "role", "ADMIN")));
        invalid.add("{\"currentPassword\":null,\"newPassword\":null}");
        for (String body : invalid) { envelope(call("POST", "/api/v1/me/password", body, token), 400, "VALIDATION_FAILED"); }
        assertThat(passwordHash(user)).isEqualTo(originalHash);
        assertThat(accountVersion(user)).isZero();
        assertThat(activeSessions(user)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"MINIMUM", "ASCII72", "CHINESE72", "EMOJI72"})
    void newPasswordUsesTheExistingUnicodeAndByteLimits(String kind) throws Exception {
        String value = switch (kind) {
            case "ASCII72" -> "x".repeat(72);
            case "CHINESE72" -> "中".repeat(24);
            case "EMOJI72" -> "😀".repeat(18);
            default -> "12345678";
        };
        User user = register();
        String token = login(user);
        envelope(change(token, PASSWORD, value), 200, "OK");
        assertThat(passwords.matches(value, passwordHash(user))).isTrue();
        envelope(loginResponse(user.name(), value), 200, "OK");
        assertThat(accountVersion(user)).isEqualTo(1L);
    }

    @Test
    void reusingTheSamePasswordStillRotatesItsHashAndRevokesSessions() throws Exception {
        User user = register();
        String token = login(user);
        String originalHash = passwordHash(user);
        envelope(change(token, PASSWORD, PASSWORD), 200, "OK");
        assertThat(passwordHash(user)).isNotEqualTo(originalHash);
        assertThat(passwords.matches(PASSWORD, passwordHash(user))).isTrue();
        assertThat(activeSessions(user)).isZero();
        envelope(call("GET", "/api/v1/me", null, token), 401, "SESSION_REVOKED");
        envelope(loginResponse(user.name(), PASSWORD), 200, "OK");
    }

    @Test
    void concurrentChangesCannotOverwriteTheFirstSuccessfulChange() throws Exception {
        User user = register();
        String firstToken = login(user);
        String secondToken = login(user);
        String alternative = " AnotherNew_密码3! ";
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) { throw new IllegalStateException("Start gate timed out"); }
                return change(firstToken, PASSWORD, NEW_PASSWORD);
            });
            var second = executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) { throw new IllegalStateException("Start gate timed out"); }
                return change(secondToken, PASSWORD, alternative);
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var firstResponse = first.get(20, TimeUnit.SECONDS);
            var secondResponse = second.get(20, TimeUnit.SECONDS);
            assertThat(List.of(firstResponse.statusCode(), secondResponse.statusCode()).stream().filter(status -> status == 200).count())
                    .isEqualTo(1);
            var success = firstResponse.statusCode() == 200 ? firstResponse : secondResponse;
            var rejected = firstResponse.statusCode() == 200 ? secondResponse : firstResponse;
            envelope(success, 200, "OK");
            assertThat(rejected.statusCode()).isIn(400, 401);
            assertThat(mapper.readTree(rejected.body()).path("code").asString()).isIn("CURRENT_PASSWORD_INCORRECT", "SESSION_REVOKED");
            String winner = firstResponse.statusCode() == 200 ? NEW_PASSWORD : alternative;
            String loser = firstResponse.statusCode() == 200 ? alternative : NEW_PASSWORD;
            assertThat(passwords.matches(winner, passwordHash(user))).isTrue();
            assertThat(passwords.matches(loser, passwordHash(user))).isFalse();
            assertThat(accountVersion(user)).isEqualTo(1L);
            assertThat(activeSessions(user)).isZero();
        } finally { start.countDown(); }
    }

    @Test
    void databaseFailureRollsBackPasswordVersionAndSessionRevocationTogether() throws Exception {
        User user = register();
        String token = login(user);
        login(user);
        String originalHash = passwordHash(user);
        String newHash = passwords.encode(NEW_PASSWORD);
        AuthPrincipal principal = principal(user, token);
        var transaction = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            passwordChanges.commit(principal, originalHash, newHash);
            // An actual MySQL CHECK failure forces the surrounding transaction to roll back.
            jdbc.update("UPDATE account SET version = -1 WHERE id = ?", user.id());
        })).isInstanceOf(DataAccessException.class);
        assertThat(passwordHash(user)).isEqualTo(originalHash);
        assertThat(accountVersion(user)).isZero();
        assertThat(activeSessions(user)).isEqualTo(2);
        envelope(call("GET", "/api/v1/me", null, token), 200, "OK");
        envelope(loginResponse(user.name(), NEW_PASSWORD), 401, "LOGIN_FAILED");
    }

    @Test
    void loginPreparedWithTheOldHashCannotCreateASessionAfterPasswordChange() throws Exception {
        User user = register();
        String token = login(user);
        String oldHash = passwordHash(user);
        envelope(change(token, PASSWORD, NEW_PASSWORD), 200, "OK");
        assertThatThrownBy(() -> sessions.create(user.id(), oldHash)).isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).code()).isEqualTo("LOGIN_FAILED"));
        assertThat(activeSessions(user)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"REVOKED", "EXPIRED", "MISSING"})
    void commitRechecksTheCurrentSessionAfterPasswordPreparation(String state) throws Exception {
        User user = register();
        String token = login(user);
        AuthPrincipal principal = principal(user, token);
        String originalHash = passwordHash(user);
        String newHash = passwords.encode(NEW_PASSWORD);
        String code;
        if (state.equals("REVOKED")) {
            envelope(call("POST", "/api/v1/auth/logout", null, token), 200, "OK");
            code = "SESSION_REVOKED";
        } else if (state.equals("EXPIRED")) {
            clock.set(tokens.verify(token).getExpiresAt());
            code = "SESSION_EXPIRED";
        } else {
            jdbc.update("DELETE FROM auth_session WHERE account_id = ?", user.id());
            code = "TOKEN_INVALID";
        }
        assertThatThrownBy(() -> passwordChanges.commit(principal, originalHash, newHash)).isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).code()).isEqualTo(code));
        assertThat(passwordHash(user)).isEqualTo(originalHash);
        assertThat(accountVersion(user)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"DISABLED", "PASSWORD_CHANGED"})
    void commitRechecksAccountStateAndVerifiedHash(String state) throws Exception {
        User user = register();
        String token = login(user);
        AuthPrincipal principal = principal(user, token);
        String verifiedHash = passwordHash(user);
        String newHash = passwords.encode(NEW_PASSWORD);
        String expectedCode;
        if (state.equals("DISABLED")) {
            jdbc.update("UPDATE account SET status = 'DISABLED' WHERE id = ?", user.id());
            expectedCode = "ACCOUNT_DISABLED";
        } else {
            jdbc.update("UPDATE account SET password_hash = 'externally-changed-test-hash' WHERE id = ?", user.id());
            expectedCode = "ACCOUNT_CREDENTIALS_CHANGED";
        }
        String hashBeforeCommit = passwordHash(user);
        assertThatThrownBy(() -> passwordChanges.commit(principal, verifiedHash, newHash)).isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).code()).isEqualTo(expectedCode));
        assertThat(passwordHash(user)).isEqualTo(hashBeforeCommit);
        assertThat(accountVersion(user)).isZero();
        assertThat(activeSessions(user)).isEqualTo(1);
    }

    @Test
    void unsupportedPasswordChangeMediaTypesDoNotChangeCredentials() throws Exception {
        User user = register();
        String token = login(user);
        String originalHash = passwordHash(user);
        String body = mapper.writeValueAsString(Map.of("currentPassword", PASSWORD, "newPassword", NEW_PASSWORD));
        var unacceptable = HttpRequest.newBuilder(uri("/api/v1/me/password"))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json").header("Accept", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        envelope(client.send(unacceptable, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)), 406, "NOT_ACCEPTABLE");
        var unsupported = HttpRequest.newBuilder(uri("/api/v1/me/password"))
                .header("Authorization", "Bearer " + token).header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        envelope(client.send(unsupported, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)), 415, "UNSUPPORTED_MEDIA_TYPE");
        assertThat(passwordHash(user)).isEqualTo(originalHash);
        assertThat(activeSessions(user)).isEqualTo(1);
    }

    private HttpResponse<String> change(String token, String current, String next) throws Exception {
        return call("POST", "/api/v1/me/password", mapper.writeValueAsString(
                Map.of("currentPassword", current, "newPassword", next)), token);
    }
    private String passwordHash(User user) {
        return jdbc.queryForObject("SELECT password_hash FROM account WHERE id = ?", String.class, user.id());
    }
    private long accountVersion(User user) {
        return jdbc.queryForObject("SELECT version FROM account WHERE id = ?", Long.class, user.id());
    }
    private int activeSessions(User user) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM auth_session WHERE account_id = ? AND revoked_at IS NULL", Integer.class, user.id());
    }
    private AuthPrincipal principal(User user, String token) { return new AuthPrincipal(user.id(), tokens.verify(token).getId(), null); }

    private User register() throws Exception {
        String name = "lg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        JsonNode account = envelope(call("POST", "/api/v1/auth/register", mapper.writeValueAsString(
                Map.of("username", name, "password", PASSWORD, "nickname", "登录测试学生")), null), 201, "OK").path("data");
        long id = Long.parseLong(account.path("id").asString());
        ownedIds.add(id);
        return new User(id, name);
    }
    private String login(User user) throws Exception {
        return envelope(loginResponse(user.name(), PASSWORD), 200, "OK").path("data").path("accessToken").asString();
    }
    private HttpResponse<String> loginResponse(String name, String password) throws Exception {
        return call("POST", "/api/v1/auth/login", credentials(name, password), null);
    }
    private String credentials(String name, String password) { return mapper.writeValueAsString(Map.of("username", name, "password", password)); }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + environment.getRequiredProperty("local.server.port") + path); }
    private HttpResponse<String> call(String method, String path, String body, String token) throws Exception {
        var request = HttpRequest.newBuilder(uri(path)).timeout(java.time.Duration.ofSeconds(15));
        if (token != null) { request.header("Authorization", "Bearer " + token); }
        if (body != null) { request.header("Content-Type", "application/json;charset=UTF-8"); }
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
    private JsonNode envelope(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        JsonNode body = mapper.readTree(response.body());
        assertThat(body.path("code").asString()).isEqualTo(code);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).containsIgnoringCase("charset=UTF-8");
        assertThat(response.headers().firstValue("X-Trace-Id")).contains(body.path("traceId").asString());
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        if (status == 401) { assertThat(response.headers().firstValue("WWW-Authenticate")).contains("Bearer"); }
        if (status >= 400) { assertThat(body.path("data").isNull()).isTrue(); }
        assertThat(response.body()).doesNotContain(PASSWORD, NEW_PASSWORD, "passwordHash", "password_hash");
        return body;
    }
    private record User(long id, String name) { }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {
        @Bean @Primary MutableClock authenticationTestClock() { return new MutableClock(); }
    }
    static class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());
        void set(Instant instant) { now.set(instant); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }
}
