package com.campuslife.coupon.service;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.*;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class CouponIssueListenerTest {
 Message message(){var p=new MessageProperties();p.setContentType("application/json");p.setMessageId("a".repeat(32));p.setDeliveryTag(7);return new Message("{}".getBytes(),p);}
 @Test void ackHappensAfterTransactionalDeliveryReturns()throws Exception{var delivery=mock(CouponIssuance.class);var channel=mock(Channel.class);var m=message();new CouponIssueListener(delivery).onMessage(m,channel);var order=inOrder(delivery,channel);order.verify(delivery).consume(m.getMessageProperties().getMessageId(),m.getBody());order.verify(channel).basicAck(7,false);}
 @Test void invalidCommandRejectsWithoutAck()throws Exception{var delivery=mock(CouponIssuance.class);var channel=mock(Channel.class);doThrow(new CouponIssuance.InvalidMessage()).when(delivery).consume(any(),any());new CouponIssueListener(delivery).onMessage(message(),channel);verify(channel).basicReject(7,false);verify(channel,never()).basicAck(anyLong(),anyBoolean());}
 @Test void invalidContentTypeIsRejectedBeforeDatabaseWork()throws Exception{var delivery=mock(CouponIssuance.class);var channel=mock(Channel.class);var m=message();m.getMessageProperties().setContentType("text/plain");new CouponIssueListener(delivery).onMessage(m,channel);verifyNoInteractions(delivery);verify(channel).basicReject(7,false);}
 @Test void technicalFailuresHaveBoundedRetriesThenDeadLetter()throws Exception{var delivery=mock(CouponIssuance.class);var channel=mock(Channel.class);doThrow(new org.springframework.dao.DataAccessResourceFailureException("isolated")).when(delivery).consume(any(),any());new CouponIssueListener(delivery).onMessage(message(),channel);verify(delivery,times(3)).consume(any(),any());verify(channel).basicReject(7,false);verify(channel,never()).basicAck(anyLong(),anyBoolean());}
 @Test void ackFailureIsNotConvertedToRejectAfterCommit()throws Exception{var delivery=mock(CouponIssuance.class);var channel=mock(Channel.class);doThrow(new java.io.IOException("test lost ACK")).when(channel).basicAck(7,false);assertThatThrownBy(()->new CouponIssueListener(delivery).onMessage(message(),channel)).isInstanceOf(java.io.IOException.class);verify(channel,never()).basicReject(anyLong(),anyBoolean());}
}
