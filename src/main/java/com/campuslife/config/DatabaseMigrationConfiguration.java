package com.campuslife.config;

import java.sql.Connection;
import java.sql.SQLException;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@Profile("mysql")
public class DatabaseMigrationConfiguration {
    @Bean
    public FlywayMigrationStrategy projectDatabaseMigration(Environment environment) {
        String expectedDatabase = environment.getProperty("DB_NAME", "campuslife");
        if (!expectedDatabase.matches("campuslife(?:_[a-z0-9]+)?")) {
            throw new IllegalStateException("DB_NAME must identify a CampusLife project database");
        }
        return flyway -> {
            try (Connection connection = flyway.getConfiguration().getDataSource().getConnection()) {
                if (!expectedDatabase.equals(connection.getCatalog())) {
                    throw new IllegalStateException("Migration target does not match DB_NAME");
                }
            } catch (SQLException exception) {
                throw new IllegalStateException("Cannot verify the migration target database", exception);
            }
            flyway.migrate();
        };
    }
}
