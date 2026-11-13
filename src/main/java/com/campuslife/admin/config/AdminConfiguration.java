package com.campuslife.admin.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("mysql")
@MapperScan("com.campuslife.admin.mapper")
public class AdminConfiguration {
}
