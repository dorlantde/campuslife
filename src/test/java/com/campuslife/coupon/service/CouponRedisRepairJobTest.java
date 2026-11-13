package com.campuslife.coupon.service;
import com.campuslife.coupon.mapper.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class CouponRedisRepairJobTest {
 @Test void longRecoveryDoesNotBlockSchedulerOrQueueOverlappingRounds()throws Exception{
  var gates=mock(DispatchGateMapper.class);var rows=mock(CouponRecoveryMapper.class);var recovery=mock(CouponRedisRecovery.class);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
  when(gates.page(0)).thenAnswer(i->{entered.countDown();if(!release.await(5,TimeUnit.SECONDS)){throw new IllegalStateException("test timeout");}return List.of();});
  var job=new CouponRedisRepairJob(gates,rows,recovery,Clock.systemUTC(),true);
  try{long start=System.nanoTime();job.scheduled();assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(1));assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();job.scheduled();job.scheduled();verify(gates,times(1)).page(0);release.countDown();verify(rows,timeout(3000)).unsettled(0);}
  finally{release.countDown();job.close();}
 }
 @Test void shutdownInterruptsOwnedWorkerAndRejectsFurtherRounds()throws Exception{
  var gates=mock(DispatchGateMapper.class);var rows=mock(CouponRecoveryMapper.class);var entered=new CountDownLatch(1);var interrupted=new CountDownLatch(1);
  when(gates.page(0)).thenAnswer(i->{entered.countDown();try{Thread.sleep(60000);}catch(InterruptedException ex){interrupted.countDown();Thread.currentThread().interrupt();}return List.of();});
  var job=new CouponRedisRepairJob(gates,rows,mock(CouponRedisRecovery.class),Clock.systemUTC(),true);job.scheduled();assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();long start=System.nanoTime();job.close();assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(3));assertThat(interrupted.await(1,TimeUnit.SECONDS)).isTrue();job.scheduled();verify(gates,times(1)).page(0);verifyNoInteractions(rows);
 }
 @Test void disabledSchedulerDoesNotStartAnyRecovery(){var gates=mock(DispatchGateMapper.class);var rows=mock(CouponRecoveryMapper.class);var recovery=mock(CouponRedisRecovery.class);var job=new CouponRedisRepairJob(gates,rows,recovery,Clock.systemUTC(),false);try{job.scheduled();verifyNoInteractions(gates,rows,recovery);}finally{job.close();}}
}
