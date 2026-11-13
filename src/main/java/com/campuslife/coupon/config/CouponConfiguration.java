package com.campuslife.coupon.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("mysql")
@MapperScan("com.campuslife.coupon.mapper")
public class CouponConfiguration {}
