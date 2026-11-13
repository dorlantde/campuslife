package com.campuslife.coupon.config;
import com.campuslife.coupon.redis.CouponRedisStore;
import com.campuslife.merchant.config.RatingCacheProperties;
import io.lettuce.core.*;
import io.lettuce.core.resource.DefaultClientResources;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.data.redis.connection.*;
import org.springframework.data.redis.connection.lettuce.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;
@Configuration(proxyBeanMethods=false) @Profile("mysql")
@EnableConfigurationProperties(CouponRedisProperties.class)
public class CouponRedisConfiguration {
 @Configuration(proxyBeanMethods=false) @Profile("mysql")
 @ConditionalOnProperty(name="campuslife.coupon-redis.enabled",havingValue="true")
 static class Enabled {
  @Bean(destroyMethod="shutdown") DefaultClientResources couponRedisResources(){return DefaultClientResources.builder().ioThreadPoolSize(2).computationThreadPoolSize(2).build();}
  @Bean LettuceConnectionFactory couponRedisConnectionFactory(CouponRedisProperties p,RatingCacheProperties rating,@Qualifier("couponRedisResources") DefaultClientResources resources,Environment environment){
   if(rating.enabled()&&rating.host().equals(p.host())&&rating.port()==p.port()){throw new IllegalArgumentException("Coupon reservations require an instance separate from the rating cache");}
   var standalone=new RedisStandaloneConfiguration(p.host(),p.port());standalone.setUsername(p.username());standalone.setPassword(RedisPassword.of(p.password()));
   var options=ClientOptions.builder().requestQueueSize(128).disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
    .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofMillis(300)).build()).timeoutOptions(TimeoutOptions.enabled(Duration.ofMillis(500))).build();
   // Spring also puts commandTimeout into the URI used by Lettuce's initialization
   // handshake. Only the short-lived maintenance process gets cold-start headroom;
   // the fixed TimeoutOptions above still expire connected commands at 500ms.
   var initializationBudget=environment.acceptsProfiles(Profiles.of("coupon-maintenance"))?Duration.ofSeconds(5):Duration.ofMillis(500);
   var config=LettuceClientConfiguration.builder().clientResources(resources).clientOptions(options).commandTimeout(initializationBudget).shutdownTimeout(Duration.ofMillis(200)).build();
   var f=new LettuceConnectionFactory(standalone,config);f.setEagerInitialization(false);return f;
  }
  @Bean StringRedisTemplate couponRedisTemplate(@Qualifier("couponRedisConnectionFactory") LettuceConnectionFactory factory){return new StringRedisTemplate(factory);}
  @Bean CouponRedisStore couponRedisStore(@Qualifier("couponRedisTemplate") StringRedisTemplate redis,CouponRedisProperties p,ObjectMapper json){return new CouponRedisStore(redis,p.namespace(),json);}
 }
}
