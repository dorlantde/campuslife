package com.campuslife.messaging.config;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.*;
@Configuration(proxyBeanMethods=false) @Profile("mysql") @MapperScan("com.campuslife.messaging.mapper")
public class MessagingConfiguration {}
