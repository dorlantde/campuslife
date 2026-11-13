package com.campuslife.coupon.bootstrap;

import com.campuslife.auth.config.RegistrationConfiguration;
import com.campuslife.config.*;
import com.campuslife.coupon.config.*;
import com.campuslife.coupon.service.*;
import com.campuslife.merchant.config.MerchantConfiguration;
import com.campuslife.merchant.config.RatingCacheProperties;
import java.util.Map;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

/** Explicit local process; normal application scanning never activates this profile. */
@Configuration(proxyBeanMethods=false) @Profile("coupon-maintenance") @EnableAutoConfiguration
@EnableConfigurationProperties(RatingCacheProperties.class)
@Import({RegistrationConfiguration.class,MerchantConfiguration.class,CouponConfiguration.class,CouponRedisConfiguration.class,
 DatabaseMigrationConfiguration.class,TimeConfiguration.class,CouponRecoveryTransactions.class,CouponRedisRecovery.class,
 CouponMaintenanceTransactions.class,CouponMaintenance.class})
public class CouponMaintenanceBootstrap {
 public static void main(String[] args){
  if(args.length!=0){System.err.println("Use local maintenance environment only.");System.exit(1);}
  int exit=1;
  try(var ctx=new SpringApplicationBuilder(CouponMaintenanceBootstrap.class).web(WebApplicationType.NONE)
    .profiles("mysql","coupon-maintenance").properties(Map.of("spring.main.web-application-type","none")).run()){
   var env=ctx.getEnvironment();String id=env.getRequiredProperty("COUPON_CAMPAIGN_ID"),v=env.getProperty("COUPON_CONTROL_VERSION");
   if(!id.matches("[1-9][0-9]{0,18}")||(v!=null&&!v.matches("[0-9]{1,19}"))){throw new IllegalArgumentException();}
   var s=ctx.getBean(CouponMaintenance.class).run(env.getRequiredProperty("COUPON_MAINTENANCE_ACTION"),Long.parseLong(id),v==null?null:Long.parseLong(v),env.getProperty("COUPON_ALL_INSTANCES_UPGRADED",Boolean.class,false));
   System.out.printf("campaign=%d mode=%s phase=%s generation=%d controlVersion=%d pending=%d issued=%d actualCoupons=%d%n",s.campaign(),s.mode(),s.phase(),s.generation(),s.controlVersion(),s.pending(),s.issued(),s.actualCoupons());exit=0;
  }catch(CouponMaintenanceTransactions.Refused ex){System.err.println("Coupon maintenance refused: "+ex.getMessage());}
  catch(Exception ex){System.err.println("Coupon maintenance failed; inspect local configuration and durable gate before retrying. No credentials printed.");}
  System.exit(exit);
 }
}
