package com.campuslife.coupon.entity;
import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;
@TableName("user_coupon")
public class OwnedCouponEntity {
    @TableId(type=IdType.AUTO)
    private Long id;
    private Long studentId;
    private Long campaignId;
    private Long shopId;
    private String nameSnapshot;
    private Long thresholdAmount;
    private Long reductionAmount;
    private LocalDateTime useStartAt;
    private LocalDateTime useEndAt;
    private Long sourceCampaignVersion;
    private LocalDateTime claimedAt;
    public Long getId(){return id;}
    public void setId(Long value){id=value;}
    public Long getStudentId(){return studentId;}
    public void setStudentId(Long value){studentId=value;}
    public Long getCampaignId(){return campaignId;}
    public void setCampaignId(Long value){campaignId=value;}
    public Long getShopId(){return shopId;}
    public void setShopId(Long value){shopId=value;}
    public String getNameSnapshot(){return nameSnapshot;}
    public void setNameSnapshot(String value){nameSnapshot=value;}
    public Long getThresholdAmount(){return thresholdAmount;}
    public void setThresholdAmount(Long value){thresholdAmount=value;}
    public Long getReductionAmount(){return reductionAmount;}
    public void setReductionAmount(Long value){reductionAmount=value;}
    public LocalDateTime getUseStartAt(){return useStartAt;}
    public void setUseStartAt(LocalDateTime value){useStartAt=value;}
    public LocalDateTime getUseEndAt(){return useEndAt;}
    public void setUseEndAt(LocalDateTime value){useEndAt=value;}
    public Long getSourceCampaignVersion(){return sourceCampaignVersion;}
    public void setSourceCampaignVersion(Long value){sourceCampaignVersion=value;}
    public LocalDateTime getClaimedAt(){return claimedAt;}
    public void setClaimedAt(LocalDateTime value){claimedAt=value;}
    private String state;
    private Long currentOrderId;
    private Long version;
    public String getState(){return state;}
    public void setState(String v){state=v;}
    public Long getCurrentOrderId(){return currentOrderId;}
    public void setCurrentOrderId(Long v){currentOrderId=v;}
    public Long getVersion(){return version;}
    public void setVersion(Long v){version=v;}
}
