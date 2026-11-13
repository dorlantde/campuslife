package com.campuslife.messaging.service;
import com.campuslife.messaging.model.OutboxMessage;
import java.time.LocalDateTime;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.ObjectProvider;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class OutboxPublisherTest {
 @ParameterizedTest @CsvSource({"1,1","2,2","3,4","6,32","7,60","8,60","20,60"})
 void retryBackoffIsBoundedAcrossPersistedAttempts(int attempts,long delay){assertThat(OutboxTransactions.backoff(attempts)).isEqualTo(delay);}
 @Test void concurrentTicksDoNotCreateAnUnboundedPublishQueue()throws Exception{
  var tx=mock(OutboxTransactions.class);var sender=mock(RabbitOutboxTransport.class);ObjectProvider<RabbitOutboxTransport> provider=mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(sender);
  var message=mock(OutboxMessage.class);when(tx.claim()).thenReturn(message);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
  when(sender.publish(message)).thenAnswer(i->{entered.countDown();assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();return OutboxTransactions.Outcome.ACK;});
  var publisher=new OutboxPublisher(tx,provider,true,false,32);
  try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
   var first=pool.submit(publisher::publishOne);assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
   for(int i=0;i<10;i++){assertThat(pool.submit(publisher::publishOne).get(1,TimeUnit.SECONDS)).isFalse();}
   release.countDown();assertThat(first.get(5,TimeUnit.SECONDS)).isTrue();
  }finally{release.countDown();}
  verify(tx,times(1)).claim();verify(tx).finish(message,OutboxTransactions.Outcome.ACK);publisher.stop();assertThat(publisher.publishOne()).isFalse();
 }
 @Test void disabledTransportDoesNotTouchTheDatabaseOrBroker(){var tx=mock(OutboxTransactions.class);ObjectProvider<RabbitOutboxTransport> provider=mock(ObjectProvider.class);var publisher=new OutboxPublisher(tx,provider,false,true,32);assertThat(publisher.publishOne()).isFalse();publisher.scheduled();verifyNoInteractions(tx,provider);publisher.stop();}

 @Test void shutdownStopsClaimsAndWaitsAtMostItsBudgetWhenTransportStalls()throws Exception{
  var tx=mock(OutboxTransactions.class);var sender=mock(RabbitOutboxTransport.class);ObjectProvider<RabbitOutboxTransport> provider=mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(sender);var message=mock(OutboxMessage.class);when(tx.claim()).thenReturn(message);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
  when(sender.publish(message)).thenAnswer(inv->{entered.countDown();release.await(25,TimeUnit.SECONDS);return OutboxTransactions.Outcome.ACK;});var publisher=new OutboxPublisher(tx,provider,true,false,32);
  try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
   var sending=pool.submit(publisher::publishOne);assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();long start=System.nanoTime();var stopping=pool.submit(publisher::stop);stopping.get(11,TimeUnit.SECONDS);assertThat(java.time.Duration.ofNanos(System.nanoTime()-start)).isBetween(java.time.Duration.ofMillis(7800),java.time.Duration.ofSeconds(11));assertThat(publisher.publishOne()).isFalse();release.countDown();sending.get(3,TimeUnit.SECONDS);
  }finally{release.countDown();}verify(tx,times(1)).claim();
 }
}
