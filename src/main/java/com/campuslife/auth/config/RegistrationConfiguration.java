package com.campuslife.auth.config;

import java.util.Map;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
@Profile("mysql")
@MapperScan("com.campuslife.auth.mapper")
public class RegistrationConfiguration {
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new DelegatingPasswordEncoder("bcrypt", Map.of("bcrypt",
                new BCryptPasswordEncoder(BCryptPasswordEncoder.BCryptVersion.$2B, 12)));
    }
}
