package com.campuslife.admin.bootstrap;

import com.campuslife.auth.config.RegistrationConfiguration;
import com.campuslife.common.exception.BusinessException;
import com.campuslife.config.DatabaseMigrationConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;

/** Separate, non-web process. Normal CampusLife startup does not activate this configuration. */
@Configuration(proxyBeanMethods = false)
@Profile("admin-bootstrap")
@EnableAutoConfiguration
@Import({RegistrationConfiguration.class, DatabaseMigrationConfiguration.class, AdminAccountInitializer.class})
public class AdminBootstrap {
    public static void main(String[] args) {
        if (args.length != 0) { System.err.println("This command accepts credentials only through local process environment."); System.exit(1); }
        int exit = 1;
        try (var context = new SpringApplicationBuilder(AdminBootstrap.class).web(WebApplicationType.NONE)
                .profiles("mysql", "admin-bootstrap").run("--spring.main.web-application-type=none")) {
            var environment = context.getEnvironment();
            String id = context.getBean(AdminAccountInitializer.class).create(
                    environment.getRequiredProperty("CAMPUSLIFE_ADMIN_USERNAME"),
                    environment.getRequiredProperty("CAMPUSLIFE_ADMIN_NICKNAME"),
                    environment.getRequiredProperty("CAMPUSLIFE_ADMIN_PASSWORD"));
            System.out.println("Created ACTIVE ADMIN business account, id=" + id + ". No HTTP server was started.");
            exit = 0;
        } catch (BusinessException exception) {
            System.err.println(exception.code() + ": " + exception.getMessage());
        } catch (Exception exception) {
            System.err.println("Admin initialization failed. Check local database configuration and required process environment.");
        }
        System.exit(exit);
    }
}
