package com.campuslife.coupon.service;

import com.campuslife.coupon.entity.ClaimRequestEntity;
import com.campuslife.messaging.mapper.OutboxMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;

/** A distinct command schema; notification consumers never handle coupon commands. */
@Service @Profile("mysql")
public class CouponIssueMessage {
    public static final String TYPE="COUPON_ISSUE_REQUESTED", AGGREGATE="COUPON_CLAIM";
    public record Payload(String campaignId,String reservationId,String generation,String sourceCampaignVersion,Instant deadline) {}
    public record Envelope(String messageId,String eventType,int schemaVersion,String aggregateType,String aggregateId,
                           String eventSeq,String recipientId,Instant occurredAt,Payload payload) {}
    private final OutboxMapper outbox;
    private final ObjectMapper json;
    public CouponIssueMessage(OutboxMapper outbox,ObjectMapper json){this.outbox=outbox;this.json=json;}

    @Transactional(propagation=Propagation.MANDATORY)
    public void append(ClaimRequestEntity r){
        if(!"PENDING".equals(r.getStatus())||!"ASYNC".equals(r.getSourceMode())||r.getId()==null||r.getAcceptedAt()==null){
            throw new IllegalArgumentException("Only a persisted async admission can enqueue issuance");
        }
        String id=UUID.randomUUID().toString().replace("-","");
        var payload=new Payload(r.getCampaignId().toString(),r.getReservationId(),r.getAcceptedGeneration().toString(),r.getSourceCampaignVersion().toString(),r.getDeadline().toInstant(ZoneOffset.UTC));
        var e=new Envelope(id,TYPE,1,AGGREGATE,r.getId().toString(),"0",r.getStudentId().toString(),r.getAcceptedAt().toInstant(ZoneOffset.UTC),payload);
        String body=json.writeValueAsString(e);
        if(body.getBytes(StandardCharsets.UTF_8).length>16384){throw new IllegalStateException("Coupon command too large");}
        if(outbox.append(id,TYPE,AGGREGATE,r.getId(),0,r.getStudentId(),body,hash(body.getBytes(StandardCharsets.UTF_8)),r.getAcceptedAt())!=1){throw new IllegalStateException("Coupon outbox write failed");}
    }
    static String hash(byte[] bytes){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
    }
}
