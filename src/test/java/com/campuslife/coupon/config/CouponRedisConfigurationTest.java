package com.campuslife.coupon.config;

import com.campuslife.merchant.config.RatingCacheProperties;
import io.lettuce.core.ClientOptions;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

/** Spring profile wiring and effective client budgets; no Redis connection is opened here. */
class CouponRedisConfigurationTest {
    void budgets(long initializationMs,String... profiles) {
        try(var ctx=new AnnotationConfigApplicationContext()) {
            ctx.getEnvironment().setActiveProfiles(profiles);
            ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("isolated-budget-test",Map.of(
                "campuslife.coupon-redis.enabled","true","campuslife.coupon-redis.namespace","budget_test",
                "campuslife.coupon-redis.username","coupon_test","campuslife.coupon-redis.password","synthetic-non-credential-value-00000000")));
            ctx.registerBean(RatingCacheProperties.class,()->new RatingCacheProperties(false,"","127.0.0.1",6379));
            ctx.registerBean(ObjectMapper.class,()->new ObjectMapper());ctx.register(CouponRedisConfiguration.class);ctx.refresh();
            var factory=ctx.getBean("couponRedisConnectionFactory",LettuceConnectionFactory.class);
            var config=factory.getClientConfiguration();var options=config.getClientOptions().orElseThrow();
            assertThat(factory.getTimeout()).isEqualTo(initializationMs);
            assertThat(config.getCommandTimeout().toMillis()).isEqualTo(initializationMs);
            assertThat(config.getShutdownTimeout().toMillis()).isEqualTo(200);
            assertThat(options.getSocketOptions().getConnectTimeout().toMillis()).isEqualTo(300);
            assertThat(options.getTimeoutOptions().isTimeoutCommands()).isTrue();
            assertThat(options.getTimeoutOptions().isApplyConnectionTimeout()).isFalse();
            var expiry=options.getTimeoutOptions().getSource();
            assertThat(expiry.getTimeUnit().toMillis(expiry.getTimeout(null))).isEqualTo(500);
            assertThat(options.getRequestQueueSize()).isEqualTo(128);
            assertThat(options.getDisconnectedBehavior()).isEqualTo(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
            assertThat(factory.getEagerInitialization()).isFalse();
        }
    }
    @Test void httpAndBackgroundKeepOriginalBudget(){budgets(500,"mysql");}
    @Test void unrelatedProfileDoesNotEnableMaintenanceBudget(){budgets(500,"mysql","unrelated");}
    @Test void onlyExplicitMaintenanceProfileSeparatesInitializationBudget(){budgets(5000,"mysql","coupon-maintenance");}
}
