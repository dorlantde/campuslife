package com.campuslife;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("mysql")
@Transactional
class MysqlIntegrationIT {
    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Flyway flyway;

    @BeforeEach
    void requireProjectSchema() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).startsWith("campuslife");
    }

    @Test
    void migrationCreatesExpectedTablesAndTracksVersion() {
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("1");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema = DATABASE() AND table_name IN ('account', 'auth_session')", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = 1",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT @@session.time_zone", String.class)).isEqualTo("+00:00");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void repeatedMigrationDoesNotRecreateTables() {
        flyway.validate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @Test
    void nicknamePreservesChineseAndEmoji() {
        long id = insertAccount(username(), "星海同学😀");
        assertThat(jdbc.queryForObject("SELECT nickname FROM account WHERE id = ?", String.class, id))
                .isEqualTo("星海同学😀");
    }

    @Test
    void usernameCannotBeRepeated() {
        String name = username();
        insertAccount(name, "学生甲");
        assertSqlViolation(() -> insertAccount(name, "学生乙"), "uk_account_username", 1062);
    }

    @Test
    void invalidRoleIsRejectedByDatabase() {
        assertSqlViolation(() -> jdbc.update("INSERT INTO account (username, password_hash, nickname, role) "
                + "VALUES (?, 'test-digest', '学生', 'student')", username()), "ck_account_role", 3819);
    }

    @Test
    void invalidUsernameIsRejectedByDatabase() {
        assertSqlViolation(() -> insertAccount("Bad-Name", "学生"), "ck_account_username", 3819);
    }

    @Test
    void sessionMustReferenceAnExistingAccount() {
        assertSqlViolation(() -> insertSession(Long.MAX_VALUE, UUID.randomUUID().toString(), future()),
                "fk_auth_session_account", 1216, 1452);
    }

    @Test
    void sessionJtiCannotBeRepeated() {
        long id = insertAccount(username(), "学生");
        String jti = UUID.randomUUID().toString();
        insertSession(id, jti, future());
        assertSqlViolation(() -> insertSession(id, jti, future()), "uk_auth_session_jti", 1062);
    }

    @Test
    void accountWithSessionCannotBePhysicallyDeleted() {
        long id = insertAccount(username(), "学生");
        insertSession(id, UUID.randomUUID().toString(), future());
        assertSqlViolation(() -> jdbc.update("DELETE FROM account WHERE id = ?", id), "fk_auth_session_account", 1217, 1451);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account WHERE id = ?", Integer.class, id)).isEqualTo(1);
    }

    @Test
    void sessionUsesMillisecondUtcValuesAndAllowsRevocation() {
        long accountId = insertAccount(username(), "学生");
        String jti = UUID.randomUUID().toString();
        LocalDateTime expiry = future().withNano(123_000_000);
        insertSession(accountId, jti, expiry);
        LocalDateTime stored = jdbc.queryForObject("SELECT expires_at FROM auth_session WHERE jti = ?",
                (rs, rowNum) -> rs.getObject(1, LocalDateTime.class), jti);
        assertThat(stored).isEqualTo(expiry);
        assertThat(jdbc.queryForObject("SELECT revoked_at IS NULL FROM auth_session WHERE jti = ?", Boolean.class, jti))
                .isTrue();
        jdbc.update("UPDATE auth_session SET revoked_at = CURRENT_TIMESTAMP(3) WHERE jti = ?", jti);
        assertThat(jdbc.queryForObject("SELECT revoked_at IS NOT NULL FROM auth_session WHERE jti = ?", Boolean.class, jti))
                .isTrue();
    }

    @Test
    void sessionCannotExpireBeforeCreation() {
        long id = insertAccount(username(), "学生");
        assertSqlViolation(() -> insertSession(id, UUID.randomUUID().toString(), LocalDateTime.now(ZoneOffset.UTC).minusDays(1)),
                "ck_auth_session_expiry", 3819);
    }

    private long insertAccount(String username, String nickname) {
        jdbc.update("INSERT INTO account (username, password_hash, nickname, role) VALUES (?, 'test-digest', ?, 'STUDENT')",
                username, nickname);
        return jdbc.queryForObject("SELECT id FROM account WHERE username = ?", Long.class, username);
    }

    private void insertSession(long accountId, String jti, LocalDateTime expiry) {
        jdbc.update("INSERT INTO auth_session (account_id, jti, expires_at) VALUES (?, ?, ?)", accountId, jti, expiry);
    }

    private static String username() {
        return "it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    private static LocalDateTime future() {
        return LocalDateTime.now(ZoneOffset.UTC).plusHours(1).withNano(0);
    }

    private void assertSqlViolation(ThrowingCallable operation, String constraint, int... allowedErrorCodes) {
        assertThatThrownBy(operation).isInstanceOf(DataAccessException.class).satisfies(failure -> {
            Throwable cause = ((DataAccessException) failure).getMostSpecificCause();
            assertThat(cause).isInstanceOf(SQLException.class);
            SQLException sqlException = (SQLException) cause;
            assertThat(allowedErrorCodes).contains(sqlException.getErrorCode());
            // MySQL can withhold the detailed FK name and return 1216/1217 instead of 1452/1451.
            if (constraint.startsWith("fk_")) {
                assertThat(sqlException.getSQLState()).isEqualTo("23000");
                assertThat(cause.getMessage()).contains("foreign key constraint");
            } else {
                assertThat(cause.getMessage()).contains(constraint);
            }
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.table_constraints "
                + "WHERE constraint_schema = DATABASE() AND constraint_name = ?", Integer.class, constraint)).isEqualTo(1);
    }
}
