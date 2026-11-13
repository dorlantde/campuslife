package com.campuslife.coupon.config;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
@ConfigurationProperties("campuslife.coupon-redis")
public record CouponRedisProperties(boolean enabled,@DefaultValue("") String namespace,@DefaultValue("127.0.0.1") String host,
 @DefaultValue("6379") int port,@DefaultValue("") String username,@DefaultValue("") String password) {
 public CouponRedisProperties {
  if(port<1||port>65535){throw new IllegalArgumentException("Invalid coupon Redis port");}
  if(enabled&&(namespace==null||!namespace.matches("[A-Za-z0-9_-]{1,64}")||username==null||username.isBlank()||username.equals("default")||password==null||password.length()<32)){
   throw new IllegalArgumentException("An explicit dedicated coupon Redis namespace and credentials are required");
  }
 }
 @Override public String toString(){return "CouponRedisProperties[enabled="+enabled+", credentials=REDACTED]";}
}
