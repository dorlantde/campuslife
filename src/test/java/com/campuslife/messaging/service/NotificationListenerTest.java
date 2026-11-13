package com.campuslife.messaging.service;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.*;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;
class NotificationListenerTest {
 private Message message(){var p=new MessageProperties();p.setMessageId("a".repeat(32));p.setContentType("application/json");p.setDeliveryTag(7);return new Message("{}".getBytes(),p);}
 @Test void ackOnlyAfterTransactionalServiceReturns()throws Exception{var d=mock(NotificationDelivery.class);var channel=mock(Channel.class);new NotificationListener(d).onMessage(message(),channel);var order=inOrder(d,channel);order.verify(d).consume(anyString(),any());order.verify(channel).basicAck(7,false);verify(channel,never()).basicReject(anyLong(),anyBoolean());}
 @Test void permanentFailureRejectsWithoutRetryOrAck()throws Exception{var d=mock(NotificationDelivery.class);var channel=mock(Channel.class);doThrow(new NotificationDelivery.InvalidMessage()).when(d).consume(anyString(),any());new NotificationListener(d).onMessage(message(),channel);verify(d,times(1)).consume(anyString(),any());verify(channel).basicReject(7,false);verify(channel,never()).basicAck(anyLong(),anyBoolean());}
 @Test void transientFailuresHaveThreeBoundedFreshCalls()throws Exception{var d=mock(NotificationDelivery.class);var channel=mock(Channel.class);doThrow(new org.springframework.dao.TransientDataAccessResourceException("injected")).when(d).consume(anyString(),any());new NotificationListener(d).onMessage(message(),channel);verify(d,times(3)).consume(anyString(),any());verify(channel).basicReject(7,false);verify(channel,never()).basicAck(anyLong(),anyBoolean());}
 @Test void transactionCreationFailureIsRetriedBeforeDeadLetter()throws Exception{var d=mock(NotificationDelivery.class);var c=mock(Channel.class);doThrow(new org.springframework.transaction.CannotCreateTransactionException("injected")).when(d).consume(anyString(),any());new NotificationListener(d).onMessage(message(),c);verify(d,times(3)).consume(anyString(),any());verify(c).basicReject(7,false);verify(c,never()).basicAck(anyLong(),anyBoolean());}
}
