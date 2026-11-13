package com.campuslife.coupon.service;

import com.campuslife.auth.mapper.AccountMapper;
import com.campuslife.coupon.entity.*;
import com.campuslife.coupon.mapper.*;
import com.campuslife.merchant.mapper.*;
import com.campuslife.messaging.mapper.OutboxMapper;
import com.campuslife.messaging.model.OutboxMessage;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;

@Service @Profile("mysql")
public class CouponIssuance {
    public static class InvalidMessage extends RuntimeException {
        public InvalidMessage(){super("Invalid coupon issuance command");}
    }
    private final AccountMapper accounts;
    private final ShopMapper shops;
    private final PublicShopMapper visible;
    private final CampaignMapper campaigns;
    private final DispatchGateMapper gates;
    private final ClaimRequestMapper requests;
    private final OwnedCouponMapper coupons;
    private final CouponConsumptionMapper receipts;
    private final OutboxMapper outbox;
    private final ObjectMapper json;
    private final Clock clock;
    public CouponIssuance(AccountMapper accounts,ShopMapper shops,PublicShopMapper visible,CampaignMapper campaigns,
                          DispatchGateMapper gates,ClaimRequestMapper requests,OwnedCouponMapper coupons,
                          CouponConsumptionMapper receipts,OutboxMapper outbox,ObjectMapper json,Clock clock){
        this.accounts=accounts;this.shops=shops;this.visible=visible;this.campaigns=campaigns;this.gates=gates;
        this.requests=requests;this.coupons=coupons;this.receipts=receipts;this.outbox=outbox;this.json=json;this.clock=clock;
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void consume(String messageId,byte[] bytes){
        var event=verified(messageId,bytes);
        var initial=requests.selectById(event.aggregateId());
        if(initial==null||initial.getStudentId()!=event.recipientId()){throw new InvalidMessage();}
        // All account locks precede shop/gate/campaign/request locks; no original HTTP session is required.
        var account=accounts.lockById(initial.getStudentId());
        var location=campaigns.selectById(initial.getCampaignId());
        if(account==null||location==null||shops.share(location.getShopId())==null){throw new IllegalStateException("Missing coupon admission owner/resource");}
        var gate=gates.share(initial.getCampaignId());requireGate(gate);
        var campaign=campaigns.lockOwn(initial.getCampaignId(),location.getShopId());
        var request=requests.lock(initial.getId());
        if(campaign==null||request==null){throw new InvalidMessage();}
        verifyRequest(event,request);
        var now=now();
        if(now.isBefore(request.getAcceptedAt())){throw new IllegalStateException("Clock precedes admission");}
        if("PENDING".equals(request.getStatus())){
            requireProcessing(gate);
            if(request.getDeadline().isAfter(campaign.getUseEndAt())||request.getAcceptedAt().isBefore(campaign.getPublishedAt())
                    ||!("PUBLISHED".equals(campaign.getStatus())||"STOPPED".equals(campaign.getStatus()))
                    ||request.getSourceCampaignVersion()!=("STOPPED".equals(campaign.getStatus())?campaign.getVersion()-1:campaign.getVersion())){
                throw new InvalidMessage();
            }
            String failure=null;
            if(!now.isBefore(request.getDeadline())||!now.isBefore(campaign.getUseEndAt())){failure="PROCESSING_EXPIRED";}
            else if(!"ACTIVE".equals(account.getStatus())||!"STUDENT".equals(account.getRole())||visible.visible(campaign.getShopId())==null){failure="QUALIFICATION_CHANGED";}
            else if(coupons.findCampaign(campaign.getId(),request.getStudentId())!=null){failure="ALREADY_CLAIMED";}
            else if(campaign.getIssuedCount()>=campaign.getTotalQuantity()){failure="SOLD_OUT";}
            if(failure!=null){one(requests.finish(request.getId(),"FAILED",null,failure,now));}
            else {
                var coupon=new OwnedCouponEntity();coupon.setStudentId(request.getStudentId());coupon.setCampaignId(campaign.getId());coupon.setShopId(campaign.getShopId());
                coupon.setNameSnapshot(campaign.getName());coupon.setThresholdAmount(campaign.getThresholdAmount());coupon.setReductionAmount(campaign.getReductionAmount());
                coupon.setUseStartAt(campaign.getUseStartAt());coupon.setUseEndAt(campaign.getUseEndAt());coupon.setSourceCampaignVersion(request.getSourceCampaignVersion());
                coupon.setClaimedAt(now);coupon.setState("AVAILABLE");coupon.setVersion(0L);
                one(campaigns.issueAdmitted(campaign.getId()));one(coupons.insert(coupon));
                one(requests.finish(request.getId(),"SUCCEEDED",coupon.getId(),null,now));
            }
        }
        // Receipt and business outcome commit together. A scanner terminal is also acknowledged once.
        receipts.receipt(messageId,event.payloadHash(),now);
        if(!event.payloadHash().equals(receipts.hash(messageId))){throw new InvalidMessage();}
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public boolean expire(long claimId){
        var initial=requests.selectById(claimId);
        if(initial==null||!"PENDING".equals(initial.getStatus())){return false;}
        if(accounts.lockById(initial.getStudentId())==null){throw new IllegalStateException("Missing admission owner");}
        var location=campaigns.selectById(initial.getCampaignId());
        if(location==null||shops.share(location.getShopId())==null){throw new IllegalStateException("Missing campaign shop");}
        var gate=gates.share(initial.getCampaignId());requireGate(gate);
        if(!"ASYNC".equals(gate.mode())||"REBUILDING".equals(gate.phase())){return false;}
        var r=requests.lock(claimId);var now=now();
        if(r==null||!"PENDING".equals(r.getStatus())||now.isBefore(r.getDeadline())){return false;}
        one(requests.finish(claimId,"FAILED",null,"PROCESSING_EXPIRED",now));return true;
    }
    private OutboxMessage verified(String id,byte[] bytes){
        if(id==null||!id.matches("[a-f0-9]{32}")||bytes==null||bytes.length>16384){throw new InvalidMessage();}
        var row=outbox.find(id);
        if(row==null||row.schemaVersion()!=1||!CouponIssueMessage.TYPE.equals(row.eventType())||!CouponIssueMessage.AGGREGATE.equals(row.aggregateType())||row.eventSeq()!=0
                ||!CouponIssueMessage.hash(bytes).equals(row.payloadHash())||!Arrays.equals(bytes,row.payload().getBytes(StandardCharsets.UTF_8))){throw new InvalidMessage();}
        try{
            var e=json.readValue(row.payload(),CouponIssueMessage.Envelope.class);
            if(!id.equals(e.messageId())||!row.eventType().equals(e.eventType())||e.schemaVersion()!=1||!row.aggregateType().equals(e.aggregateType())
                    ||!Long.toString(row.aggregateId()).equals(e.aggregateId())||!"0".equals(e.eventSeq())||!Long.toString(row.recipientId()).equals(e.recipientId())
                    ||!row.occurredAt().toInstant(ZoneOffset.UTC).equals(e.occurredAt())||e.payload()==null){throw new InvalidMessage();}
            return row;
        }catch(InvalidMessage ex){throw ex;}catch(Exception ex){throw new InvalidMessage();}
    }
    private void verifyRequest(OutboxMessage event,ClaimRequestEntity r){
        try {
            var p=json.readValue(event.payload(),CouponIssueMessage.Envelope.class).payload();
            if(!"ASYNC".equals(r.getSourceMode())||r.getAcceptedAt()==null||!event.occurredAt().equals(r.getAcceptedAt())
                    ||!r.getCampaignId().toString().equals(p.campaignId())||!r.getReservationId().equals(p.reservationId())
                    ||!r.getAcceptedGeneration().toString().equals(p.generation())||!r.getSourceCampaignVersion().toString().equals(p.sourceCampaignVersion())
                    ||!r.getDeadline().toInstant(ZoneOffset.UTC).equals(p.deadline())){throw new InvalidMessage();}
        }catch(InvalidMessage ex){throw ex;}catch(Exception ex){throw new InvalidMessage();}
    }
    private static void requireGate(DispatchGateMapper.Gate gate){if(gate==null){throw new IllegalStateException("Missing campaign dispatch gate");}}
    private static void requireProcessing(DispatchGateMapper.Gate gate){
        if(!"ASYNC".equals(gate.mode())||"REBUILDING".equals(gate.phase())){throw new DataAccessResourceFailureException("Coupon dispatch is paused for reconciliation");}
    }
    private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MILLIS),ZoneOffset.UTC);}
    private static void one(int n){if(n!=1){throw new IllegalStateException("Unexpected coupon issuance write count");}}
}
