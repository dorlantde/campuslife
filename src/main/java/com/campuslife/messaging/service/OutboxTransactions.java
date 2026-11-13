package com.campuslife.messaging.service;
import com.campuslife.messaging.mapper.OutboxMapper;
import com.campuslife.messaging.model.OutboxMessage;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
@Service @Profile("mysql")
public class OutboxTransactions {
 private final OutboxMapper rows;private final Clock clock;
 public OutboxTransactions(OutboxMapper rows,Clock clock){this.rows=rows;this.clock=clock;}
 public enum Outcome { ACK,RETURNED,NACK,TIMEOUT,CONNECTION_FAILED,SHUTDOWN }
 @Transactional(isolation=Isolation.READ_COMMITTED)
 public OutboxMessage claim(){
  var row=rows.lockDue(now());if(row==null){return null;}var time=now();
  var due="READY".equals(row.status())?row.nextAttemptAt():row.leaseUntil();
  if(time.isBefore(row.createdAt())||time.isBefore(due)){return null;}
  if(row.attempts()==20){one(rows.exhausted(row.messageId()));return null;}
  one(rows.claim(row.messageId(),UUID.randomUUID().toString().replace("-",""),time,time.plusSeconds(30)));
  return rows.find(row.messageId());
 }
 @Transactional(isolation=Isolation.READ_COMMITTED)
 public boolean finish(OutboxMessage row,Outcome outcome){
  var time=now();if(time.isBefore(row.lastAttemptAt())){time=row.lastAttemptAt();}
  boolean ok=outcome==Outcome.ACK,dead=!ok&&row.attempts()==20;
  return rows.finish(row.messageId(),row.leaseToken(),ok?"SENT":dead?"DEAD":"READY",ok||dead?null:time.plusSeconds(backoff(row.attempts())),ok?time:null,ok?null:outcome.name())==1;
 }
 public static long backoff(int attempts){return Math.min(60,1L<<Math.min(6,Math.max(0,attempts-1)));}
 private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MILLIS),ZoneOffset.UTC);}
 private static void one(int changed){if(changed!=1){throw new IllegalStateException("Outbox claim failed");}}
}
