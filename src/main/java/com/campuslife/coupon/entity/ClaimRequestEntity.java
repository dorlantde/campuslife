package com.campuslife.coupon.entity;
import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;
@TableName("coupon_claim_request")
public class ClaimRequestEntity {
    @TableId(type=IdType.AUTO)
    private Long id;
    private Long studentId;
    private Long campaignId;
    private String requestKey;
    private Long userCouponId;
    private LocalDateTime completedAt;
    public Long getId(){return id;}
    public void setId(Long value){id=value;}
    public Long getStudentId(){return studentId;}
    public void setStudentId(Long value){studentId=value;}
    public Long getCampaignId(){return campaignId;}
    public void setCampaignId(Long value){campaignId=value;}
    public String getRequestKey(){return requestKey;}
    public void setRequestKey(String value){requestKey=value;}
    public Long getUserCouponId(){return userCouponId;}
    public void setUserCouponId(Long value){userCouponId=value;}
    public LocalDateTime getCompletedAt(){return completedAt;}
    public void setCompletedAt(LocalDateTime value){completedAt=value;}
    private String status;
    public String getStatus(){return status;}
    public void setStatus(String value){status=value;}
    private String sourceMode;
    public String getSourceMode(){return sourceMode;}
    public void setSourceMode(String value){sourceMode=value;}
    private LocalDateTime createdAt;
    public LocalDateTime getCreatedAt(){return createdAt;}
    public void setCreatedAt(LocalDateTime value){createdAt=value;}
    private LocalDateTime acceptedAt;
    public LocalDateTime getAcceptedAt(){return acceptedAt;}
    public void setAcceptedAt(LocalDateTime value){acceptedAt=value;}
    private LocalDateTime deadline;
    public LocalDateTime getDeadline(){return deadline;}
    public void setDeadline(LocalDateTime value){deadline=value;}
    private String reservationId;
    public String getReservationId(){return reservationId;}
    public void setReservationId(String value){reservationId=value;}
    private Long acceptedGeneration;
    public Long getAcceptedGeneration(){return acceptedGeneration;}
    public void setAcceptedGeneration(Long value){acceptedGeneration=value;}
    private Long sourceCampaignVersion;
    public Long getSourceCampaignVersion(){return sourceCampaignVersion;}
    public void setSourceCampaignVersion(Long value){sourceCampaignVersion=value;}
    private String failureCode;
    public String getFailureCode(){return failureCode;}
    public void setFailureCode(String value){failureCode=value;}
    private Long redisSettledGeneration;
    public Long getRedisSettledGeneration(){return redisSettledGeneration;}
    public void setRedisSettledGeneration(Long value){redisSettledGeneration=value;}
}
