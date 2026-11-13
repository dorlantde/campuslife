package com.campuslife;
import com.campuslife.coupon.bootstrap.CouponMaintenanceBootstrap;
import com.campuslife.coupon.service.CouponMaintenance;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import static org.assertj.core.api.Assertions.*;

/** Actual explicit bootstrap wiring, with no HTTP server or background maintenance loop. */
class CouponMaintenanceBootstrapCheck {
 @Test void explicitBootstrapIsNonWebAndDoesNotRequireJwtOrStartMessageConsumers(){
  assertThat(System.getenv("DB_NAME")).isEqualTo("campuslife_it");
  try(var context=new SpringApplicationBuilder(CouponMaintenanceBootstrap.class).web(WebApplicationType.NONE).profiles("mysql","coupon-maintenance")
    .run("--campuslife.coupon-redis.enabled=false","--campuslife.messaging.enabled=false")){
   assertThat(context.getBeansOfType(org.springframework.boot.web.server.WebServer.class)).isEmpty();
   assertThat(context.getBeansOfType(com.campuslife.auth.security.TokenService.class)).isEmpty();
   assertThat(context.getBeansOfType(com.campuslife.coupon.service.CouponRedisRepairJob.class)).isEmpty();
   assertThat(context.getBeansOfType(org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer.class)).isEmpty();
   assertThat(context.getBean(CouponMaintenance.class)).isNotNull();
  }
 }
}
