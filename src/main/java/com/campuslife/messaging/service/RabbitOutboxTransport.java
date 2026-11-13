package com.campuslife.messaging.service;
import com.campuslife.messaging.config.RabbitMessagingConfiguration;
import com.campuslife.messaging.model.OutboxMessage;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static com.campuslife.messaging.service.OutboxTransactions.Outcome;
@Component @Profile("mysql") @ConditionalOnProperty(name="campuslife.messaging.enabled",havingValue="true")
public class RabbitOutboxTransport {
 private final RabbitTemplate template;
 public RabbitOutboxTransport(RabbitTemplate outboxRabbitTemplate){this.template=outboxRabbitTemplate;}
 public Outcome publish(OutboxMessage row){
  if(TransactionSynchronizationManager.isActualTransactionActive()){throw new IllegalStateException("Broker IO must be outside a database transaction");}
  String routing;
  if(("BOOKING_ACCEPTED".equals(row.eventType())&&"BOOKING".equals(row.aggregateType()))||("PRINT_READY_FOR_PICKUP".equals(row.eventType())&&"PRINT".equals(row.aggregateType()))){routing=RabbitMessagingConfiguration.ROUTING;}
  else if("COUPON_ISSUE_REQUESTED".equals(row.eventType())&&"COUPON_CLAIM".equals(row.aggregateType())&&row.eventSeq()==0){routing=RabbitMessagingConfiguration.COUPON_ROUTING;}
  else{throw new IllegalArgumentException("Unsupported outbox route");}
  var props=new MessageProperties();props.setMessageId(row.messageId());props.setContentType(MessageProperties.CONTENT_TYPE_JSON);props.setContentEncoding("UTF-8");props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
  var correlation=new CorrelationData(row.leaseToken());
  try{
   template.send(RabbitMessagingConfiguration.EXCHANGE,routing,new Message(row.payload().getBytes(StandardCharsets.UTF_8),props),correlation);
   var confirm=correlation.getFuture().get(3,TimeUnit.SECONDS);
   if(correlation.getReturned()!=null){return Outcome.RETURNED;}
   return confirm.ack()?Outcome.ACK:Outcome.NACK;
  }catch(TimeoutException ex){return Outcome.TIMEOUT;}
  catch(InterruptedException ex){Thread.currentThread().interrupt();return Outcome.SHUTDOWN;}
  catch(ExecutionException|org.springframework.amqp.AmqpException ex){return Outcome.CONNECTION_FAILED;}
 }
}
