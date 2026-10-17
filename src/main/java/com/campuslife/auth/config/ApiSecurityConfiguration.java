package com.campuslife.auth.config;

import com.campuslife.auth.security.AuthHttpErrors;
import com.campuslife.auth.security.SessionAuthenticationFilter;
import com.campuslife.auth.security.TokenService;
import com.campuslife.auth.service.SessionService;
import com.campuslife.common.exception.BusinessException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@Profile("mysql")
public class ApiSecurityConfiguration {
    @Bean
    public SecurityFilterChain apiSecurity(HttpSecurity http, TokenService tokens, SessionService sessions,
                                          AuthHttpErrors errors, Environment environment)
            throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable) // Header bearer credentials only; no cookie authentication.
                .httpBasic(AbstractHttpConfigurer::disable).formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable).requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(config -> config.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(config -> {
                    config.requestMatchers("/api/v1/system/health", "/api/v1/auth/register", "/api/v1/auth/login").permitAll();
                    if (environment.getProperty("springdoc.api-docs.enabled", Boolean.class, false)) {
                        config.requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**",
                                "/v3/api-docs.yaml").permitAll();
                    }
                    config.anyRequest().authenticated();
                })
                .exceptionHandling(config -> config
                        .authenticationEntryPoint((request, response, failure) -> errors.write(response,
                                TokenService.failure("AUTH_REQUIRED", "请先登录")))
                        .accessDeniedHandler((request, response, failure) -> errors.write(response,
                                new BusinessException(HttpStatus.FORBIDDEN, "ROLE_FORBIDDEN", "无权访问该资源"))))
                .addFilterBefore(new SessionAuthenticationFilter(tokens, sessions, errors), AnonymousAuthenticationFilter.class)
                .build();
    }
}
