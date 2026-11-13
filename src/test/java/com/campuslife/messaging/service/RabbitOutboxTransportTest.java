package com.campuslife.messaging.service;
import com.campuslife.messaging.model.OutboxMessage;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class RabbitOutboxTransportTest {
 private OutboxMessage row(){var r=mock(OutboxMessage.class);when(r.messageId()).thenReturn("a".repeat(32));when(r.leaseToken()).thenReturn("b".repeat(32));when(r.payload()).thenReturn("{}");when(r.eventType()).thenReturn("BOOKING_ACCEPTED");when(r.aggregateType()).thenReturn("BOOKING");return r;}
 @Test void confirmTimeoutIsBoundedAndNeverClassifiedAsSuccess(){var template=mock(RabbitTemplate.class);long start=System.nanoTime();assertThat(new RabbitOutboxTransport(template).publish(row())).isEqualTo(OutboxTransactions.Outcome.TIMEOUT);assertThat(Duration.ofNanos(System.nanoTime()-start)).isBetween(Duration.ofMillis(2900),Duration.ofSeconds(6));}
 @Test void returnedMessageOverridesPositiveConfirm(){var template=mock(RabbitTemplate.class);doAnswer(i->{CorrelationData c=i.getArgument(3);c.setReturned(new ReturnedMessage(i.getArgument(2),312,"NO_ROUTE","events","key"));c.getFuture().complete(new CorrelationData.Confirm(true,null));return null;}).when(template).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));assertThat(new RabbitOutboxTransport(template).publish(row())).isEqualTo(OutboxTransactions.Outcome.RETURNED);}
 @Test void couponCommandUsesSeparateExplicitRoute(){var template=mock(RabbitTemplate.class);var r=row();when(r.eventType()).thenReturn("COUPON_ISSUE_REQUESTED");when(r.aggregateType()).thenReturn("COUPON_CLAIM");doAnswer(i->{CorrelationData c=i.getArgument(3);c.getFuture().complete(new CorrelationData.Confirm(true,null));return null;}).when(template).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));assertThat(new RabbitOutboxTransport(template).publish(r)).isEqualTo(OutboxTransactions.Outcome.ACK);verify(template).send(eq(com.campuslife.messaging.config.RabbitMessagingConfiguration.EXCHANGE),eq("coupon.issue"),any(Message.class),any(CorrelationData.class));}
 @Test void unsupportedTypeOrAggregateNeverReachesBroker(){var template=mock(RabbitTemplate.class);var r=row();when(r.aggregateType()).thenReturn("PRINT");assertThatThrownBy(()->new RabbitOutboxTransport(template).publish(r)).isInstanceOf(IllegalArgumentException.class);verifyNoInteractions(template);}
}
