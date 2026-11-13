package com.campuslife.coupon.service;
import com.campuslife.coupon.mapper.ClaimRequestMapper;
import java.time.*;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
@Component @Profile("mysql")
public class CouponClaimExpiryJob {
 private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(CouponClaimExpiryJob.class);
 private final ClaimRequestMapper requests;private final CouponIssuance issuance;private final Clock clock;private final boolean enabled;
 public CouponClaimExpiryJob(ClaimRequestMapper requests,CouponIssuance issuance,Clock clock,@org.springframework.beans.factory.annotation.Value("${campuslife.coupon.expiry-enabled:true}") boolean enabled){this.requests=requests;this.issuance=issuance;this.clock=clock;this.enabled=enabled;}
 @Scheduled(initialDelay=1000,fixedDelay=1000)
 public void scheduled(){if(enabled){scan();}}
 public int scan(){
  int count=0;
  for(long id:requests.due(LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC))){
   try{if(issuance.expire(id)){count++;}}catch(RuntimeException ex){log.warn("Coupon expiry failed for claim {}; will retry",id);}
  }
  return count;
 }
}
