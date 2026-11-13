package com.campuslife.order.config;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
@Configuration(proxyBeanMethods=false)
@Profile("mysql")
@MapperScan("com.campuslife.order.mapper")
@EnableScheduling
public class OrderConfiguration {}
