package com.campuslife;
import com.campuslife.messaging.mapper.NotificationMapper;
import com.campuslife.messaging.service.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
/** Test-classpath only. Pauses real production operations at explicit crash windows. */
public final class MessagingCrashChild {
 public static void main(String[] args){
  if(!"owned-isolated-fixture".equals(System.getenv("CAMPUSLIFE_MQ_IT"))){throw new IllegalStateException("Owned fixture required");}
  boolean publisher="AFTER_CONFIRM".equals(System.getenv("CRASH_MODE"));
  new SpringApplicationBuilder(CampusLifeApplication.class,Hooks.class).profiles("mysql","messaging-crash-child").run("--server.port=0","--server.address=127.0.0.1","--campuslife.messaging.enabled=true","--campuslife.messaging.consumer-enabled="+!publisher,"--campuslife.messaging.scheduling-enabled="+publisher,"--campuslife.booking.expiry-enabled=false","--campuslife.print.expiry-enabled=false","--campuslife.files.cleanup-enabled=false");
  try{Files.writeString(Path.of(System.getenv("CRASH_MARKER")+".started"),"STARTED");}catch(Exception ex){throw new IllegalStateException(ex);}
 }
 static void pause(String id)throws Exception{if(!id.equals(System.getenv("CRASH_MESSAGE"))){return;}Files.writeString(Path.of(System.getenv("CRASH_MARKER")),System.getenv("CRASH_MODE"),StandardCharsets.UTF_8);for(;;){Thread.sleep(1000);}}
 static Object invoke(Object target,Method method,Object[] args)throws Throwable{try{return method.invoke(target,args);}catch(InvocationTargetException ex){throw ex.getCause();}}
 @Configuration(proxyBeanMethods=false) @Profile("messaging-crash-child")
 public static class Hooks {
  @Bean static BeanPostProcessor crashSeams(){return new BeanPostProcessor(){
   @Override public Object postProcessAfterInitialization(Object bean,String name){String mode=System.getenv("CRASH_MODE");
    if(bean instanceof NotificationMapper&&"BEFORE_COMMIT".equals(mode)){
     return Proxy.newProxyInstance(NotificationMapper.class.getClassLoader(),new Class<?>[]{NotificationMapper.class},(proxy,method,args)->{Object value=invoke(bean,method,args);if(method.getName().equals("create")){pause((String)args[0]);}return value;});
    }
    if(bean instanceof NotificationListener listener&&"BEFORE_ACK".equals(mode)){
     return new NotificationListener(null){@Override public void onMessage(org.springframework.amqp.core.Message message,com.rabbitmq.client.Channel channel)throws java.io.IOException{
      var gated=(com.rabbitmq.client.Channel)Proxy.newProxyInstance(com.rabbitmq.client.Channel.class.getClassLoader(),new Class<?>[]{com.rabbitmq.client.Channel.class},(proxy,method,args)->{if(method.getName().equals("basicAck")){pause(message.getMessageProperties().getMessageId());}return invoke(channel,method,args);});listener.onMessage(message,gated);
     }};
    }
    if(bean instanceof RabbitOutboxTransport sender&&"AFTER_CONFIRM".equals(mode)){
     return new RabbitOutboxTransport(null){@Override public OutboxTransactions.Outcome publish(com.campuslife.messaging.model.OutboxMessage row){var result=sender.publish(row);if(result==OutboxTransactions.Outcome.ACK){try{pause(row.messageId());}catch(Exception ex){throw new IllegalStateException(ex);}}return result;}};
    }
    return bean;
   }
  };}
 }
}
