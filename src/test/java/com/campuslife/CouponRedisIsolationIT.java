package com.campuslife;
import com.campuslife.coupon.config.CouponRedisProperties;
import com.campuslife.coupon.redis.CouponRedisStore;
import com.campuslife.merchant.config.RatingCacheProperties;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.*;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,properties={"campuslife.rating-cache.enabled=true"})
@ActiveProfiles("mysql")
@EnabledIfEnvironmentVariable(named="CAMPUSLIFE_CACHE_IT",matches="isolated-fixture")
class CouponRedisIsolationIT {
 @Autowired @Qualifier("ratingRedisTemplate") StringRedisTemplate rating;
 @Autowired @Qualifier("couponRedisTemplate") StringRedisTemplate coupon;
 @Autowired RatingCacheProperties ratingProperties;@Autowired CouponRedisProperties couponProperties;
 @Autowired CouponRedisStore store;
 @Test void sameApplicationUsesSeparateClientsInstancesAndDatasets(){
  assertThat(System.getenv("CAMPUSLIFE_COUPON_REDIS_IT")).isEqualTo("owned-isolated-fixture");assertThat(coupon).isNotSameAs(rating);assertThat(coupon.getConnectionFactory()).isNotSameAs(rating.getConnectionFactory());assertThat(couponProperties.port()).isNotEqualTo(ratingProperties.port());
  Properties info=rating.execute((RedisCallback<Properties>)c->c.serverCommands().info("server"));assertThat(info.getProperty("run_id")).isNotEqualTo(store.runId());
  String rk="campuslife:rating:"+ratingProperties.namespace()+":isolation",ck="coupon:"+couponProperties.namespace()+":{1}:isolation";
  try{rating.opsForValue().set(rk,"rating");coupon.opsForValue().set(ck,"coupon");assertThat(rating.opsForValue().get(rk)).isEqualTo("rating");assertThat(coupon.opsForValue().get(ck)).isEqualTo("coupon");assertThat(rating.opsForValue().get(ck)).isNull();assertThatThrownBy(()->coupon.opsForValue().get(rk)).isInstanceOf(RuntimeException.class);}
  finally{rating.delete(rk);coupon.delete(ck);}
 }
}
