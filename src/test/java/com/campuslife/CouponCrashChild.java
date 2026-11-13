package com.campuslife;

import com.campuslife.coupon.mapper.*;
import com.campuslife.coupon.redis.CouponRedisStore;
import com.campuslife.coupon.service.*;
import com.campuslife.messaging.mapper.OutboxMapper;
import com.campuslife.messaging.service.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.Map;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.*;
import tools.jackson.databind.ObjectMapper;

/** Test classpath only; owns no public fault endpoint. Parent kills this JVM at a real operation boundary. */
public final class CouponCrashChild {
 static String mode(){return System.getenv("COUPON_CRASH_MODE");}
 static long campaign(){return Long.parseLong(System.getenv("COUPON_CRASH_CAMPAIGN"));}
 static Path marker(){return Path.of(System.getenv("COUPON_CRASH_MARKER")).toAbsolutePath().normalize();}
 public static void main(String[] args)throws Exception{
  if(!"owned-isolated-fixture".equals(System.getenv("CAMPUSLIFE_MQ_IT"))||!"owned-isolated-fixture".equals(System.getenv("CAMPUSLIFE_COUPON_REDIS_IT"))||!"campuslife_it".equals(System.getenv("DB_NAME"))||campaign()<1||!marker().startsWith(Path.of("target").toAbsolutePath().normalize())){throw new IllegalStateException("Owned isolated fixture required");}
  boolean normal="NORMAL".equals(mode()),recover=normal||"RECOVER".equals(mode())||"BEFORE_CHECKPOINT".equals(mode());
  boolean consumer=normal||mode().equals("BEFORE_ISSUE_COMMIT")||mode().equals("BEFORE_ACK");
  boolean publisher=normal||mode().equals("BEFORE_SEND")||mode().equals("AFTER_CONFIRM")||mode().equals("BETWEEN_MESSAGES");
  var context=new SpringApplicationBuilder(CampusLifeApplication.class,Hooks.class).profiles("mysql","coupon-crash-child").run(
   "--server.port=0","--server.address=127.0.0.1","--campuslife.messaging.enabled=true",
   "--campuslife.messaging.consumer-enabled="+consumer,"--campuslife.messaging.scheduling-enabled="+publisher,
   "--campuslife.coupon-redis.scheduling-enabled="+recover,"--campuslife.coupon.expiry-enabled=false",
   "--campuslife.booking.expiry-enabled=false","--campuslife.print.expiry-enabled=false","--campuslife.files.cleanup-enabled=false");
  Files.writeString(Path.of(marker()+".started"),context.getEnvironment().getRequiredProperty("local.server.port"));
  if(mode().equals("AFTER_SEED")||mode().equals("AFTER_ACTIVATE")){
   var maintenance=context.getBean(CouponMaintenance.class);var state=maintenance.run("Inspect",campaign(),null,false);
   maintenance.run("Resume",campaign(),state.controlVersion(),true);
  }
 }
 static void pause()throws Exception{
  Files.writeString(marker(),new ObjectMapper().writeValueAsString(Map.of("mode",mode(),"campaign",campaign(),"at",java.time.Instant.now().toString(),"pid",ProcessHandle.current().pid())));
  for(;;){Thread.sleep(1000);}
 }
 static Object invoke(Object bean,Method method,Object[] args)throws Throwable{try{return method.invoke(bean,args);}catch(InvocationTargetException ex){throw ex.getCause();}}
 @Configuration(proxyBeanMethods=false) @Profile("coupon-crash-child")
 public static class Hooks {
  @Bean static BeanPostProcessor crashSeams(){return new BeanPostProcessor(){
   final java.util.concurrent.atomic.AtomicInteger claims=new java.util.concurrent.atomic.AtomicInteger();
   @Override public Object postProcessAfterInitialization(Object bean,String name){
    if(bean instanceof OutboxMapper&&mode().equals("BETWEEN_MESSAGES")){
     return Proxy.newProxyInstance(OutboxMapper.class.getClassLoader(),new Class<?>[]{OutboxMapper.class},(p,m,a)->{if(m.getName().equals("lockDue")&&claims.incrementAndGet()==2){pause();}return invoke(bean,m,a);});
    }
    if(bean instanceof RabbitOutboxTransport sender&&(mode().equals("BEFORE_SEND")||mode().equals("AFTER_CONFIRM"))){
     return new RabbitOutboxTransport(null){@Override public OutboxTransactions.Outcome publish(com.campuslife.messaging.model.OutboxMessage row){try{if(mode().equals("BEFORE_SEND")){pause();}var result=sender.publish(row);if(result==OutboxTransactions.Outcome.ACK){pause();}return result;}catch(Exception ex){throw new IllegalStateException(ex);}}};
    }
    if(bean instanceof OutboxMapper&&mode().equals("BEFORE_COMMIT")){
     return Proxy.newProxyInstance(OutboxMapper.class.getClassLoader(),new Class<?>[]{OutboxMapper.class},(p,m,a)->{Object result=invoke(bean,m,a);if(m.getName().equals("append")&&"COUPON_ISSUE_REQUESTED".equals(a[1])){pause();}return result;});
    }
    if(bean instanceof CouponConsumptionMapper&&mode().equals("BEFORE_ISSUE_COMMIT")){
     return Proxy.newProxyInstance(CouponConsumptionMapper.class.getClassLoader(),new Class<?>[]{CouponConsumptionMapper.class},(p,m,a)->{Object result=invoke(bean,m,a);if(m.getName().equals("receipt")){pause();}return result;});
    }
    if(bean instanceof CouponRecoveryMapper&&mode().equals("BEFORE_CHECKPOINT")){
     return Proxy.newProxyInstance(CouponRecoveryMapper.class.getClassLoader(),new Class<?>[]{CouponRecoveryMapper.class},(p,m,a)->{if(m.getName().equals("checkpoint")){pause();}return invoke(bean,m,a);});
    }
    if(bean instanceof CouponIssueListener listener&&mode().equals("BEFORE_ACK")){
     return new CouponIssueListener(null){@Override public void onMessage(org.springframework.amqp.core.Message message,com.rabbitmq.client.Channel channel)throws java.io.IOException{
      var gated=(com.rabbitmq.client.Channel)Proxy.newProxyInstance(com.rabbitmq.client.Channel.class.getClassLoader(),new Class<?>[]{com.rabbitmq.client.Channel.class},(p,m,a)->{if(m.getName().equals("basicAck")){pause();}return invoke(channel,m,a);});listener.onMessage(message,gated);
     }};
    }
    if(bean instanceof CouponRedisStore||bean instanceof CouponAdmissionService){
     var proxy=new ProxyFactory(bean);proxy.setProxyTargetClass(true);
     proxy.addAdvice((org.aopalliance.intercept.MethodInterceptor)call->{Object result=call.proceed();String method=call.getMethod().getName();
      if((mode().equals("AFTER_RESERVE")&&method.equals("reserve"))||(mode().equals("AFTER_ADMISSION")&&method.equals("claim"))||(mode().equals("AFTER_SEED")&&method.equals("seed"))||(mode().equals("AFTER_ACTIVATE")&&method.equals("activate"))){pause();}return result;});
     return proxy.getProxy();
    }
    return bean;
   }
  };}
 }
}
