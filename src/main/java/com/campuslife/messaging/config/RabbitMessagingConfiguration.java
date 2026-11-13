package com.campuslife.messaging.config;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
@Configuration(proxyBeanMethods=false) @Profile("mysql")
@ConditionalOnProperty(name="campuslife.messaging.enabled",havingValue="true")
public class RabbitMessagingConfiguration {
 public static final String EXCHANGE="campuslife.events.v1",QUEUE="campuslife.notification.v1",DLX="campuslife.dead.v1",DEAD_QUEUE="campuslife.notification.dead.v1",ROUTING="notification",DEAD_ROUTING="notification.dead";
 public static final String COUPON_QUEUE="campuslife.coupon.issue.v1",COUPON_ROUTING="coupon.issue",COUPON_DEAD_QUEUE="campuslife.coupon.issue.dead.v1",COUPON_DEAD_ROUTING="coupon.issue.dead";
 @Bean(destroyMethod="destroy")
 public CachingConnectionFactory messagingConnectionFactory(@Value("${campuslife.messaging.host}") String host,@Value("${campuslife.messaging.port}") int port,
   @Value("${campuslife.messaging.username}") String username,@Value("${campuslife.messaging.password}") String password,@Value("${campuslife.messaging.vhost}") String vhost){
  if(username.isBlank()||password.isBlank()||vhost.isBlank()||username.equals("guest")){throw new IllegalArgumentException("Dedicated RabbitMQ credentials and vhost are required");}
  var f=new CachingConnectionFactory(host,port);f.setUsername(username);f.setPassword(password);f.setVirtualHost(vhost);
  f.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);f.setPublisherReturns(true);f.setChannelCacheSize(4);f.setChannelCheckoutTimeout(3000);f.setConnectionTimeout(2000);
  f.getRabbitConnectionFactory().useNio();f.getRabbitConnectionFactory().setNioParams(new com.rabbitmq.client.impl.nio.NioParams().setWriteQueueCapacity(64).setWriteEnqueuingTimeoutInMs(2000));f.getRabbitConnectionFactory().setShutdownTimeout(3000);
  f.getRabbitConnectionFactory().setHandshakeTimeout(3000);f.getRabbitConnectionFactory().setChannelRpcTimeout(3000);f.getRabbitConnectionFactory().setRequestedHeartbeat(10);
  return f;
 }
 @Bean public RabbitTemplate outboxRabbitTemplate(CachingConnectionFactory messagingConnectionFactory){
  var t=new RabbitTemplate(messagingConnectionFactory);t.setMandatory(true);
  // Four long-lived consumer channels can occupy the entire bounded consumer cache.
  // Publishers use the factory's separate bounded connection, retaining confirms and returns.
  t.setUsePublisherConnection(true);return t;
 }
 @Bean public RabbitAdmin messagingAdmin(RabbitTemplate outboxRabbitTemplate){
  // Declaration callbacks must use the publisher pool too, including reconnect initialization.
  var a=new RabbitAdmin(outboxRabbitTemplate);a.setIgnoreDeclarationExceptions(false);return a;
 }
 @Bean public Declarables messagingTopology(){
  var events=new DirectExchange(EXCHANGE,true,false);var dead=new DirectExchange(DLX,true,false);
  var queue=QueueBuilder.durable(QUEUE).quorum().deadLetterExchange(DLX).deadLetterRoutingKey(DEAD_ROUTING)
   .withArgument("x-delivery-limit",5).withArgument("x-overflow","reject-publish").withArgument("x-dead-letter-strategy","at-least-once").build();
  var dlq=QueueBuilder.durable(DEAD_QUEUE).quorum().build();
  var coupon=QueueBuilder.durable(COUPON_QUEUE).quorum().deadLetterExchange(DLX).deadLetterRoutingKey(COUPON_DEAD_ROUTING)
   .withArgument("x-delivery-limit",5).withArgument("x-overflow","reject-publish").withArgument("x-dead-letter-strategy","at-least-once").build();
  var couponDead=QueueBuilder.durable(COUPON_DEAD_QUEUE).quorum().build();
  return new Declarables(events,dead,queue,dlq,coupon,couponDead,BindingBuilder.bind(coupon).to(events).with(COUPON_ROUTING),BindingBuilder.bind(couponDead).to(dead).with(COUPON_DEAD_ROUTING),BindingBuilder.bind(queue).to(events).with(ROUTING),BindingBuilder.bind(dlq).to(dead).with(DEAD_ROUTING));
 }
 @Bean @Primary
 public org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer notificationContainer(CachingConnectionFactory messagingConnectionFactory,com.campuslife.messaging.service.NotificationListener listener,@Value("${campuslife.messaging.consumer-enabled:true}") boolean enabled){
  var c=new org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer(messagingConnectionFactory);c.setQueueNames(QUEUE);c.setConcurrentConsumers(2);c.setPrefetchCount(10);c.setAcknowledgeMode(AcknowledgeMode.MANUAL);c.setDefaultRequeueRejected(false);c.setAutoStartup(enabled);c.setMessageListener(listener);c.setShutdownTimeout(8000);return c;
 }
 @Bean
 public org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer couponIssueContainer(CachingConnectionFactory messagingConnectionFactory,com.campuslife.coupon.service.CouponIssueListener listener,@Value("${campuslife.messaging.consumer-enabled:true}") boolean enabled){
  var c=new org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer(messagingConnectionFactory);c.setQueueNames(COUPON_QUEUE);c.setConcurrentConsumers(2);c.setPrefetchCount(10);c.setAcknowledgeMode(AcknowledgeMode.MANUAL);c.setDefaultRequeueRejected(false);c.setAutoStartup(enabled);c.setMessageListener(listener);c.setShutdownTimeout(8000);return c;
 }
}
