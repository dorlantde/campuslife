package com.campuslife.operations.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;

/** A separate local listener and authentication manager; never authenticates a business account. */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class ManagementSecurityConfiguration {
    @Bean
    InitializingBean managementBoundary(Environment env) {
        return () -> {
            boolean enabled = env.getProperty("campuslife.management.enabled", Boolean.class, false);
            int port = env.getProperty("management.server.port", Integer.class, -1);
            if (!enabled && port != -1) {
                throw new IllegalStateException("Management HTTP requires explicit enablement");
            }
            if (enabled && (port < 1024 || port > 65535
                    || port == env.getProperty("server.port", Integer.class, 8080)
                    || !"127.0.0.1".equals(env.getProperty("management.server.address"))
                    || !env.getProperty("campuslife.management.password", "").matches("[a-f0-9]{64}"))) {
                throw new IllegalStateException("Management requires a separate loopback port and generated credential");
            }
        };
    }

    @Bean
    @Order(-100)
    SecurityFilterChain managementSecurity(HttpSecurity http, Environment env) throws Exception {
        int port = env.getProperty("management.server.port", Integer.class, -1);
        byte[] expected = env.getProperty("campuslife.management.password", "").getBytes(StandardCharsets.UTF_8);
        return http.securityMatcher(request -> port > 0 && request.getLocalPort() == port)
                .csrf(AbstractHttpConfigurer::disable).formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable).requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(c -> c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authenticationManager(authentication -> {
                    String supplied = String.valueOf(authentication.getCredentials());
                    if (!"campuslife_ops".equals(authentication.getName()) || !MessageDigest.isEqual(expected,
                            supplied.getBytes(StandardCharsets.UTF_8))) {
                        throw new BadCredentialsException("Invalid operations credential");
                    }
                    return UsernamePasswordAuthenticationToken.authenticated("campuslife_ops", null,
                            List.of(new SimpleGrantedAuthority("ROLE_OPERATIONS")));
                })
                .httpBasic(c -> c.realmName("CampusLife operations"))
                .authorizeHttpRequests(c -> c
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/liveness",
                                "/actuator/health/readiness", "/actuator/metrics", "/actuator/metrics/{name}")
                        .hasRole("OPERATIONS")
                        .anyRequest().denyAll())
                .build();
    }
}
