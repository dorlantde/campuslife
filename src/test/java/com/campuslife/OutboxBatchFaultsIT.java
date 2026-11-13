package com.campuslife;

import java.time.*;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

/** Includes the eight original real-JVM coupon windows via inheritance, plus three publisher windows. */
class OutboxBatchFaultsIT extends CouponFaultsIT {
 @ParameterizedTest @ValueSource(strings={"BEFORE_SEND","AFTER_CONFIRM","BETWEEN_MESSAGES"})
 void batchSurvivesRealProcessDeathWithoutPreclaim(String mode)throws Exception{
  var c=campaign(3);action(c,"Pause");action(c,"Async");action(c,"Resume");
  var rows=new ArrayList<com.campuslife.coupon.entity.ClaimRequestEntity>();
  for(int i=0;i<3;i++){var p=principal(account("STUDENT"));rows.add(accept(c,p,token(p),"batch_"+i));}
  var first=outbox.find(message(rows.getFirst()));String originalPayload=first.payload();
  var crash=child(mode,c);seam(crash);var held=outbox.find(first.messageId());
  boolean between=mode.equals("BETWEEN_MESSAGES");
  assertThat(held.status()).isEqualTo(between?"SENT":"PUBLISHING");assertThat(held.attempts()).isEqualTo(1);
  for(var r:rows.subList(1,3)){var o=outbox.find(message(r));assertThat(o.status()).isEqualTo("READY");assertThat(o.attempts()).isZero();assertThat(o.leaseToken()).isNull();}
  assertThat(issued(c)).isZero();assertThat(receiptCount()).isZero();
  if(!mode.equals("BEFORE_SEND")){await(()->queueStat("messages")>=1,20);}
  long start=System.nanoTime();kill(crash.process());var restart=child("NORMAL",c);
  await(()->issued(c)==3&&rows.stream().allMatch(r->"SUCCEEDED".equals(state(r).getStatus())&&Objects.equals(state(r).getRedisSettledGeneration(),gates.find(id(c)).generation())),85);
  await(()->rows.stream().allMatch(r->"SENT".equals(outbox.find(message(r)).status())),35);
  await(()->queueStat("messages")==0&&queueStat("messages_unacknowledged")==0,30);
  var recovered=outbox.find(first.messageId());assertThat(recovered.attempts()).isEqualTo(between?1:2);
  assertThat(recovered.payload()).isEqualTo(originalPayload);
  if(!between){assertThat(recovered.lastAttemptAt()).isAfterOrEqualTo(held.leaseUntil());}
  for(var r:rows){assertThat(state(r).getDeadline()).isEqualTo(r.getDeadline());assertThat(message(r)).isNotBlank();}
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_coupon WHERE campaign_id=?",Integer.class,id(c))).isEqualTo(3);
  assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT student_id) FROM user_coupon WHERE campaign_id=?",Integer.class,id(c))).isEqualTo(3);
  assertThat(receiptCount()).isEqualTo(3);assertThat(outboxCount(c)).isEqualTo(3);
  assertThat(stats(c)).isEqualTo(new com.campuslife.coupon.redis.CouponRedisStore.Stats(3,0,0,3));
  assertThat(admin.getQueueInfo(com.campuslife.messaging.config.RabbitMessagingConfiguration.COUPON_DEAD_QUEUE).getMessageCount()).isZero();
  record(mode,c,crash,restart,start);
 }
}
