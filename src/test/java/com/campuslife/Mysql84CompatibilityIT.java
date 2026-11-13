package com.campuslife;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** Deployment gate only; ordinary MySQL 8.0 checks must not select this fixture. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("mysql")
@EnabledIfEnvironmentVariable(named = "CAMPUSLIFE_MYSQL84_IT", matches = "owned-isolated-fixture")
class Mysql84CompatibilityIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;

    @Test void exactCandidateUsesProjectIdentityAndUtcSession() {
        assertThat(jdbc.queryForObject("SELECT VERSION()", String.class)).isEqualTo("8.4.13");
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("campuslife_it");
        assertThat(jdbc.queryForObject("SELECT CURRENT_USER()", String.class)).isEqualTo("campuslife@%");
        assertThat(jdbc.queryForObject("SELECT @@session.time_zone", String.class)).isEqualTo("+00:00");
        assertThat(jdbc.queryForObject("SELECT @@session.foreign_key_checks", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT @@session.sql_mode", String.class)).contains("STRICT_TRANS_TABLES");
        assertThat(jdbc.queryForObject("SELECT @@session.restrict_fk_on_non_standard_key", Integer.class)).isEqualTo(1);
    }

    @Test void allOriginalMigrationsValidateWithoutRepairOrRepeatDdl() {
        flyway.validate();
        assertThat(jdbc.queryForList("SELECT CAST(version AS UNSIGNED) FROM flyway_schema_history WHERE success=1 ORDER BY CAST(version AS UNSIGNED)", Integer.class))
                .containsExactly(java.util.stream.IntStream.rangeClosed(1, 24).boxed().toArray(Integer[]::new));
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name<>'flyway_schema_history'", Integer.class)).isEqualTo(32);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND (engine<>'InnoDB' OR table_collation<>'utf8mb4_0900_ai_ci')", Integer.class)).isZero();
    }

    @Test void everyForeignKeyTargetsACompleteUniqueKey() {
        var foreignKeys = jdbc.queryForList("SELECT constraint_name,unique_constraint_name FROM information_schema.referential_constraints WHERE constraint_schema=DATABASE()");
        assertThat(foreignKeys).isNotEmpty();
        assertThat(foreignKeys).allSatisfy(row -> assertThat(row.get("UNIQUE_CONSTRAINT_NAME")).isNotNull());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND constraint_type='CHECK' AND enforced='YES'", Integer.class)).isGreaterThan(20);
    }

    @Test void projectAccountCannotReadAdministrativeTables() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.queryForList("SELECT User,Host FROM mysql.user"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForList("SHOW GRANTS", String.class)).noneSatisfy(grant -> assertThat(grant).contains("ALL PRIVILEGES ON *.*"));
    }
}
