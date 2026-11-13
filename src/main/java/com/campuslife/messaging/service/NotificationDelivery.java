package com.campuslife.messaging.service;
import com.campuslife.messaging.mapper.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;
@Service @Profile("mysql")
public class NotificationDelivery {
 public static class InvalidMessage extends RuntimeException { public InvalidMessage(){super("Invalid notification envelope");} }
 private final OutboxMapper outbox;private final NotificationMapper notifications;private final ObjectMapper json;private final Clock clock;
 public NotificationDelivery(OutboxMapper outbox,NotificationMapper notifications,ObjectMapper json,Clock clock){this.outbox=outbox;this.notifications=notifications;this.json=json;this.clock=clock;}
 @Transactional(isolation=Isolation.READ_COMMITTED)
 public void consume(String id,byte[] bytes){
  if(id==null||!id.matches("[a-f0-9]{32}")||bytes==null||bytes.length>16384){throw new InvalidMessage();}
  var row=outbox.find(id);if(row==null){throw new InvalidMessage();}
  OutboxWriter.Envelope e;
  try{String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
   if(!hash.equals(row.payloadHash())||!Arrays.equals(bytes,row.payload().getBytes(StandardCharsets.UTF_8))){throw new InvalidMessage();}
   e=json.readValue(row.payload(),OutboxWriter.Envelope.class);
   if(!id.equals(e.messageId())||e.schemaVersion()!=1||!row.eventType().equals(e.eventType())||!row.aggregateType().equals(e.aggregateType())||!Long.toString(row.aggregateId()).equals(e.aggregateId())||!Long.toString(row.eventSeq()).equals(e.eventSeq())||!Long.toString(row.recipientId()).equals(e.recipientId())||!row.occurredAt().toInstant(ZoneOffset.UTC).equals(e.occurredAt())||e.payload()==null||e.payload().orderNo()==null||e.payload().shopName()==null||!("BOOKING_ACCEPTED".equals(e.eventType())||"PRINT_READY_FOR_PICKUP".equals(e.eventType()))){throw new InvalidMessage();}
  }catch(InvalidMessage ex){throw ex;}catch(Exception ex){throw new InvalidMessage();}
  var now=LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MILLIS),ZoneOffset.UTC);
  notifications.receipt(id,row.payloadHash(),now);
  if(!row.payloadHash().equals(notifications.receiptHash(id))){throw new InvalidMessage();}
  if(notifications.source(id)!=null){return;}
  if(notifications.create(id,row.recipientId(),row.eventType(),row.aggregateId(),e.payload().orderNo(),e.payload().shopName(),row.occurredAt(),now)!=1){throw new IllegalStateException("Notification write failed");}
 }
}
