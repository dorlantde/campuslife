package com.campuslife.messaging.service;
import com.campuslife.messaging.mapper.OutboxMapper;
import com.campuslife.order.entity.ServiceOrderEntity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;
@Service @Profile("mysql")
public class OutboxWriter {
 private final OutboxMapper rows;private final ObjectMapper json;
 public OutboxWriter(OutboxMapper rows,ObjectMapper json){this.rows=rows;this.json=json;}
 public record Payload(String orderNo,String shopName) {}
 public record Envelope(String messageId,String eventType,int schemaVersion,String aggregateType,String aggregateId,String eventSeq,String recipientId,Instant occurredAt,Payload payload) {}
 @Transactional(propagation=Propagation.MANDATORY)
 public void append(ServiceOrderEntity order,String type,long seq,LocalDateTime now){
  if(!(("BOOKING_ACCEPTED".equals(type)&&"BOOKING".equals(order.getOrderType()))||("PRINT_READY_FOR_PICKUP".equals(type)&&"PRINT".equals(order.getOrderType())))){throw new IllegalArgumentException("Unsupported notification event");}
  String id=UUID.randomUUID().toString().replace("-","");
  var envelope=new Envelope(id,type,1,order.getOrderType(),order.getId().toString(),Long.toString(seq),order.getStudentId().toString(),now.toInstant(ZoneOffset.UTC),new Payload(order.getOrderNo(),order.getShopNameSnapshot()));
  String body=json.writeValueAsString(envelope);byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
  if(bytes.length>16384){throw new IllegalStateException("Outbox envelope exceeds size limit");}
  try{String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
   if(rows.append(id,type,order.getOrderType(),order.getId(),seq,order.getStudentId(),body,hash,now)!=1){throw new IllegalStateException("Outbox write failed");}
  }catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
 }
}
